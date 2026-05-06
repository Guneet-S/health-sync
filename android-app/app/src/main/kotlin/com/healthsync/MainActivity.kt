package com.healthsync

import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.lifecycleScope
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.healthsync.BuildConfig
import com.healthsync.HealthSyncWorker.Companion.DEFAULT_SERVER
import com.healthsync.HealthSyncWorker.Companion.KEY_LAST_STATUS
import com.healthsync.HealthSyncWorker.Companion.KEY_SERVER_IP
import com.healthsync.HealthSyncWorker.Companion.PREFS_NAME
import com.healthsync.databinding.ActivityMainBinding
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SharedPreferences
    private lateinit var healthConnectManager: HealthConnectManager

    private val permissionLauncher = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { granted ->
        Log.d("HC_DEBUG", "Granted permissions: $granted")

        if (granted.containsAll(HealthConnectManager.PERMISSIONS)) {
            Log.d("HC_DEBUG", "ALL PERMISSIONS GRANTED")
            showGrantButton(false)
            Toast.makeText(this, "Permissions granted — background sync enabled", Toast.LENGTH_SHORT).show()
            schedulePeriodicSync()
        } else {
            Log.d("HC_DEBUG", "MISSING PERMISSIONS: ${HealthConnectManager.PERMISSIONS - granted}")
            showGrantButton(true)
            Toast.makeText(this, "Some permissions not granted. Tap Grant Health Access to try again.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        healthConnectManager = HealthConnectManager(this)

        binding.etServerIp.setText(prefs.getString(KEY_SERVER_IP, DEFAULT_SERVER))
        binding.tvVersion.text = "v${BuildConfig.VERSION_NAME}"
        refreshStatus()

        binding.btnSyncNow.setOnClickListener {
            saveServerIp()
            triggerManualSync()
        }

        binding.btnGrantAccess.setOnClickListener {
            permissionLauncher.launch(HealthConnectManager.PERMISSIONS)
        }

        binding.btnViewLogs.setOnClickListener {
            startActivity(Intent(this, LogsActivity::class.java))
        }

        checkAndRequestPermissions()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 100)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        lifecycleScope.launch {
            if (HealthConnectManager.isAvailable(this@MainActivity) && healthConnectManager.hasAllPermissions()) {
                showGrantButton(false)
                schedulePeriodicSync()
            }
        }
    }

    private fun checkAndRequestPermissions() {
        when (HealthConnectManager.getSdkStatus(this)) {
            HealthConnectClient.SDK_AVAILABLE -> {
                lifecycleScope.launch {
                    if (healthConnectManager.hasAllPermissions()) {
                        showGrantButton(false)
                        schedulePeriodicSync()
                    } else {
                        showGrantButton(true)
                        permissionLauncher.launch(HealthConnectManager.PERMISSIONS)
                    }
                }
            }
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> {
                showGrantButton(false)
                Toast.makeText(this, "Please update Health Connect from Play Store", Toast.LENGTH_LONG).show()
                openPlayStore()
            }
            else -> {
                // SDK_UNAVAILABLE
                showGrantButton(false)
                Toast.makeText(this, "Health Connect is not installed. Please install it from Play Store.", Toast.LENGTH_LONG).show()
                openPlayStore()
            }
        }
    }

    private fun showGrantButton(show: Boolean) {
        binding.btnGrantAccess.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun openPlayStore() {
        try {
            startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse("market://details?id=com.google.android.apps.healthdata")))
        } catch (_: Exception) {
            startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=com.google.android.apps.healthdata")))
        }
    }

    private fun saveServerIp() {
        val ip = binding.etServerIp.text.toString().trim()
        if (ip.isNotEmpty()) {
            prefs.edit().putString(KEY_SERVER_IP, ip).apply()
        }
    }

    private fun triggerManualSync() {
        binding.btnSyncNow.isEnabled = false
        binding.tvStatus.text = "Status: Syncing..."

        val request = OneTimeWorkRequestBuilder<HealthSyncWorker>()
            .setConstraints(networkConstraints())
            .build()

        WorkManager.getInstance(this)
            .enqueueUniqueWork("manual_sync", ExistingWorkPolicy.REPLACE, request)

        WorkManager.getInstance(this)
            .getWorkInfoByIdLiveData(request.id)
            .observe(this) { info ->
                if (info?.state?.isFinished == true) {
                    binding.btnSyncNow.isEnabled = true
                    refreshStatus()
                    val msg = prefs.getString(KEY_LAST_STATUS, "Sync complete") ?: "Sync complete"
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                }
            }
    }

    private fun schedulePeriodicSync() {
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "periodic_health_sync",
            ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<HealthSyncWorker>(15, TimeUnit.MINUTES, 5, TimeUnit.MINUTES)
                .setConstraints(networkConstraints())
                // FIX 4: Exponential backoff prevents retry spam on permission/network failures
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
        )
    }

    private fun networkConstraints() = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    private fun refreshStatus() {
        val status = prefs.getString(KEY_LAST_STATUS, "Not synced yet") ?: "Not synced yet"
        binding.tvStatus.text = "Status: $status"
        if (status.startsWith("PERMISSION_DENIED")) {
            showGrantButton(true)
        }
    }
}
