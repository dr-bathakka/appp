package com.clukey.os.engine

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray

object ShoppingListManager {
    private lateinit var prefs: SharedPreferences
    private val items = mutableListOf<String>()

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.getSharedPreferences("clukey_shopping", Context.MODE_PRIVATE)
        load()
    }

    private fun load() {
        items.clear()
        val json = prefs.getString("items", "[]") ?: "[]"
        val arr = JSONArray(json)
        for (i in 0 until arr.length()) items.add(arr.getString(i))
    }

    private fun save() {
        val arr = JSONArray(items)
        prefs.edit().putString("items", arr.toString()).apply()
    }

    fun addItem(item: String): String {
        val clean = item.trim()
        if (items.any { it.equals(clean, ignoreCase = true) }) return "$clean is already on your list."
        items.add(clean)
        save()
        return "Added $clean to shopping list. You now have ${items.size} item(s)."
    }

    fun removeItem(item: String): String {
        val removed = items.removeIf { it.equals(item.trim(), ignoreCase = true) }
        save()
        return if (removed) "Removed ${item.trim()} from your shopping list." else "${item.trim()} not found in list."
    }

    fun readList(): String {
        if (items.isEmpty()) return "Your shopping list is empty."
        return "Shopping list: ${items.joinToString(", ")}."
    }

    fun clearList(): String {
        items.clear()
        save()
        return "Shopping list cleared."
    }
}
