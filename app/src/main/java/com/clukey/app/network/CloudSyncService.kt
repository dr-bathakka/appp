package com.clukey.app.network

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.clukey.app.engine.CommandExecutor
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

class CloudSyncService(private val context: Context) {

    // ⚠️ REPLACE WITH YOUR RAILWAY URL
    private val BASE_URL = "https://your-clukey-app.railway.app"
    private val client = OkHttpClient()
    private val JSON = "application/json; charset=utf-8".toMediaType()
    private val handler = Handler(Looper.getMainLooper())

    // ─── POLLING ─────────────────────────────────────────────────────────
    fun startPolling() {
        val pollRunnable = object : Runnable {
            override fun run() {
                pollCommands()
                handler.postDelayed(this, 3000) // Poll every 3s
            }
        }
        handler.post(pollRunnable)
    }

    private fun pollCommands() {
        val request = Request.Builder().url("$BASE_URL/poll").get().build()
        client.newCall(request).enqueue(object : Callback {
            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string() ?: return
                try {
                    val json = JSONObject(body)
                    val command = json.optString("command", "")
                    val params = json.optJSONObject("params")
                    if (command.isNotEmpty()) {
                        val result = CommandExecutor(context).execute(command, params)
                        pushCommandResult(command, result)
                    }
                } catch (e: Exception) { e.printStackTrace() }
            }
            override fun onFailure(call: Call, e: IOException) {}
        })
    }

    // ─── PUSH DEVICE HEALTH ──────────────────────────────────────────────
    fun pushDeviceHealth(data: JSONObject) {
        post("/device_health", data)
    }

    // ─── PUSH LOCATION ───────────────────────────────────────────────────
    fun pushLocation(data: JSONObject) {
        post("/location", data)
    }

    // ─── PUSH INTRUDER ALERT ─────────────────────────────────────────────
    fun pushIntruderAlert(data: JSONObject) {
        post("/intruder", data)
    }

    // ─── PUSH ALERT ──────────────────────────────────────────────────────
    fun pushAlert(data: JSONObject) {
        post("/alert", data)
    }

    // ─── PUSH COMMAND RESULT ─────────────────────────────────────────────
    fun pushCommandResult(command: String, result: String) {
        val data = JSONObject().apply {
            put("command", command)
            put("result", result)
            put("timestamp", System.currentTimeMillis())
        }
        post("/command_result", data)
    }

    // ─── GET PENDING REPLIES ─────────────────────────────────────────────
    fun getPendingReplies(callback: (JSONArray) -> Unit) {
        val request = Request.Builder().url("$BASE_URL/pending_replies").get().build()
        client.newCall(request).enqueue(object : Callback {
            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string() ?: return
                try {
                    val json = JSONObject(body)
                    callback(json.optJSONArray("replies") ?: JSONArray())
                } catch (e: Exception) { e.printStackTrace() }
            }
            override fun onFailure(call: Call, e: IOException) {}
        })
    }

    // ─── GET AUTOMATION RULES ────────────────────────────────────────────
    fun getRules(callback: (JSONArray) -> Unit) {
        val request = Request.Builder().url("$BASE_URL/rules").get().build()
        client.newCall(request).enqueue(object : Callback {
            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string() ?: return
                try {
                    val json = JSONObject(body)
                    callback(json.optJSONArray("rules") ?: JSONArray())
                } catch (e: Exception) { e.printStackTrace() }
            }
            override fun onFailure(call: Call, e: IOException) {}
        })
    }

    // ─── PUSH MESSAGES (for summarizer) ──────────────────────────────────
    fun pushMessages(messages: JSONArray) {
        val data = JSONObject().apply {
            put("messages", messages)
            put("timestamp", System.currentTimeMillis())
        }
        post("/messages", data)
    }

    // ─── INTERNAL POST ───────────────────────────────────────────────────
    private fun post(endpoint: String, data: JSONObject) {
        val body = data.toString().toRequestBody(JSON)
        val request = Request.Builder()
            .url("$BASE_URL$endpoint")
            .post(body)
            .build()
        client.newCall(request).enqueue(object : Callback {
            override fun onResponse(call: Call, response: Response) {}
            override fun onFailure(call: Call, e: IOException) {}
        })
    }
}
