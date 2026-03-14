package com.clukey.os.utils

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AppLogger {
    private const val MAX_LOG = 500

    data class LogEntry(
        val time: String,
        val level: String,
        val tag: String,
        val message: String
    )

    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries: StateFlow<List<LogEntry>> = _entries.asStateFlow()

    fun init(context: Context) { /* no-op, can extend to file logging */ }

    fun i(tag: String, msg: String) { Log.i(tag, msg); store("I", tag, msg) }
    fun e(tag: String, msg: String, t: Throwable? = null) {
        if (t != null) Log.e(tag, msg, t) else Log.e(tag, msg)
        store("E", tag, if (t != null) "$msg — ${t.message}" else msg)
    }
    fun w(tag: String, msg: String) { Log.w(tag, msg); store("W", tag, msg) }
    fun d(tag: String, msg: String) { Log.d(tag, msg); store("D", tag, msg) }

    private fun store(level: String, tag: String, msg: String) {
        val entry = LogEntry(
            time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date()),
            level = level,
            tag = tag,
            message = msg
        )
        synchronized(this) {
            val current = _entries.value.toMutableList()
            if (current.size >= MAX_LOG) current.removeAt(0)
            current.add(entry)
            _entries.value = current
        }
    }

    fun getLogs(): List<String> = _entries.value.map { "[${it.time}] ${it.level}/${it.tag}: ${it.message}" }
    fun clear() { _entries.value = emptyList() }
}
