package com.healthsync

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class LogsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_logs)

        val tvLogs = findViewById<TextView>(R.id.tvLogs)
        val btnRefresh = findViewById<Button>(R.id.btnRefreshLogs)
        val btnClear = findViewById<Button>(R.id.btnClearLogs)
        val btnCopy = findViewById<Button>(R.id.btnCopyLogs)
        val scrollView = findViewById<ScrollView>(R.id.scrollLogs)

        fun loadLogs() {
            tvLogs.text = "Loading..."
            try {
                val pid = android.os.Process.myPid()
                val process = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "--pid=$pid"))
                val output = process.inputStream.bufferedReader().readText()
                tvLogs.text = if (output.isBlank()) "No logs yet." else output
                scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
            } catch (e: Exception) {
                tvLogs.text = "Error reading logs: ${e.message}"
            }
        }

        btnRefresh.setOnClickListener { loadLogs() }

        btnClear.setOnClickListener {
            try {
                Runtime.getRuntime().exec(arrayOf("logcat", "-c"))
                tvLogs.text = "Logs cleared."
                Toast.makeText(this, "Logs cleared", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "Clear failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }

        btnCopy.setOnClickListener {
            val text = tvLogs.text.toString()
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("HealthSync Logs", text))
            Toast.makeText(this, "Logs copied to clipboard", Toast.LENGTH_SHORT).show()
        }

        loadLogs()
    }
}
