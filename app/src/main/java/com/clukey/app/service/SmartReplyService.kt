package com.clukey.app.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.clukey.app.network.CloudSyncService
import org.json.JSONObject

class SmartReplyService : AccessibilityService() {

    override fun onServiceConnected() {
        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                    AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            packageNames = arrayOf(
                "com.whatsapp",
                "com.facebook.orca", // Messenger
                "org.telegram.messenger",
                "com.google.android.apps.messaging"
            )
            notificationTimeout = 100
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Monitor for pending replies from PC
        checkAndSendPendingReply()
    }

    fun sendReply(packageName: String, replyText: String): Boolean {
        return try {
            val rootNode = rootInActiveWindow ?: return false

            // Find the message input field
            val inputNode = findInputField(rootNode) ?: return false

            // Set the text
            val args = Bundle()
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, replyText)
            inputNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)

            // Find and click send button
            val sendButton = findSendButton(rootNode)
            sendButton?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false

        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    private fun findInputField(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findInputField(child)
            if (found != null) return found
        }
        return null
    }

    private fun findSendButton(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val sendKeywords = listOf("send", "Send", "SEND")
        if (node.contentDescription?.toString()?.let { desc ->
                sendKeywords.any { desc.contains(it) }
            } == true) return node

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findSendButton(child)
            if (found != null) return found
        }
        return null
    }

    private fun checkAndSendPendingReply() {
        CloudSyncService(this).getPendingReplies { replies ->
            replies.forEach { reply ->
                val pkg = reply.optString("package", "com.whatsapp")
                val text = reply.optString("text", "")
                if (text.isNotEmpty()) {
                    sendReply(pkg, text)
                }
            }
        }
    }

    override fun onInterrupt() {}
}
