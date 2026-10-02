package io.github.tiltbob.grape.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

/**
 * Draws the latest camera frame rotated by the scope's roll angle.
 *
 * Two framings:
 *  - rectangle: the whole frame, fit-centred (for a fixed, unrotated picture);
 *  - circle: the disc inscribed in the frame, filling the view's short side. A disc looks
 *    the same at every angle, so when auto-rotation keeps the picture upright as the scope
 *    turns, nothing appears to move but the scene; and every pixel inside it is real image
 *    at any angle, with no corners sweeping in and out.
 */
class CameraView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private var bitmap: Bitmap? = null
    private var angleDegrees = 0f
    private val matrix = Matrix()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    /** Show the inscribed disc instead of the whole rectangle. */
    var circular: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    fun setFrame(frame: Bitmap, angle: Float) {
        bitmap = frame
        angleDegrees = angle
        invalidate()
    }

    fun clear() {
        bitmap = null
        paint.shader = null
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
        val scale = if (circular) min(vw, vh) / min(bw, bh) else min(vw / bw, vh / bh)
        matrix.reset()
        matrix.postTranslate(-bw / 2f, -bh / 2f)
        matrix.postScale(scale, scale)
        matrix.postRotate(angleDegrees)
        matrix.postTranslate(vw / 2f, vh / 2f)
        if (circular) {
            // A shader-filled circle gives an anti-aliased edge; clipPath would not.
            val shader = BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            shader.setLocalMatrix(matrix)
            paint.shader = shader
            canvas.drawCircle(vw / 2f, vh / 2f, min(vw, vh) / 2f, paint)
            paint.shader = null
        } else {
            canvas.drawBitmap(b, matrix, paint)
        }
    }
}
