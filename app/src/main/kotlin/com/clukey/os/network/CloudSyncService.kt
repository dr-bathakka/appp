package com.clukey.os.network

import android.content.Context
import android.util.Log
import com.clukey.os.utils.PrefsManager
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

object CloudSyncService {

    private const val TAG = "CloudSyncService"
    private val JSON = "application/json; charset=utf-8".toMediaType()

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val _connected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _connected

    fun init(ctx: Context) {}

    private val baseUrl get() = PrefsManager.serverUrl.trimEnd('/')
    private val apiKey  get() = PrefsManager.apiKey

    private fun headers() = Headers.Builder()
        .add("Content-Type", "application/json")
        .add("X-Device-Id", PrefsManager.deviceId)
        .apply { if (apiKey.isNotBlank()) add("X-CLUKEY-KEY", apiKey) }
        .build()

    private fun j(vararg pairs: Pair<String, Any?>): String {
        val sb = StringBuilder("{")
        pairs.forEachIndexed { i, (k, v) ->
            if (i > 0) sb.append(",")
            sb.append("\"$k\":")
            when (v) {
                null       -> sb.append("null")
                is String  -> sb.append("\"${v.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n")}\"")
                is Boolean -> sb.append(v)
                is Number  -> sb.append(v)
                else       -> sb.append("\"$v\"")
            }
        }
        sb.append("}")
        return sb.toString()
    }

    private suspend fun post(path: String, body: String = "{}"): JsonObject =
        withContext(Dispatchers.IO) {
            try {
                val req = Request.Builder()
                    .url("$baseUrl$path").headers(headers())
                    .post(body.toRequestBody(JSON)).build()
                val resp = client.newCall(req).execute()
                _connected.value = resp.isSuccessful
                parseJson(resp.body?.string() ?: "{}")
            } catch (e: Exception) {
                Log.e(TAG, "POST $path: ${e.message}")
                _connected.value = false
                JsonObject()
            }
        }

    private suspend fun get(path: String, params: Map<String, String> = emptyMap()): JsonObject =
        withContext(Dispatchers.IO) {
            try {
                val queryStr = if (params.isEmpty()) "" else "?" + params.entries.joinToString("&") {
                    "${java.net.URLEncoder.encode(it.key,"UTF-8")}=${java.net.URLEncoder.encode(it.value,"UTF-8")}"
                }
                val req = Request.Builder().url("$baseUrl$path$queryStr").headers(headers()).get().build()
                val resp = client.newCall(req).execute()
                _connected.value = resp.isSuccessful
                parseJson(resp.body?.string() ?: "{}")
            } catch (e: Exception) {
                Log.e(TAG, "GET $path: ${e.message}")
                _connected.value = false
                JsonObject()
            }
        }

    private fun parseJson(text: String): JsonObject = try {
        JsonParser.parseString(text).asJsonObject
    } catch (e: Exception) { JsonObject() }

    private fun JsonObject.s(k: String, d: String = "") = try { get(k)?.takeIf { !it.isJsonNull }?.asString ?: d } catch (e: Exception) { d }
    private fun JsonObject.b(k: String, d: Boolean = false) = try { get(k)?.takeIf { !it.isJsonNull }?.asBoolean ?: d } catch (e: Exception) { d }
    private fun JsonObject.i(k: String, d: Int = 0) = try { get(k)?.takeIf { !it.isJsonNull }?.asInt ?: d } catch (e: Exception) { d }

    // ── Data classes ──────────────────────────────────────────────────────────
    data class ChatResponse(val status: String, val response: String, val agent: String, val action: String)
    data class StatusResponse(
        val status: String, val mode: String, val memoryTurns: Int,
        val phoneConnected: Boolean, val pcConnected: Boolean, val unreadMessages: Int,
        val aiProvider: String, val groqReady: Boolean, val geminiReady: Boolean, val time: String
    )
    data class SyncMessage(
        val id: String, val app: String, val account: String,
        val sender: String, val text: String, val time: String,
        val threadId: String, val read: Boolean
    )
    data class FileEntry(val name: String, val sizeStr: String, val modified: String, val mime: String)

    // ── 1. AI Chat ────────────────────────────────────────────────────────────
    suspend fun sendChat(message: String, device: String = "android"): Result<ChatResponse> = runCatching {
        val r = post("/chat", j("message" to message, "device" to device))
        ChatResponse(r.s("status","ok"), r.s("response","..."), r.s("agent","ai"), r.s("action","none"))
    }

    // ── 2. Status ─────────────────────────────────────────────────────────────
    suspend fun getStatus(): Result<StatusResponse> = runCatching {
        val r = get("/status")
        StatusResponse(
            r.s("status","unknown"), r.s("mode","normal"), r.i("memory_turns"),
            r.b("phone_connected"), r.b("pc_connected"), r.i("unread_messages"),
            r.s("ai_provider","groq"), r.b("groq_ready"), r.b("gemini_ready"), r.s("time","--:--")
        )
    }

    suspend fun ping(): Long { val t0 = System.currentTimeMillis(); get("/ping"); return System.currentTimeMillis() - t0 }

    /** Convenience: heartbeat push (used by AssistantService periodic push). */
    suspend fun pushDeviceInfo() {
        try {
            post("/devices/heartbeat",
                "{\"name\":\"CluKey Android\",\"type\":\"android\",\"device_id\":\"${PrefsManager.deviceId}\"}")
        } catch (_: Exception) {}
    }

    // ── 3. Device Registration ────────────────────────────────────────────────
    suspend fun registerDevice(name: String = "CluKey Android"): Result<String> = runCatching {
        val r = post("/register_device", j("device_name" to name, "device_type" to "android"))
        val id = r.s("device_id","")
        if (id.isNotBlank()) { PrefsManager.deviceId = id; PrefsManager.isRegistered = true }
        id
    }

    // ── 4. Message Sync ───────────────────────────────────────────────────────
    suspend fun pushMessage(
        account: String, app: String, sender: String, text: String,
        timestamp: String = "", threadId: String = ""
    ): Result<Boolean> = runCatching {
        post("/messages/push", j(
            "account" to account, "app" to app, "sender" to sender,
            "text" to text, "timestamp" to timestamp, "thread_id" to threadId
        )).s("status") == "ok"
    }

    suspend fun getMessages(
        account: String? = null, app: String? = null,
        unreadOnly: Boolean = false, limit: Int = 50
    ): Result<List<SyncMessage>> = runCatching {
        val params = mutableMapOf("limit" to "$limit")
        account?.let { params["account"] = it }
        app?.let { params["app"] = it }
        if (unreadOnly) params["unread"] = "true"
        val arr = runCatching { get("/messages", params).getAsJsonArray("messages") }.getOrNull()
        val list = mutableListOf<SyncMessage>()
        arr?.forEach { el -> runCatching {
            val m = el.asJsonObject
            list.add(SyncMessage(m.s("id"), m.s("app"), m.s("account"), m.s("sender"), m.s("text"), m.s("time"), m.s("thread_id"), m.b("read")))
        }}
        list
    }

    suspend fun markRead(account: String, app: String, msgId: String? = null): Result<Int> = runCatching {
        val body = buildString {
            append("{\"account\":\"$account\",\"app\":\"$app\"")
            msgId?.let { append(",\"msg_id\":\"$it\"") }
            append("}")
        }
        post("/messages/read", body).i("marked_read", 0)
    }

    suspend fun summarizeMessages(appFilter: String? = null, hours: Int = 24): String = runCatching {
        val body = buildString {
            append("{\"hours\":$hours")
            appFilter?.let { append(",\"app\":\"$it\"") }
            append("}")
        }
        post("/messages/summarize", body).s("summary", "No summary available.")
    }.getOrDefault("Summary failed.")

    // ── 5. File Transfer ──────────────────────────────────────────────────────
    suspend fun listFiles(): Result<List<FileEntry>> = runCatching {
        val arr = runCatching { get("/files").getAsJsonArray("files") }.getOrNull()
        val list = mutableListOf<FileEntry>()
        arr?.forEach { el -> runCatching {
            val f = el.asJsonObject
            list.add(FileEntry(f.s("name"), f.s("size_str"), f.s("modified"), f.s("mime")))
        }}
        list
    }

    suspend fun uploadFile(file: File): Result<String> = runCatching {
        val mime = when (file.extension.lowercase()) {
            "jpg","jpeg" -> "image/jpeg"; "png" -> "image/png"; "pdf" -> "application/pdf"
            "mp4" -> "video/mp4"; "txt" -> "text/plain"; else -> "application/octet-stream"
        }
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", file.name, file.asRequestBody(mime.toMediaType())).build()
        val req = Request.Builder().url("$baseUrl/files/upload").headers(headers()).post(body).build()
        val resp = client.newCall(req).execute()
        val r = parseJson(resp.body?.string() ?: "{}")
        if (r.s("status") == "ok") r.s("filename", file.name) else throw Exception(r.s("message","Upload failed"))
    }

    suspend fun downloadFile(filename: String, destDir: File): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder().url("$baseUrl/files/download/$filename").headers(headers()).get().build()
            val resp = client.newCall(req).execute()
            if (!resp.isSuccessful) throw Exception("HTTP ${resp.code}")
            val dest = File(destDir, filename)
            resp.body?.byteStream()?.use { inp -> dest.outputStream().use { inp.copyTo(it) } }
            dest
        }
    }

    // ── 6. Phone Control (server → phone) ────────────────────────────────────
    private suspend fun ctrl(path: String, vararg pairs: Pair<String, Any?>): String =
        post(path, if (pairs.isEmpty()) "{}" else j(*pairs)).s("message", "OK")

    suspend fun lockPhone()                        = ctrl("/lock_phone")
    suspend fun toggleWifi()                       = ctrl("/toggle_wifi")
    suspend fun toggleBluetooth()                  = ctrl("/toggle_bluetooth")
    suspend fun toggleHotspot()                    = ctrl("/toggle_hotspot")
    suspend fun silentMode()                       = ctrl("/silent_mode")
    suspend fun takeScreenshot()                   = ctrl("/screenshot")
    suspend fun setVolume(v: Int)                  = ctrl("/set_volume",     "level"  to v)
    suspend fun setBrightness(v: Int)              = ctrl("/set_brightness", "value"  to v)
    suspend fun openApp(app: String)               = ctrl("/open_app",       "app"    to app)
    suspend fun sendSms(to: String, txt: String)   = ctrl("/send_sms",       "to"     to to, "text" to txt)
    suspend fun makeCall(number: String)           = ctrl("/make_call",       "number" to number)
    suspend fun toggleData(enable: Boolean)        = ctrl("/phone/toggle_data", "enable" to enable)

    // ── 7. PC Control (server → PC agent) ────────────────────────────────────
    suspend fun pcRunCmd(cmd: String)             = post("/pc/cmd",        j("cmd"   to cmd)).s("output", "Done.")
    suspend fun pcScreenshot()                    = post("/pc/screenshot").s("message", "Done.")
    suspend fun pcLock()                          = post("/pc/lock").s("message", "Done.")
    suspend fun pcShutdown()                      = post("/pc/shutdown").s("message", "Done.")
    suspend fun pcReboot()                        = post("/pc/shutdown", j("action" to "reboot")).s("message", "Done.")
    suspend fun pcOpenApp(app: String)            = post("/pc/open_app",   j("app"   to app)).s("message", "Done.")
    suspend fun pcOpenUrl(url: String)            = post("/pc/open_url",   j("url"   to url)).s("message", "Done.")
    suspend fun pcGetClipboard()                  = post("/pc/clipboard").s("text", "")
    suspend fun pcSetClipboard(txt: String)       = post("/pc/clipboard",  j("text"  to txt)).s("message", "Done.")
    suspend fun pcVolume(v: Int)                  = post("/pc/volume",     j("level" to v)).s("message", "Done.")
    suspend fun pcTypeText(text: String)          = post("/pc/type",       j("text"  to text)).s("message", "Done.")
    suspend fun pcKeyShortcut(keys: String)       = post("/pc/keyboard",   j("keys"  to keys)).s("message", "Done.")
    suspend fun pcListProcesses()                 = post("/pc/processes").s("output", "")
    suspend fun pcKillProcess(name: String)       = post("/pc/kill",       j("name"  to name)).s("message", "Done.")
    suspend fun pcListFiles(path: String = "~")   = post("/pc/files",      j("path"  to path)).s("output", "")

    // ── 8. Settings ───────────────────────────────────────────────────────────
    suspend fun updateSettings(phoneIp: String = "", pcIp: String = "", mode: String = ""): Boolean {
        val pairs = mutableListOf<Pair<String, Any?>>()
        if (phoneIp.isNotBlank()) pairs += "phone_ip" to phoneIp
        if (pcIp.isNotBlank())    pairs += "pc_ip"    to pcIp
        if (mode.isNotBlank())    pairs += "mode"      to mode
        if (pairs.isEmpty()) return false
        return post("/settings", j(*pairs.toTypedArray())).s("status") == "ok"
    }

    suspend fun getData()              = get("/data")
    suspend fun getLogs(limit: Int=50) = get("/logs", mapOf("limit" to "$limit"))
    suspend fun clearHistory()         = post("/clear_history").s("status","ok") == "ok"

    // ── 9. Device Health ──────────────────────────────────────────────────────
    suspend fun pushDeviceHealth(battery: Int, charging: Boolean, ramUsedMb: Long, ramTotalMb: Long): Boolean = runCatching {
        post("/device/health", j(
            "battery" to battery, "charging" to charging,
            "ram_used_mb" to ramUsedMb, "ram_total_mb" to ramTotalMb,
            "timestamp" to System.currentTimeMillis()
        )).s("status") == "ok"
    }.getOrDefault(false)

    suspend fun getDeviceHealth()      = get("/device/health")

    // ── 10. Location ──────────────────────────────────────────────────────────
    suspend fun pushLocation(lat: Double, lng: Double, accuracy: Float, speed: Float = 0f): Boolean = runCatching {
        post("/device/location", j(
            "lat" to lat, "lng" to lng,
            "accuracy" to accuracy, "speed" to speed,
            "timestamp" to System.currentTimeMillis()
        )).s("status") == "ok"
    }.getOrDefault(false)

    suspend fun getLocation()          = get("/device/location")

    // ── 11. Alerts ────────────────────────────────────────────────────────────
    suspend fun getAlerts()                       = get("/alerts")
    suspend fun dismissAlert(id: String)          = post("/alerts/dismiss", j("alert_id" to id))
    suspend fun getProactiveInsights()            = get("/alerts/proactive")

    // ── 12. Smart Reply ───────────────────────────────────────────────────────
    suspend fun sendReply(app: String, to: String, text: String, threadId: String = ""): Boolean = runCatching {
        post("/reply/send", j("app" to app, "to" to to, "text" to text, "thread_id" to threadId)).s("status") == "ok"
    }.getOrDefault(false)

    suspend fun getPendingReplies(): List<JsonObject> = runCatching {
        val arr = get("/reply/pending").getAsJsonArray("replies") ?: return@runCatching emptyList()
        arr.map { it.asJsonObject }
    }.getOrDefault(emptyList())

    suspend fun markReplySent(replyId: String) = post("/reply/sent", j("reply_id" to replyId))

    // ── 13. Panic ─────────────────────────────────────────────────────────────
    suspend fun triggerPanic(reason: String = "manual"): Boolean = runCatching {
        post("/panic", j("reason" to reason)).s("status") == "panic_activated"
    }.getOrDefault(false)

    suspend fun addPanicContact(name: String, number: String) =
        post("/panic/contacts", j("name" to name, "number" to number))

    suspend fun getPanicContacts() = get("/panic/contacts")

    // ── 14. Automation Rules ──────────────────────────────────────────────────
    suspend fun getRules()             = get("/rules")
    suspend fun deleteRule(id: String) = post("/rules/delete", j("id" to id))
    suspend fun tickRules()            = post("/rules/tick", j())

    suspend fun addRule(triggerType: String, triggerValue: String, action: String, name: String = ""): Boolean = runCatching {
        post("/rules/add", j(
            "trigger_type" to triggerType, "trigger_value" to triggerValue,
            "action" to action, "name" to name
        )).s("status") == "ok"
    }.getOrDefault(false)

    // ── 15. Clipboard Sync ────────────────────────────────────────────────────
    suspend fun pushClipboard(text: String): Boolean = runCatching {
        post("/clipboard", j("text" to text, "source" to "phone")).s("status") == "ok"
    }.getOrDefault(false)

    suspend fun getClipboard()         = get("/clipboard")

    // ── 16. Intruder Alert ────────────────────────────────────────────────────
    suspend fun pushIntruderSelfie(imageBase64: String): Boolean = runCatching {
        post("/intruder", j(
            "type" to "intruder_selfie", "image" to imageBase64,
            "timestamp" to System.currentTimeMillis()
        )).s("status") == "ok"
    }.getOrDefault(false)

    // ── 17. SMS & Call Log ────────────────────────────────────────────────────
    suspend fun pushSmsHistory(messages: List<Map<String,String>>): Boolean = runCatching {
        val arr = buildString {
            append("{\"messages\":[")
            messages.forEachIndexed { i, m ->
                if (i > 0) append(",")
                append("{\"from\":\"${m["from"]}\",\"body\":\"${m["body"]?.replace("\"","\\\"")}\",\"date\":\"${m["date"]}\"}")
            }
            append("]}")
        }
        post("/sms_history", arr).s("status") == "ok"
    }.getOrDefault(false)

    suspend fun pushCallLog(calls: List<Map<String,String>>): Boolean = runCatching {
        val arr = buildString {
            append("{\"calls\":[")
            calls.forEachIndexed { i, c ->
                if (i > 0) append(",")
                append("{\"number\":\"${c["number"]}\",\"type\":\"${c["type"]}\",\"duration\":\"${c["duration"]}\",\"date\":\"${c["date"]}\"}")
            }
            append("]}")
        }
        post("/call_log", arr).s("status") == "ok"
    }.getOrDefault(false)

    // ── 18. App Usage Stats ───────────────────────────────────────────────────
    suspend fun pushAppUsage(stats: List<Map<String,Any>>): Boolean = runCatching {
        val arr = buildString {
            append("{\"stats\":[")
            stats.forEachIndexed { i, s ->
                if (i > 0) append(",")
                append("{\"package\":\"${s["package"]}\",\"name\":\"${s["name"]}\",\"minutes\":${s["minutes"]}}")
            }
            append("]}")
        }
        post("/app_usage", arr).s("status") == "ok"
    }.getOrDefault(false)

    // ── 19. Contacts ──────────────────────────────────────────────────────────
    suspend fun pushContacts(contacts: List<Map<String,String>>): Boolean = runCatching {
        val arr = buildString {
            append("{\"contacts\":[")
            contacts.forEachIndexed { i, c ->
                if (i > 0) append(",")
                append("{\"name\":\"${c["name"]}\",\"number\":\"${c["number"]}\"}")
            }
            append("]}")
        }
        post("/contacts", arr).s("status") == "ok"
    }.getOrDefault(false)

    suspend fun searchContacts(query: String) = get("/contacts/search", mapOf("q" to query))

    // ── 20. Geofence ─────────────────────────────────────────────────────────
    suspend fun addGeofence(name: String, lat: Double, lng: Double, radiusM: Float): Boolean = runCatching {
        post("/geofence/add", j("name" to name, "lat" to lat, "lng" to lng, "radius" to radiusM)).s("status") == "ok"
    }.getOrDefault(false)

    suspend fun checkGeofence(lat: Double, lng: Double) = post("/geofence/check", j("lat" to lat, "lng" to lng))

    // ── 21. AI Briefs ─────────────────────────────────────────────────────────
    suspend fun getMorningBrief(): String = runCatching { post("/ai/morning_brief").s("brief","Good morning!") }.getOrDefault("Could not get brief.")
    suspend fun getNightSummary(): String = runCatching { post("/ai/night_summary").s("summary","Good night!") }.getOrDefault("Could not get summary.")

    // ── 22. Screen Push ───────────────────────────────────────────────────────
    suspend fun pushScreenshot(base64Image: String, source: String = "phone"): Boolean = runCatching {
        post("/screen/push", j("image" to base64Image, "source" to source, "ts" to System.currentTimeMillis())).s("status") == "ok"
    }.getOrDefault(false)

    suspend fun getLatestScreenshot()  = get("/screen/latest")

    // ── 23. Security ──────────────────────────────────────────────────────────
    suspend fun pushSimAlert(oldSim: String, newSim: String): Boolean = runCatching {
        post("/security/sim_change", j("old_sim" to oldSim, "new_sim" to newSim, "ts" to System.currentTimeMillis())).s("status") == "ok"
    }.getOrDefault(false)

    suspend fun checkRemoteWipe(): Boolean = runCatching { get("/security/wipe_pending").b("wipe_requested", false) }.getOrDefault(false)

    // ── 24. Voice Events ──────────────────────────────────────────────────────
    suspend fun pushWakeWordEvent(phrase: String): Boolean = runCatching {
        post("/voice/wake_word", j("phrase" to phrase, "ts" to System.currentTimeMillis())).s("status") == "ok"
    }.getOrDefault(false)

    // ── 25. Weather ───────────────────────────────────────────────────────────
    suspend fun getWeather(lat: Double, lng: Double): String = runCatching {
        post("/weather", j("lat" to lat, "lng" to lng)).s("summary","Weather unavailable.")
    }.getOrDefault("Weather unavailable.")

    // ── 26. Media Control ─────────────────────────────────────────────────────
    suspend fun pushMediaInfo(app: String, track: String, artist: String, playing: Boolean): Boolean = runCatching {
        post("/media/now_playing", j("app" to app, "track" to track, "artist" to artist, "playing" to playing)).s("status") == "ok"
    }.getOrDefault(false)

    suspend fun getMediaCommand()      = get("/media/command")

    // ── 27. WiFi Networks ─────────────────────────────────────────────────────
    suspend fun pushWifiNetworks(networks: List<Map<String,Any>>): Boolean = runCatching {
        val arr = buildString {
            append("{\"networks\":[")
            networks.forEachIndexed { i, n ->
                if (i > 0) append(",")
                append("{\"ssid\":\"${n["ssid"]}\",\"signal\":${n["signal"]},\"secured\":${n["secured"]}}")
            }
            append("]}")
        }
        post("/wifi/networks", arr).s("status") == "ok"
    }.getOrDefault(false)

    // ── 28. Battery Health ────────────────────────────────────────────────────
    suspend fun pushBatteryHealth(health: Int, temperature: Float, voltage: Int, technology: String): Boolean = runCatching {
        post("/device/battery_health", j(
            "health" to health, "temperature" to temperature,
            "voltage" to voltage, "technology" to technology
        )).s("status") == "ok"
    }.getOrDefault(false)

    // ── 29. Macros ────────────────────────────────────────────────────────────
    suspend fun saveMacro(name: String, commands: List<String>): Boolean = runCatching {
        val cmds = commands.joinToString("\",\"", prefix="[\"", postfix="\"]")
        post("/macros/save", "{\"name\":\"$name\",\"commands\":$cmds}").s("status") == "ok"
    }.getOrDefault(false)

    suspend fun getMacros()                = get("/macros")
    suspend fun runMacro(name: String)     = post("/macros/run", j("name" to name))

    // ── 30. Audit Log ─────────────────────────────────────────────────────────
    suspend fun getAuditLog(limit: Int = 100) = get("/audit", mapOf("limit" to "$limit"))

    // ── 31. Recurring Reminders ───────────────────────────────────────────────
    suspend fun addRecurringReminder(message: String, cronExpr: String): Boolean = runCatching {
        post("/reminders/recurring/add", j("message" to message, "cron" to cronExpr)).s("status") == "ok"
    }.getOrDefault(false)

    // ── 32. Export ────────────────────────────────────────────────────────────
    suspend fun requestExport(dataType: String): String = runCatching {
        post("/export", j("type" to dataType)).s("url","")
    }.getOrDefault("")
}
