package com.igino.remote_camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.min

class DrawingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    data class Stroke(
        val path: Path,
        val color: Int,
        val strokeWidth: Float
    )

    private var photoBitmap: Bitmap? = null
    private val strokes = mutableListOf<Stroke>()

    private var currentPath: Path? = null
    private var currentColor: Int = Color.RED
    private var strokeWidth: Float = 12f

    private val paint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }

    private val photoRect = RectF()
    private val viewRect = RectF()

    fun setPhoto(bitmap: Bitmap) {
        photoBitmap = bitmap
        strokes.clear()
        invalidate()
    }

    fun setStrokeColor(color: Int) {
        currentColor = color
        invalidate()
    }

    fun setStrokeWidth(width: Float) {
        strokeWidth = width
        invalidate()
    }

    fun clearDrawing() {
        strokes.clear()
        currentPath = null
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bitmap = photoBitmap ?: return

        val vw = width.toFloat()
        val vh = height.toFloat()
        if (vw <= 0f || vh <= 0f) return

        val pw = bitmap.width.toFloat()
        val ph = bitmap.height.toFloat()

        val scale = min(vw / pw, vh / ph)
        val drawW = pw * scale
        val drawH = ph * scale
        val left = (vw - drawW) / 2f
        val top = (vh - drawH) / 2f

        photoRect.set(0f, 0f, pw, ph)
        viewRect.set(left, top, left + drawW, top + drawH)

        canvas.drawBitmap(bitmap, null, viewRect, null)

        for (stroke in strokes) {
            paint.color = stroke.color
            paint.strokeWidth = stroke.strokeWidth
            canvas.drawPath(stroke.path, paint)
        }

        currentPath?.let { path ->
            paint.color = currentColor
            paint.strokeWidth = strokeWidth
            canvas.drawPath(path, paint)
        }
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (photoBitmap == null) return false

        val x = event.x
        val y = event.y

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                val path = Path()
                path.moveTo(x, y)
                currentPath = path
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                currentPath?.lineTo(x, y)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                currentPath?.let { path ->
                    path.lineTo(x, y)
                    strokes.add(Stroke(path, currentColor, strokeWidth))
                }
                currentPath = null
                performClick()
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    fun getCombinedBitmap(): Bitmap? {
        val original = photoBitmap ?: return null
        val pw = original.width
        val ph = original.height
        if (pw <= 0 || ph <= 0) return null

        val result = Bitmap.createBitmap(pw, ph, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)

        canvas.drawBitmap(original, 0f, 0f, null)

        val vw = width.toFloat()
        val vh = height.toFloat()
        if (vw <= 0f || vh <= 0f) return result

        val scale = min(vw / pw.toFloat(), vh / ph.toFloat())
        val drawW = pw.toFloat() * scale
        val drawH = ph.toFloat() * scale
        val left = (vw - drawW) / 2f
        val top = (vh - drawH) / 2f

        val matrix = Matrix()
        matrix.postTranslate(-left, -top)
        matrix.postScale(1f / scale, 1f / scale)

        val strokePaint = Paint().apply {
            isAntiAlias = true
            style = Paint.Style.STROKE
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
        }

        for (stroke in strokes) {
            val transformedPath = Path()
            stroke.path.transform(matrix, transformedPath)

            strokePaint.color = stroke.color
            strokePaint.strokeWidth = stroke.strokeWidth / scale

            canvas.drawPath(transformedPath, strokePaint)
        }

        return result
    }
}
