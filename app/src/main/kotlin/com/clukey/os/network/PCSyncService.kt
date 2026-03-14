package com.clukey.os.network

import com.clukey.os.utils.AppLogger
import com.clukey.os.utils.PrefsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * PCSyncService — sends commands from the phone to a CluKey desktop overlay
 * running on a PC on the same network.
 */
object PCSyncService {

    private const val TAG = "PCSyncService"

    private val client = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    private val JSON = "application/json; charset=utf-8".toMediaType()

    private fun pcUrl(): String = PrefsManager.serverUrl

    suspend fun sendToPc(command: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val body = """{"message":"$command","device":"android_pc_sync"}""".toRequestBody(JSON)
            val req = Request.Builder()
                .url("${pcUrl()}/chat")
                .post(body)
                .build()
            val resp = client.newCall(req).execute()
            AppLogger.i(TAG, "PC sync sent: $command → ${resp.code}")
            resp.isSuccessful
        } catch (e: Exception) {
            AppLogger.e(TAG, "PC sync failed", e)
            false
        }
    }

    suspend fun pingPc(): Boolean = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url("${pcUrl()}/ping").get().build()
            client.newCall(req).execute().isSuccessful
        } catch (e: Exception) {
            false
        }
    }
}
