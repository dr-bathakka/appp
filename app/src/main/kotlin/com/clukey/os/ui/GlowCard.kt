package com.clukey.os.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.widget.FrameLayout
import androidx.core.content.ContextCompat

/**
 * GlowCard — FrameLayout with neon glow border for the Jarvis HUD look.
 * Draws a rounded rect border that pulses with a neon-cyan glow.
 */
class GlowCard @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : FrameLayout(context, attrs, defStyle) {

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = 0xFF00C8FF.toInt()
        // Glow via shadow layer
        setShadowLayer(12f, 0f, 0f, 0xFF00C8FF.toInt())
    }

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFF0D1117.toInt()
    }

    private val radius = 12f

    init {
        setWillNotDraw(false)
        setLayerType(LAYER_TYPE_SOFTWARE, null)  // required for shadow layer
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawRoundRect(2f, 2f, w - 2f, h - 2f, radius, radius, bgPaint)
        canvas.drawRoundRect(2f, 2f, w - 2f, h - 2f, radius, radius, borderPaint)
        super.onDraw(canvas)
    }
}
