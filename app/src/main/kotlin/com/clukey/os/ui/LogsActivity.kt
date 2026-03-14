package com.clukey.os.ui

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.clukey.os.R
import com.clukey.os.utils.AppLogger
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class LogsActivity : AppCompatActivity() {

    private lateinit var txtLogs: TextView
    private lateinit var scrollLogs: ScrollView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_logs)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = "CluKey Logs"

        txtLogs   = findViewById(R.id.txtLogs)
        scrollLogs = findViewById(R.id.scrollLogs)

        lifecycleScope.launch {
            AppLogger.entries.collectLatest { entries ->
                val text = entries.joinToString("\n") {
                    "[${it.time}] ${it.level}/${it.tag}: ${it.message}"
                }
                txtLogs.text = text
                scrollLogs.post { scrollLogs.fullScroll(android.view.View.FOCUS_DOWN) }
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed(); return true
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        // Add clear option programmatically — no menu XML needed
        menu.add(0, 1, 0, "Clear Logs")
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == 1) {
            AppLogger.clear()
            txtLogs.text = ""
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}
