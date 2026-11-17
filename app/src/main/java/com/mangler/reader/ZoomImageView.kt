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
 * AUTO: cabe inteira na largura. ORIGINAL: 1 pixel da imagem = 1 pixel da tela.
 * MANUAL: [level] de 0 a 100; 0 = original, 100 = cabe na largura (mesmo que AUTO), crescimento proporcional.
 * FILL: estica a imagem para ocupar a área útil inteira (largura e altura), deformando se preciso.
 *
 * [stretchX] e [stretchY] (em %, 100 = normal) esticam a largura e a altura de forma independente,
 * por cima de qualquer modo. Assim dá para escolher a deformação que ficar melhor.
 */
class Sizing(
    val mode: Int,
    val level: Int,
    val stretchX: Int = 100,
    val stretchY: Int = 100
) {
    companion object {
        const val AUTO = 0
        const val ORIGINAL = 1
        const val MANUAL = 2
        const val FILL = 3
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

    private val scroller = android.widget.OverScroller(context)
    private val flingStep = object : Runnable {
        override fun run() {
            if (scroller.computeScrollOffset()) {
                m.getValues(v)
                m.postTranslate(scroller.currX - v[Matrix.MTRANS_X], scroller.currY - v[Matrix.MTRANS_Y])
                clamp()
                imageMatrix = m
                postOnAnimation(this)
            }
        }
    }

    private val m by lazy { Matrix() }
    private val v by lazy { FloatArray(9) }
    // Escala configurada (modo/controle) em X e em Y. O zoom por pinça é sempre relativo a elas
    // e mantém a proporção entre as duas (o esticado escolhido não se perde ao dar zoom).
    private var baseScale = 1f
    private var baseScaleY = 1f
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
            override fun onDown(e: MotionEvent): Boolean {
                scroller.forceFinished(true)
                return true
            }

            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                val d = drawable ?: return false
                if (!canPan() || scaleDetector.isInProgress) return false
                m.getValues(v)
                val iw = d.intrinsicWidth * v[Matrix.MSCALE_X]
                val ih = d.intrinsicHeight * v[Matrix.MSCALE_Y]
                val aw = areaW()
                val ah = areaH()
                val tx = v[Matrix.MTRANS_X].toInt()
                val ty = v[Matrix.MTRANS_Y].toInt()
                val minX = if (iw > aw) (insL + aw - iw).toInt() else tx
                val maxX = if (iw > aw) insL else tx
                val minY = if (ih > ah) (insT + ah - ih).toInt() else ty
                val maxY = if (ih > ah) insT else ty
                scroller.fling(tx, ty, vx.toInt(), vy.toInt(), minX, maxX, minY, maxY)
                postOnAnimation(flingStep)
                return true
            }

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
                    if (kotlin.math.abs(v[Matrix.MTRANS_X] - before) < 0.5f && kotlin.math.abs(dx) > kotlin.math.abs(dy)) {
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
        baseScaleY *= k
        sourceScale = newSource
        super.setImageDrawable(d)
        updateLayer(d)
        clamp()
        imageMatrix = m
    }

    override fun setImageDrawable(drawable: Drawable?) {
        super.setImageDrawable(drawable)
        updateLayer(drawable)
        resetFit()
    }

    /** O GIF do Android antigo (Movie) só desenha direito sem aceleração de hardware. */
    private fun updateLayer(d: Drawable?) {
        val soft = d is MovieDrawable
        val want = if (soft) LAYER_TYPE_SOFTWARE else LAYER_TYPE_NONE
        if (layerType != want) setLayerType(want, null)
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
        m.getValues(v)
        return d.intrinsicWidth * v[Matrix.MSCALE_X] > areaW() + 1f ||
            d.intrinsicHeight * v[Matrix.MSCALE_Y] > areaH() + 1f
    }

    private fun resetFit() {
        val d = drawable ?: return
        if (width == 0 || height == 0 || d.intrinsicWidth <= 0 || d.intrinsicHeight <= 0) return
        val aw = areaW()
        val ah = areaH()
        val dw = d.intrinsicWidth.toFloat()
        val dh = d.intrinsicHeight.toFloat()
        val src = sourceScale.coerceAtLeast(0.01f)

        val sx: Float
        val sy: Float
        if (sizing.mode == Sizing.FILL) {
            // estica independente em cada eixo: a imagem inteira ocupa a área útil, sem cortar nada
            sx = aw / dw
            sy = ah / dh
        } else {
            // pixels da tela por pixel do arquivo original: ajuste pela LARGURA
            val fit = aw / (dw * src)
            val perOriginal = when (sizing.mode) {
                Sizing.AUTO -> fit
                Sizing.ORIGINAL -> 1f
                else -> fit.toDouble().pow(sizing.level.coerceIn(0, 100) / 100.0).toFloat()
            }
            sx = perOriginal * src
            sy = sx
        }
        // controles de esticar largura/altura (100% = sem mudança)
        baseScale = sx * (sizing.stretchX.coerceIn(10, 500) / 100f)
        baseScaleY = sy * (sizing.stretchY.coerceIn(10, 500) / 100f)
        m.reset()
        m.postScale(baseScale, baseScaleY)
        m.postTranslate(insL + (aw - dw * baseScale) / 2f, insT.toFloat())
        clamp()
        imageMatrix = m
    }

    private fun zoomBy(factor: Float, fx: Float, fy: Float) {
        scroller.forceFinished(true)
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
        val iw = d.intrinsicWidth * v[Matrix.MSCALE_X]
        val ih = d.intrinsicHeight * v[Matrix.MSCALE_Y]
        val aw = areaW()
        val ah = areaH()
        val tx = v[Matrix.MTRANS_X]
        val ty = v[Matrix.MTRANS_Y]
        val nx = if (iw <= aw) insL + (aw - iw) / 2f else tx.coerceIn(insL + aw - iw, insL.toFloat())
        val ny = if (ih <= ah) insT + (ah - ih) / 2f else ty.coerceIn(insT + ah - ih, insT.toFloat())
        m.postTranslate(nx - tx, ny - ty)
    }
}
