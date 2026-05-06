package com.healthsync

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
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

        val manager = HealthConnectManager(context)
        val repository = SyncRepository(context)

        appendSyncLog(context, "Auto-sync triggered")

        return try {
            try { setForeground(getForegroundInfo()) } catch (_: Exception) {}

            if (!HealthConnectManager.isAvailable(context)) {
                appendSyncLog(context, "FAIL: Health Connect unavailable")
                prefs.edit().putString(KEY_LAST_STATUS, "Failed: Health Connect unavailable").apply()
                return Result.failure()
            }
            val now = Instant.now()
            // Use last sync time as start, defaulting to 24h ago to handle Samsung Health delays
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
                return Result.success()
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
            appendSyncLog(context, "FAIL: Permission denied")
            prefs.edit().putString(KEY_LAST_STATUS, "PERMISSION_DENIED: HC permissions not granted — tap Grant Health Access").apply()
            Result.failure()
        } catch (e: Exception) {
            appendSyncLog(context, "ERROR: ${e.message}")
            prefs.edit().putString(KEY_LAST_STATUS, "Error: ${e.message}").apply()
            Result.failure()
        }
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
    }
}
