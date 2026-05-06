package com.healthsync

import android.content.Context
import com.google.gson.Gson
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.time.Instant
import java.util.concurrent.TimeUnit

class SyncRepository(private val context: Context) {

    data class HealthPayload(
        val timestamp: String,
        val steps: StepsData,
        val heart_rate: HeartRateData,
        val sleep: SleepData,
        val calories: CaloriesData,
        val spo2: SpO2Data
    )

    private val gson = Gson()

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    fun buildPayload(
        steps: StepsData,
        heartRate: HeartRateData,
        sleep: SleepData,
        calories: CaloriesData,
        spo2: SpO2Data
    ): HealthPayload = HealthPayload(
        timestamp = Instant.now().toString(),
        steps = steps,
        heart_rate = heartRate,
        sleep = sleep,
        calories = calories,
        spo2 = spo2
    )

    fun saveLocally(payload: HealthPayload) {
        val dir = File(context.getExternalFilesDir(null), "HealthSync")
        if (!dir.exists()) dir.mkdirs()
        val safeTimestamp = payload.timestamp.replace(":", "-").replace(".", "-")
        File(dir, "data_$safeTimestamp.json").writeText(gson.toJson(payload))
    }

    /**
     * POST payload to server with 3 attempts and exponential backoff (1s, 2s, 4s).
     * Returns Result.success with response message, or Result.failure with last error.
     */
    fun uploadToServer(payload: HealthPayload, serverAddress: String): Result<String> {
        val url = if (serverAddress.startsWith("http")) {
            serverAddress
        } else {
            "http://$serverAddress/upload"
        }
        val json = gson.toJson(payload)
        val body = json.toRequestBody("application/json".toMediaType())

        var lastError: Exception = IOException("Upload failed after 3 attempts")
        var delayMs = 1000L

        repeat(3) { attempt ->
            try {
                if (attempt > 0) Thread.sleep(delayMs)
                val request = Request.Builder().url(url).post(body).build()
                val response = httpClient.newCall(request).execute()
                if (response.isSuccessful) {
                    return Result.success("HTTP ${response.code} from $url")
                }
                lastError = IOException("HTTP ${response.code}")
            } catch (e: Exception) {
                lastError = e
            }
            delayMs *= 2
        }

        return Result.failure(lastError)
    }
}
