package com.clukey.os.engine

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray

object VoiceNoteManager {
    private lateinit var prefs: SharedPreferences
    private val notes = mutableListOf<String>()

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.getSharedPreferences("clukey_voice_notes", Context.MODE_PRIVATE)
        val arr = try { JSONArray(prefs.getString("notes", "[]") ?: "[]") } catch (_: Exception) { JSONArray() }
        notes.clear()
        for (i in 0 until arr.length()) notes.add(arr.getString(i))
    }

    private fun save() {
        prefs.edit().putString("notes", JSONArray(notes).toString()).apply()
    }

    fun saveNote(text: String): String {
        notes.add(text.trim())
        save()
        return "Note saved: \"${text.trim()}\""
    }

    fun readNotes(): String {
        if (notes.isEmpty()) return "No notes saved."
        return notes.takeLast(5).mapIndexed { i, n -> "${i+1}. $n" }.joinToString("\n")
    }

    fun deleteLastNote(): String {
        if (notes.isEmpty()) return "No notes to delete."
        val removed = notes.removeLast()
        save()
        return "Deleted note: \"$removed\""
    }

    fun clearAll(): String {
        notes.clear()
        save()
        return "All notes cleared."
    }
}
