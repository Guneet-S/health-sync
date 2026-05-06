package com.healthsync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import java.time.Instant

class HealthSyncWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val serverAddress = prefs.getString(KEY_SERVER_IP, DEFAULT_SERVER) ?: DEFAULT_SERVER

        val manager = HealthConnectManager(context)
        val repository = SyncRepository(context)

        setForeground(getForegroundInfo())

        return try {
            if (!HealthConnectManager.isAvailable(context)) {
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
                prefs.edit()
                    .putString(KEY_LAST_STATUS, "Success: ${uploadResult.getOrNull()}")
                    .putLong(KEY_LAST_SYNC_TIME, now.toEpochMilli())
                    .apply()
                Result.success()
            } else {
                val error = uploadResult.exceptionOrNull()?.message ?: "unknown"
                prefs.edit().putString(KEY_LAST_STATUS, "Upload failed: $error (saved locally)").apply()
                Result.retry()
            }
        } catch (e: SecurityException) {
            prefs.edit().putString(KEY_LAST_STATUS, "PERMISSION_DENIED: HC permissions not granted — tap Grant Health Access").apply()
            Result.failure()
        } catch (e: Exception) {
            prefs.edit().putString(KEY_LAST_STATUS, "Error: ${e.message}").apply()
            Result.failure()
        }
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
        return ForegroundInfo(1, notification)
    }

    companion object {
        const val PREFS_NAME = "health_sync_prefs"
        const val KEY_SERVER_IP = "server_ip"
        const val KEY_LAST_STATUS = "last_sync_status"
        const val KEY_LAST_SYNC_TIME = "last_sync_time_ms"
        const val DEFAULT_SERVER = "192.168.18.12:5000"
    }
}
