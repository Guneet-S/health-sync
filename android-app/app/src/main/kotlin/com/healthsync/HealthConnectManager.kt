package com.healthsync

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Duration
import java.time.Instant

class HealthConnectManager(private val context: Context) {

    private val client by lazy { HealthConnectClient.getOrCreate(context) }

    companion object {
        val PERMISSIONS = setOf(
            HealthPermission.getReadPermission(StepsRecord::class),
            HealthPermission.getReadPermission(HeartRateRecord::class),
            HealthPermission.getReadPermission(SleepSessionRecord::class),
            HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class),
            HealthPermission.getReadPermission(OxygenSaturationRecord::class)
        )

        fun getSdkStatus(context: Context): Int =
            HealthConnectClient.getSdkStatus(context)

        fun isAvailable(context: Context): Boolean =
            getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE
    }

    suspend fun hasAllPermissions(): Boolean {
        val granted = client.permissionController.getGrantedPermissions()
        return granted.containsAll(PERMISSIONS)
    }

    suspend fun readSteps(start: Instant, end: Instant): StepsData {
        val response = client.readRecords(
            ReadRecordsRequest(
                recordType = StepsRecord::class,
                timeRangeFilter = TimeRangeFilter.between(start, end)
            )
        )
        val total = response.records.sumOf { it.count }
        return StepsData(total = total.toInt(), date = end.toString())
    }

    suspend fun readHeartRate(start: Instant, end: Instant): HeartRateData {
        val response = client.readRecords(
            ReadRecordsRequest(
                recordType = HeartRateRecord::class,
                timeRangeFilter = TimeRangeFilter.between(start, end)
            )
        )
        val samples = response.records.flatMap { it.samples }
        return if (samples.isEmpty()) {
            HeartRateData(avg = 0.0, min = 0.0, max = 0.0, date = end.toString())
        } else {
            val bpm = samples.map { it.beatsPerMinute.toDouble() }
            HeartRateData(
                avg = bpm.average(),
                min = bpm.min(),
                max = bpm.max(),
                date = end.toString()
            )
        }
    }

    suspend fun readSleep(start: Instant, end: Instant): SleepData {
        val response = client.readRecords(
            ReadRecordsRequest(
                recordType = SleepSessionRecord::class,
                timeRangeFilter = TimeRangeFilter.between(start, end)
            )
        )
        val totalMinutes = response.records.sumOf { record ->
            Duration.between(record.startTime, record.endTime).toMinutes()
        }
        return SleepData(duration_hours = totalMinutes / 60.0, date = end.toString())
    }

    suspend fun readCalories(start: Instant, end: Instant): CaloriesData {
        val response = client.readRecords(
            ReadRecordsRequest(
                recordType = TotalCaloriesBurnedRecord::class,
                timeRangeFilter = TimeRangeFilter.between(start, end)
            )
        )
        val total = response.records.sumOf { it.energy.inKilocalories }
        return CaloriesData(total = total, date = end.toString())
    }

    suspend fun readSpO2(start: Instant, end: Instant): SpO2Data {
        val response = client.readRecords(
            ReadRecordsRequest(
                recordType = OxygenSaturationRecord::class,
                timeRangeFilter = TimeRangeFilter.between(start, end)
            )
        )
        return if (response.records.isEmpty()) {
            SpO2Data(avg = 0.0, min = 0.0, date = end.toString())
        } else {
            val values = response.records.map { it.percentage.value }
            SpO2Data(avg = values.average(), min = values.min(), date = end.toString())
        }
    }
}

data class StepsData(val total: Int, val date: String)
data class HeartRateData(val avg: Double, val min: Double, val max: Double, val date: String)
data class SleepData(val duration_hours: Double, val date: String)
data class CaloriesData(val total: Double, val date: String)
data class SpO2Data(val avg: Double, val min: Double, val date: String)
