package com.healthsync

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.health.connect.client.HealthConnectClient
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class HealthSyncWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val serverAddress = prefs.getString(KEY_SERVER_IP, DEFAULT_SERVER) ?: DEFAULT_SERVER

        appendSyncLog(context, "Auto-sync triggered")

        // FIX 7: Mutex prevents overlapping worker executions from corrupting permission access
        return syncMutex.withLock {
            try {
                try { setForeground(getForegroundInfo()) } catch (_: Exception) {}

                // FIX 1: Fresh HealthConnectClient every worker run — never reuse a cached instance
                val healthConnectClient = HealthConnectClient.getOrCreate(applicationContext)

                // FIX 2: Fresh permission check inside worker — not cached, not from shared prefs
                val granted = healthConnectClient.permissionController.getGrantedPermissions()
                if (!granted.containsAll(HealthConnectManager.PERMISSIONS)) {
                    appendSyncLog(context, "FAIL: Permissions missing — open app to grant")
                    prefs.edit().putString(KEY_LAST_STATUS, "PERMISSION_DENIED: Open app to grant Health Connect access").apply()
                    // FIX 5: Notify user to open app — worker never requests permissions directly
                    showPermissionNotification(context)
                    return@withLock Result.retry()
                }

                if (!HealthConnectManager.isAvailable(context)) {
                    appendSyncLog(context, "FAIL: Health Connect unavailable")
                    prefs.edit().putString(KEY_LAST_STATUS, "Failed: Health Connect unavailable").apply()
                    return@withLock Result.failure()
                }

                val manager = HealthConnectManager(context)
                val repository = SyncRepository(context)

                val now = Instant.now()
                val lastSyncMs = prefs.getLong(KEY_LAST_SYNC_TIME, 0L)
                val start = if (lastSyncMs > 0) Instant.ofEpochMilli(lastSyncMs) else now.minusSeconds(86400)

                val steps = manager.readSteps(start, now)
                val heartRate = manager.readHeartRate(start, now)
                val sleep = manager.readSleep(start, now)
                val calories = manager.readCalories(start, now)
                val spo2 = manager.readSpO2(start, now)

                val allZero = steps.total == 0 && heartRate.avg == 0.0 && calories.total == 0.0
                if (allZero) {
                    appendSyncLog(context, "SKIP: No new data from Samsung Health")
                    prefs.edit()
                        .putString(KEY_LAST_STATUS, "No new data (Samsung Health hasn't synced to Health Connect yet — try again later)")
                        .putLong(KEY_LAST_SYNC_TIME, now.toEpochMilli())
                        .apply()
                    return@withLock Result.success()
                }

                val payload = repository.buildPayload(steps, heartRate, sleep, calories, spo2)
                repository.saveLocally(payload)

                val uploadResult = repository.uploadToServer(payload, serverAddress)
                if (uploadResult.isSuccess) {
                    appendSyncLog(context, "SUCCESS: steps=${steps.total} hr=${heartRate.avg} cal=${calories.total}")
                    prefs.edit()
                        .putString(KEY_LAST_STATUS, "Success: ${uploadResult.getOrNull()}")
                        .putLong(KEY_LAST_SYNC_TIME, now.toEpochMilli())
                        .apply()
                    Result.success()
                } else {
                    val error = uploadResult.exceptionOrNull()?.message ?: "unknown"
                    appendSyncLog(context, "FAIL: Upload error — $error")
                    prefs.edit().putString(KEY_LAST_STATUS, "Upload failed: $error (saved locally)").apply()
                    Result.retry()
                }
            } catch (e: SecurityException) {
                // FIX 3: SecurityException during reads → retry silently.
                // Permissions ARE granted (checked above). HC background context is temporarily
                // restricted on some devices — fresh client on retry resolves it automatically.
                // No notification needed — this is not a user error.
                appendSyncLog(context, "FAIL: HC background read blocked — retrying automatically")
                prefs.edit().putString(KEY_LAST_STATUS, "Background read blocked — retrying automatically").apply()
                Result.retry()
            } catch (e: Exception) {
                appendSyncLog(context, "ERROR: ${e.message}")
                prefs.edit().putString(KEY_LAST_STATUS, "Error: ${e.message}").apply()
                Result.failure()
            }
        }
    }

    // FIX 5: Notify user to open app — background workers cannot show permission dialogs
    private fun showPermissionNotification(context: Context) {
        val channelId = "health_sync_permission"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "HealthSync Alerts", NotificationManager.IMPORTANCE_HIGH)
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, channelId)
            .setContentTitle("HealthSync — Action Required")
            .setContentText("Health Connect permissions needed. Tap to open app.")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(2, notification)
    }

    private fun appendSyncLog(context: Context, message: String) {
        try {
            val ts = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(ZoneId.systemDefault())
                .format(Instant.now())
            val line = "[$ts] $message\n"
            File(context.filesDir, "sync_log.txt").appendText(line)
        } catch (_: Exception) {}
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val channelId = "health_sync"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "HealthSync", NotificationManager.IMPORTANCE_LOW)
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
        val notification = NotificationCompat.Builder(context, channelId)
            .setContentTitle("HealthSync")
            .setContentText("Syncing health data...")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ForegroundInfo(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE)
        } else {
            ForegroundInfo(1, notification)
        }
    }

    companion object {
        const val PREFS_NAME = "health_sync_prefs"
        const val KEY_SERVER_IP = "server_ip"
        const val KEY_LAST_STATUS = "last_sync_status"
        const val KEY_LAST_SYNC_TIME = "last_sync_time_ms"
        const val DEFAULT_SERVER = "192.168.18.12:5000"

        // FIX 7: Shared mutex — prevents overlapping worker runs
        val syncMutex = Mutex()
    }
}
