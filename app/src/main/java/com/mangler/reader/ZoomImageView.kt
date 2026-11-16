package com.mangler.reader

import android.content.Context
import android.graphics.Matrix
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.appcompat.widget.AppCompatImageView
import kotlin.math.pow

/**
 * Ajuste de tamanho da página.
 * AUTO: cabe inteira na área útil. ORIGINAL: 1 pixel da imagem = 1 pixel da tela.
 * MANUAL: [level] de 0 a 100; 0 = original, 100 = cabe inteira (mesmo que AUTO), crescimento proporcional.
 */
class Sizing(val mode: Int, val level: Int) {
    companion object {
        const val AUTO = 0
        const val ORIGINAL = 1
        const val MANUAL = 2
    }
}

/** Imagem com zoom (pinça e toque duplo), arrastar e toque simples. */
class ZoomImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : AppCompatImageView(context, attrs) {

    var onTap: ((Float) -> Unit)? = null

    /** Quanto o bitmap foi reduzido na decodificação (inSampleSize); o tamanho original = bitmap * isto. */
    var sourceScale = 1f

    /** Chamado quando o usuário amplia além da resolução do bitmap exibido (versão reduzida). */
    var onNeedOriginal: (() -> Unit)? = null

    private var sizing = Sizing(Sizing.ORIGINAL, 0)
    private var insL = 0
    private var insT = 0
    private var insR = 0
    private var insB = 0

    private val m by lazy { Matrix() }
    private val v by lazy { FloatArray(9) }
    // Escala configurada (modo/controle). O zoom por pinça é sempre relativo a ela.
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
                if (canPan() && !scaleDetector.isInProgress) {
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

    /** Aplica modo/controle e a área útil (descontando recortes de tela). Só refaz a matriz: não recarrega a imagem. */
    fun configure(s: Sizing, l: Int, t: Int, r: Int, b: Int) {
        sizing = s
        insL = l; insT = t; insR = r; insB = b
        resetFit()
    }

    /**
     * Troca a imagem por outra do mesmo conteúdo em resolução diferente, mantendo zoom e posição
     * (sem passar por tela vazia).
     */
    fun swapDrawable(d: Drawable, newSource: Float) {
        val old = drawable
        if (old == null || old.intrinsicWidth <= 0 || d.intrinsicWidth <= 0) {
            sourceScale = newSource
            setImageDrawable(d)
            return
        }
        val k = old.intrinsicWidth.toFloat() / d.intrinsicWidth
        m.preScale(k, k)
        baseScale *= k
        sourceScale = newSource
        super.setImageDrawable(d)
        clamp()
        imageMatrix = m
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
                parent?.requestDisallowInterceptTouchEvent(e.pointerCount > 1 || canPan())
        }
        return true
    }

    private fun areaW() = (width - insL - insR).coerceAtLeast(1).toFloat()
    private fun areaH() = (height - insT - insB).coerceAtLeast(1).toFloat()

    private fun currentScale(): Float {
        m.getValues(v)
        return v[Matrix.MSCALE_X]
    }

    /** A imagem (na escala atual) passa da área útil em alguma direção? */
    private fun canPan(): Boolean {
        val d = drawable ?: return false
        val s = currentScale()
        return d.intrinsicWidth * s > areaW() + 1f || d.intrinsicHeight * s > areaH() + 1f
    }

    private fun resetFit() {
        val d = drawable ?: return
        if (width == 0 || height == 0 || d.intrinsicWidth <= 0 || d.intrinsicHeight <= 0) return
        val aw = areaW()
        val ah = areaH()
        val dw = d.intrinsicWidth.toFloat()
        val dh = d.intrinsicHeight.toFloat()
        val src = sourceScale.coerceAtLeast(0.01f)
        // pixels da tela por pixel do arquivo original
        val fit = minOf(aw / (dw * src), ah / (dh * src))
        val perOriginal = when (sizing.mode) {
            Sizing.AUTO -> fit
            Sizing.ORIGINAL -> 1f
            else -> fit.toDouble().pow(sizing.level.coerceIn(0, 100) / 100.0).toFloat()
        }
        baseScale = perOriginal * src
        m.reset()
        m.postScale(baseScale, baseScale)
        m.postTranslate(insL + (aw - dw * baseScale) / 2f, insT.toFloat())
        clamp()
        imageMatrix = m
    }

    private fun zoomBy(factor: Float, fx: Float, fy: Float) {
        val cur = currentScale()
        val target = (cur * factor).coerceIn(baseScale, baseScale * maxMul)
        val f = target / cur
        m.postScale(f, f, fx, fy)
        clamp()
        imageMatrix = m
        val now = currentScale()
        if (f > 1f && now > 1.05f && now > baseScale * 1.1f && sourceScale > 1.01f) onNeedOriginal?.invoke()
    }

    private fun clamp() {
        val d = drawable ?: return
        m.getValues(v)
        val s = v[Matrix.MSCALE_X]
        val iw = d.intrinsicWidth * s
        val ih = d.intrinsicHeight * s
        val aw = areaW()
        val ah = areaH()
        val tx = v[Matrix.MTRANS_X]
        val ty = v[Matrix.MTRANS_Y]
        val nx = if (iw <= aw) insL + (aw - iw) / 2f else tx.coerceIn(insL + aw - iw, insL.toFloat())
        val ny = if (ih <= ah) insT + (ah - ih) / 2f else ty.coerceIn(insT + ah - ih, insT.toFloat())
        m.postTranslate(nx - tx, ny - ty)
    }
}
