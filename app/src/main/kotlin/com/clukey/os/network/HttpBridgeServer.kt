package com.clukey.os.network

import com.clukey.os.engine.CommandExecutor
import com.clukey.os.security.SecurityManager
import com.clukey.os.utils.AppLogger
import com.google.gson.Gson
import com.google.gson.JsonObject
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.runBlocking

/**
 * HttpBridgeServer — NanoHTTPD local HTTP server (port 8080).
 * The CluKey Brain cloud server calls this to send commands to the phone.
 *
 * Endpoints (all POST unless noted):
 *   GET  /ping
 *   GET  /status
 *   POST /open_app        {"app":"youtube"}
 *   POST /lock_phone
 *   POST /toggle_wifi
 *   POST /toggle_bluetooth
 *   POST /toggle_hotspot
 *   POST /toggle_data     {"enable":true}
 *   POST /silent_mode
 *   POST /set_volume      {"level":50}
 *   POST /set_brightness  {"value":80}
 *   POST /screenshot
 *   POST /make_call       {"number":"1234567890"}
 *   POST /send_sms        {"to":"1234567890","text":"hello"}
 *   POST /launch_camera
 *   POST /flashlight_on
 *   POST /flashlight_off
 *   POST /find_phone
 *   POST /chat            {"message":"open youtube"}
 */
class HttpBridgeServer(
    private val port: Int,
    private val executor: CommandExecutor
) : NanoHTTPD(port) {

    private val TAG  = "HttpBridgeServer"
    private val gson = Gson()

    override fun serve(session: IHTTPSession): Response {
        val uri     = session.uri
        val method  = session.method
        val headers = session.headers

        AppLogger.i(TAG, "[HTTP] $method $uri")

        if (!SecurityManager.isAuthorized(headers)) {
            return newFixedLengthResponse(
                Response.Status.UNAUTHORIZED, MIME_JSON,
                SecurityManager.unauthorizedResponse()
            )
        }

        val body = parseBody(session)

        return when {
            uri == "/ping"             -> ok("pong", "ping")
            uri == "/status"           -> ok("online", "status")

            // ── App launcher ──────────────────────────────────────────────────
            uri == "/open_app"         -> {
                val app = body.get("app")?.asString ?: ""
                execute("open $app")
            }

            // ── Phone control ─────────────────────────────────────────────────
            uri == "/lock_phone"       -> execute("lock phone")
            uri == "/toggle_wifi"      -> execute("toggle wifi")
            uri == "/toggle_bluetooth" -> execute("toggle bluetooth")
            uri == "/toggle_hotspot"   -> execute("toggle hotspot")
            uri == "/toggle_data"      -> {
                val enable = body.get("enable")?.asBoolean ?: true
                execute(if (enable) "enable data" else "disable data")
            }
            uri == "/silent_mode"      -> execute("silent mode")
            uri == "/set_volume"       -> {
                val level = body.get("level")?.asInt ?: 50
                execute("volume $level")
            }
            uri == "/set_brightness"   -> {
                val v = body.get("value")?.asInt ?: 50
                execute("brightness $v")
            }

            // ── Camera / screenshot ───────────────────────────────────────────
            uri == "/screenshot"       -> execute("screenshot")
            uri == "/launch_camera"    -> execute("launch camera")
            uri == "/flashlight_on"    -> execute("flashlight on")
            uri == "/flashlight_off"   -> execute("flashlight off")

            // ── Phone / SMS ───────────────────────────────────────────────────
            uri == "/make_call"        -> {
                val number = body.get("number")?.asString ?: ""
                execute("call $number")
            }
            uri == "/send_sms"         -> {
                val to   = body.get("to")?.asString ?: ""
                val text = body.get("text")?.asString ?: ""
                execute("sms $to $text")
            }

            // ── Find phone ────────────────────────────────────────────────────
            uri == "/find_phone"       -> execute("ring phone")

            // ── Chat passthrough ──────────────────────────────────────────────
            uri == "/chat"             -> {
                val msg = body.get("message")?.asString
                    ?: body.get("command")?.asString
                    ?: return error("no message")
                execute(msg)
            }

            else -> notFound()
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun execute(command: String): Response {
        return try {
            val result = runBlocking { executor.execute(command, "http") }
            val json = JsonObject().apply {
                addProperty("status",   if (result.success) "ok" else "error")
                addProperty("response", result.response)
                addProperty("action",   result.action)
            }
            newFixedLengthResponse(Response.Status.OK, MIME_JSON, json.toString())
        } catch (e: Exception) {
            AppLogger.e(TAG, "execute error", e)
            error(e.message ?: "Internal error")
        }
    }

    private fun ok(message: String, action: String): Response {
        val json = JsonObject().apply {
            addProperty("status",  "ok")
            addProperty("message", message)
            addProperty("action",  action)
        }
        return newFixedLengthResponse(Response.Status.OK, MIME_JSON, json.toString())
    }

    private fun error(reason: String): Response {
        val json = JsonObject().apply {
            addProperty("status", "error")
            addProperty("reason", reason)
        }
        return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_JSON, json.toString())
    }

    private fun notFound(): Response {
        return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_JSON,
            """{"status":"error","reason":"endpoint not found"}""")
    }

    private fun parseBody(session: IHTTPSession): JsonObject {
        return try {
            val files = HashMap<String, String>()
            session.parseBody(files)
            val bodyStr = files["postData"] ?: return JsonObject()
            gson.fromJson(bodyStr, JsonObject::class.java) ?: JsonObject()
        } catch (e: Exception) {
            JsonObject()
        }
    }

    companion object {
        const val MIME_JSON = "application/json"
    }
}
