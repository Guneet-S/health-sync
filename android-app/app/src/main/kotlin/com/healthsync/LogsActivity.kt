package com.healthsync

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.io.File

class LogsActivity : AppCompatActivity() {

    private lateinit var tvLogs: TextView
    private lateinit var scrollView: ScrollView
    private lateinit var etSearch: EditText
    private var allLines: List<String> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_logs)

        tvLogs = findViewById(R.id.tvLogs)
        scrollView = findViewById(R.id.scrollLogs)
        etSearch = findViewById(R.id.etSearch)
        val btnRefresh = findViewById<Button>(R.id.btnRefreshLogs)
        val btnClear = findViewById<Button>(R.id.btnClearLogs)
        val btnCopy = findViewById<Button>(R.id.btnCopyLogs)

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { applyFilter(s?.toString() ?: "") }
        })

        btnRefresh.setOnClickListener { loadLogs() }

        btnClear.setOnClickListener {
            try {
                File(filesDir, "sync_log.txt").delete()
                allLines = emptyList()
                tvLogs.text = "No sync history yet. Tap Sync Now or wait for auto-sync."
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

    private fun loadLogs() {
        val logFile = File(filesDir, "sync_log.txt")
        allLines = if (logFile.exists() && logFile.length() > 0) {
            logFile.readLines().filter { it.isNotBlank() }
        } else {
            emptyList()
        }
        applyFilter(etSearch.text?.toString() ?: "")
    }

    private fun applyFilter(query: String) {
        val filtered = if (query.isBlank()) allLines
                       else allLines.filter { it.contains(query, ignoreCase = true) }
        if (filtered.isEmpty()) {
            tvLogs.text = if (allLines.isEmpty())
                "No sync history yet. Tap Sync Now or wait for auto-sync."
            else
                "No lines match \"$query\"."
        } else {
            tvLogs.text = filtered.joinToString("\n")
            scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }
}
