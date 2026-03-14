package com.clukey.os.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.*
import android.widget.*
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import com.clukey.os.CluKeyApp
import com.clukey.os.R
import com.clukey.os.network.CloudSyncService
import com.clukey.os.utils.AppLogger
import kotlinx.coroutines.*

/**
 * OverlayService — floating AI assistant bubble.
 *
 * Uses WindowManager TYPE_APPLICATION_OVERLAY for always-on-top rendering.
 *
 * States:
 *   COLLAPSED — small glowing ⬡ orb in screen corner, draggable
 *   EXPANDED  — full panel with conversation, voice/text input, status
 */
class OverlayService : LifecycleService() {

    companion object {
        const val TAG = "OverlayService"
        const val NOTIF_ID = 1002
    }

    private lateinit var wm: WindowManager
    private var overlayRoot: View? = null
    private var expanded = false
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // Drag state
    private var startX = 0; private var startY = 0
    private var initX  = 0; private var initY  = 0

    override fun onCreate() {
        super.onCreate()
        AppLogger.i(TAG, "OverlayService created")
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        startForeground(NOTIF_ID, buildNotification())
        showOverlay()
    }

    override fun onBind(intent: Intent): IBinder? { super.onBind(intent); return null }

    override fun onDestroy() {
        scope.cancel()
        removeOverlay()
        super.onDestroy()
    }

    // ── Overlay view construction ──────────────────────────────────────────────

    private fun showOverlay() {
        val inflater = LayoutInflater.from(this)
        val root = inflater.inflate(R.layout.overlay_floating, null)
        overlayRoot = root

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            x = 24; y = 80
        }

        setupDrag(root, params)
        setupInteraction(root, params)

        wm.addView(root, params)
        AppLogger.i(TAG, "Overlay added to WindowManager")
    }

    private fun removeOverlay() {
        overlayRoot?.let {
            try { wm.removeView(it) } catch (e: Exception) { /* already removed */ }
            overlayRoot = null
        }
    }

    // ── Drag ──────────────────────────────────────────────────────────────────

    private fun setupDrag(root: View, params: WindowManager.LayoutParams) {
        val orb = root.findViewById<View>(R.id.orbCollapsed)
        orb.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.rawX.toInt(); startY = event.rawY.toInt()
                    initX  = params.x;           initY  = params.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initX + (startX - event.rawX.toInt())
                    params.y = initY + (event.rawY.toInt() - startY)
                    wm.updateViewLayout(root, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val dx = Math.abs(event.rawX.toInt() - startX)
                    val dy = Math.abs(event.rawY.toInt() - startY)
                    if (dx < 10 && dy < 10) toggleExpanded(root, params)
                    true
                }
                else -> false
            }
        }
    }

    // ── Panel interaction ──────────────────────────────────────────────────────

    private fun setupInteraction(root: View, params: WindowManager.LayoutParams) {
        val btnClose  = root.findViewById<View>(R.id.btnClosePanel)
        val btnSend   = root.findViewById<View>(R.id.btnOverlaySend)
        val input     = root.findViewById<EditText>(R.id.inputOverlay)
        val txtResp   = root.findViewById<TextView>(R.id.txtOverlayResponse)

        btnClose.setOnClickListener { collapse(root, params) }

        btnSend.setOnClickListener {
            val msg = input.text.toString().trim()
            if (msg.isBlank()) return@setOnClickListener
            input.setText("")
            txtResp.text = "Thinking…"
            scope.launch {
                val result = CloudSyncService.sendChat(msg, "overlay")
                val resp   = result.getOrNull()?.response ?: "Brain offline."
                txtResp.text = resp
            }
        }
    }

    private fun toggleExpanded(root: View, params: WindowManager.LayoutParams) {
        if (expanded) collapse(root, params) else expand(root, params)
    }

    private fun expand(root: View, params: WindowManager.LayoutParams) {
        expanded = true
        root.findViewById<View>(R.id.orbCollapsed).visibility = View.GONE
        root.findViewById<View>(R.id.panelExpanded).visibility = View.VISIBLE
        params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        wm.updateViewLayout(root, params)
    }

    private fun collapse(root: View, params: WindowManager.LayoutParams) {
        expanded = false
        root.findViewById<View>(R.id.orbCollapsed).visibility = View.VISIBLE
        root.findViewById<View>(R.id.panelExpanded).visibility = View.GONE
        params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        wm.updateViewLayout(root, params)
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CluKeyApp.CHANNEL_OVERLAY)
            .setSmallIcon(R.drawable.ic_clukey_notif)
            .setContentTitle("CluKey Overlay")
            .setContentText("Floating AI assistant active")
            .setOngoing(true)
            .build()
    }
}
