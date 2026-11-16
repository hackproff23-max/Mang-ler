package com.mangler.reader

import android.content.Context
import android.graphics.Matrix
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.appcompat.widget.AppCompatImageView

/** Imagem com zoom (pinça e toque duplo), arrastar e toque simples. */
class ZoomImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : AppCompatImageView(context, attrs) {

    var onTap: ((Float) -> Unit)? = null

    private val m by lazy { Matrix() }
    private val v by lazy { FloatArray(9) }
    private var baseScale = 1f
    private val maxMul = 6f

    private val scaleDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                zoomBy(d.scaleFactor, d.focusX, d.focusY)
                return true
            }
        })

    private val gestureDetector = GestureDetector(context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (currentScale() > baseScale * 1.05f) resetFit()
                else zoomBy((baseScale * 2.5f) / currentScale(), e.x, e.y)
                return true
            }

            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
                if (currentScale() > baseScale * 1.01f && !scaleDetector.isInProgress) {
                    m.getValues(v)
                    val before = v[Matrix.MTRANS_X]
                    m.postTranslate(-dx, -dy)
                    clamp()
                    imageMatrix = m
                    m.getValues(v)
                    // Chegou na borda lateral: deixa o ViewPager virar a página.
                    if (kotlin.math.abs(v[Matrix.MTRANS_X] - before) < 0.5f && dx != 0f) {
                        parent?.requestDisallowInterceptTouchEvent(false)
                    }
                }
                return true
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (width > 0) onTap?.invoke(e.x / width)
                return true
            }
        })

    init {
        scaleType = ScaleType.MATRIX
    }

    override fun setImageDrawable(drawable: Drawable?) {
        super.setImageDrawable(drawable)
        resetFit()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        resetFit()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(e)
        gestureDetector.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN ->
                parent?.requestDisallowInterceptTouchEvent(
                    e.pointerCount > 1 || currentScale() > baseScale * 1.01f
                )
        }
        return true
    }

    private fun currentScale(): Float {
        m.getValues(v)
        return v[Matrix.MSCALE_X]
    }

    private fun resetFit() {
        val d = drawable ?: return
        if (width == 0 || height == 0 || d.intrinsicWidth <= 0) return
        val dw = d.intrinsicWidth.toFloat()
        val dh = d.intrinsicHeight.toFloat()
        baseScale = minOf(width / dw, height / dh)
        m.reset()
        m.postScale(baseScale, baseScale)
        m.postTranslate((width - dw * baseScale) / 2f, (height - dh * baseScale) / 2f)
        imageMatrix = m
    }

    private fun zoomBy(factor: Float, fx: Float, fy: Float) {
        val cur = currentScale()
        val target = (cur * factor).coerceIn(baseScale, baseScale * maxMul)
        val f = target / cur
        m.postScale(f, f, fx, fy)
        clamp()
        imageMatrix = m
    }

    private fun clamp() {
        val d = drawable ?: return
        m.getValues(v)
        val s = v[Matrix.MSCALE_X]
        val iw = d.intrinsicWidth * s
        val ih = d.intrinsicHeight * s
        val tx = v[Matrix.MTRANS_X]
        val ty = v[Matrix.MTRANS_Y]
        val nx = if (iw <= width) (width - iw) / 2f else tx.coerceIn(width - iw, 0f)
        val ny = if (ih <= height) (height - ih) / 2f else ty.coerceIn(height - ih, 0f)
        m.postTranslate(nx - tx, ny - ty)
    }
}
