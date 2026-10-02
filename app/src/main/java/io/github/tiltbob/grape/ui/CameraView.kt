package io.github.tiltbob.grape.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

/** Draws the latest camera frame fit-centred and rotated by the scope's roll angle. */
class CameraView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private var bitmap: Bitmap? = null
    private var angleDegrees = 0f
    private val matrix = Matrix()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    fun setFrame(frame: Bitmap, angle: Float) {
        bitmap = frame
        angleDegrees = angle
        invalidate()
    }

    fun clear() {
        bitmap = null
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val b = bitmap ?: return
        if (b.isRecycled) return
        val vw = width.toFloat()
        val vh = height.toFloat()
        val bw = b.width.toFloat()
        val bh = b.height.toFloat()
        if (vw <= 0f || vh <= 0f || bw <= 0f || bh <= 0f) return
        val scale = min(vw / bw, vh / bh)
        matrix.reset()
        matrix.postTranslate(-bw / 2f, -bh / 2f)
        matrix.postScale(scale, scale)
        matrix.postRotate(angleDegrees)
        matrix.postTranslate(vw / 2f, vh / 2f)
        canvas.drawBitmap(b, matrix, paint)
    }
}
