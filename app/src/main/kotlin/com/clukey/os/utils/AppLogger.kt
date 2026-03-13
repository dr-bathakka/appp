package com.clukey.os.utils

import android.content.Context
import android.util.Log

object AppLogger {
    private const val MAX_LOG = 500
    private val logs = ArrayDeque<String>(MAX_LOG)

    fun init(context: Context) {
        // No-op: can be extended to write to file
    }

    fun i(tag: String, msg: String) { Log.i(tag, msg); store("I", tag, msg) }
    fun e(tag: String, msg: String, t: Throwable? = null) {
        if (t != null) Log.e(tag, msg, t) else Log.e(tag, msg)
        store("E", tag, if (t != null) "$msg — ${t.message}" else msg)
    }
    fun w(tag: String, msg: String) { Log.w(tag, msg); store("W", tag, msg) }
    fun d(tag: String, msg: String) { Log.d(tag, msg); store("D", tag, msg) }

    private fun store(level: String, tag: String, msg: String) {
        val entry = "[${level}/${tag}] $msg"
        synchronized(logs) {
            if (logs.size >= MAX_LOG) logs.removeFirst()
            logs.addLast(entry)
        }
    }

    fun getLogs(): List<String> = synchronized(logs) { logs.toList() }
    fun clear() = synchronized(logs) { logs.clear() }
}
