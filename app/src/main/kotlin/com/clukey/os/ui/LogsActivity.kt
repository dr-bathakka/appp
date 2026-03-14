package com.clukey.os.ui

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.clukey.os.R
import com.clukey.os.databinding.ActivityLogsBinding
import com.clukey.os.utils.AppLogger
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * LogsActivity — real-time scrolling log viewer for debugging.
 * Observes AppLogger.entries StateFlow and displays each entry.
 */
class LogsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLogsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLogsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = "CluKey Logs"

        lifecycleScope.launch {
            AppLogger.entries.collect { entries ->
                val text = entries.joinToString("\n") {
                    "[${it.time}] ${it.level}/${it.tag}: ${it.message}"
                }
                binding.txtLogs.text = text
                binding.scrollLogs.post { binding.scrollLogs.fullScroll(android.view.View.FOCUS_DOWN) }
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed(); return true
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_logs, menu); return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.action_clear_logs) {
            AppLogger.clear()
            binding.txtLogs.text = ""
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}
