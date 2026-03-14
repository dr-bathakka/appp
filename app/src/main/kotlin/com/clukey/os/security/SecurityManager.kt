package com.clukey.os.security

import com.clukey.os.utils.PrefsManager

/**
 * SecurityManager — validates incoming HTTP requests to HttpBridgeServer.
 * Checks X-CLUKEY-KEY header against the stored API key.
 */
object SecurityManager {

    fun isAuthorized(headers: Map<String, String>): Boolean {
        val storedKey = PrefsManager.apiKey
        // If no API key is configured, allow all local requests
        if (storedKey.isBlank()) return true
        val requestKey = headers["x-clukey-key"] ?: headers["X-CLUKEY-KEY"] ?: ""
        return requestKey == storedKey
    }

    fun unauthorizedResponse(): String =
        """{"status":"error","message":"Unauthorized — set X-CLUKEY-KEY header"}"""
}
