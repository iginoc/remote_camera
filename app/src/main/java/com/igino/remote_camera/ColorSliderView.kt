package com.igino.remote_camera

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

class ColorSliderView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#555555")
        strokeWidth = 2f
    }
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thumbBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeWidth = 3f
    }
    private val thumbShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.BLACK
        strokeWidth = 5f
    }

    private val barRect = RectF()
    private var colors: IntArray = intArrayOf(
        Color.RED, Color.YELLOW, Color.GREEN, Color.CYAN, Color.BLUE, Color.MAGENTA, Color.RED
    )

    var progress: Float = 0f
        set(value) {
            val clamped = value.coerceIn(0f, 1f)
            if (field != clamped) {
                field = clamped
                invalidate()
            }
        }

    var onProgressChanged: ((progress: Float, fromUser: Boolean) -> Unit)? = null
    var onTrackingStopped: ((progress: Float) -> Unit)? = null

    fun setGradientColors(vararg newColors: Int) {
        if (newColors.size >= 2) {
            colors = newColors
            updateShader()
            invalidate()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val density = resources.displayMetrics.density
        strokePaint.strokeWidth = 1.5f * density
        thumbBorderPaint.strokeWidth = 3f * density
        thumbShadowPaint.strokeWidth = 5f * density

        val paddingV = 5f * density
        val paddingH = 12f * density
        barRect.set(paddingH, paddingV, w.toFloat() - paddingH, h.toFloat() - paddingV)
        updateShader()
    }

    private fun updateShader() {
        if (barRect.width() > 0) {
            barPaint.shader = LinearGradient(
                barRect.left, 0f, barRect.right, 0f,
                colors, null, Shader.TileMode.CLAMP
            )
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (barRect.width() <= 0) return

        val density = resources.displayMetrics.density
        val cornerRadius = 8f * density

        // Draw gradient bar
        canvas.drawRoundRect(barRect, cornerRadius, cornerRadius, barPaint)
        canvas.drawRoundRect(barRect, cornerRadius, cornerRadius, strokePaint)

        // Draw thumb indicator
        val thumbX = barRect.left + progress * barRect.width()
        val thumbY = barRect.centerY()
        val thumbRadius = (height / 2f) - (2f * density)

        val currentColor = evaluateColor(progress)
        thumbPaint.color = currentColor

        canvas.drawCircle(thumbX, thumbY, thumbRadius, thumbShadowPaint)
        canvas.drawCircle(thumbX, thumbY, thumbRadius, thumbBorderPaint)
        canvas.drawCircle(thumbX, thumbY, (thumbRadius - 3f * density).coerceAtLeast(2f), thumbPaint)
    }

    private fun evaluateColor(fraction: Float): Int {
        if (colors.isEmpty()) return Color.BLACK
        if (colors.size == 1) return colors[0]
        if (fraction <= 0f) return colors[0]
        if (fraction >= 1f) return colors.last()

        val segment = fraction * (colors.size - 1)
        val index = segment.toInt().coerceIn(0, colors.size - 2)
        val subFraction = segment - index

        val c1 = colors[index]
        val c2 = colors[index + 1]

        val a = (Color.alpha(c1) + subFraction * (Color.alpha(c2) - Color.alpha(c1))).toInt()
        val r = (Color.red(c1) + subFraction * (Color.red(c2) - Color.red(c1))).toInt()
        val g = (Color.green(c1) + subFraction * (Color.green(c2) - Color.green(c1))).toInt()
        val b = (Color.blue(c1) + subFraction * (Color.blue(c2) - Color.blue(c1))).toInt()

        return Color.argb(a, r, g, b)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                updateFromTouch(event.x)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                updateFromTouch(event.x)
                return true
            }
            MotionEvent.ACTION_UP -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                updateFromTouch(event.x)
                onTrackingStopped?.invoke(progress)
                performClick()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                onTrackingStopped?.invoke(progress)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun updateFromTouch(x: Float) {
        if (barRect.width() <= 0) return
        val newProgress = ((x - barRect.left) / barRect.width()).coerceIn(0f, 1f)
        progress = newProgress
        onProgressChanged?.invoke(newProgress, true)
    }
}
