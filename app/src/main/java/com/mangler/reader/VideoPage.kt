package com.mangler.reader

import android.content.Context
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.widget.FrameLayout
import java.io.File
import kotlin.math.pow

/**
 * Página de vídeo (WebM/MP4) do leitor: toca em tela cheia, em repetição e sem som, como um GIF.
 * Usa o mesmo [Sizing] das imagens: Ajustar à tela (vídeo inteiro visível), Tamanho original,
 * Esticar para preencher e os controles de esticar largura/altura. Toque simples vira a página.
 */
class VideoPageView(context: Context) : FrameLayout(context), TextureView.SurfaceTextureListener {

    var onTap: ((Float) -> Unit)? = null

    private val tv = TextureView(context)
    private var surface: Surface? = null
    private var mp: MediaPlayer? = null
    private var file: File? = null
    private var prepared = false
    private var wantPlay = false
    private var vw = 0
    private var vh = 0

    private var sizing = Sizing(Sizing.AUTO, 100)
    private var insL = 0
    private var insT = 0
    private var insR = 0
    private var insB = 0

    private val gd = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            if (width > 0) onTap?.invoke(e.x / width)
            return true
        }
    })

    init {
        setBackgroundColor(Color.BLACK)
        tv.surfaceTextureListener = this
        addView(tv, LayoutParams(-1, -1))
    }

    fun configure(s: Sizing, l: Int, t: Int, r: Int, b: Int) {
        sizing = s
        insL = l; insT = t; insR = r; insB = b
        applyTransform()
    }

    /** Define o arquivo do vídeo e começa a prepará-lo (assim que a superfície existir). */
    fun setFile(f: File) {
        file = f
        startPlayer()
    }

    /** Liga/desliga a reprodução (só a página atual toca). */
    fun play(on: Boolean) {
        wantPlay = on
        val p = mp ?: return
        if (!prepared) return
        try {
            if (on) { if (!p.isPlaying) p.start() } else if (p.isPlaying) p.pause()
        } catch (_: Throwable) {
        }
    }

    /** Solta o player e esquece o arquivo (página reciclada). */
    fun clear() {
        file = null
        vw = 0
        vh = 0
        release()
    }

    private fun release() {
        prepared = false
        try { mp?.release() } catch (_: Throwable) {}
        mp = null
    }

    private fun startPlayer() {
        val f = file ?: return
        val s = surface ?: return
        release()
        try {
            val p = MediaPlayer()
            mp = p
            p.setDataSource(f.absolutePath)
            p.setSurface(s)
            p.isLooping = true
            p.setVolume(0f, 0f)
            p.setOnVideoSizeChangedListener { _, w, h ->
                vw = w; vh = h
                applyTransform()
            }
            p.setOnPreparedListener { m ->
                prepared = true
                vw = m.videoWidth
                vh = m.videoHeight
                applyTransform()
                if (wantPlay) m.start()
            }
            p.setOnErrorListener { _, _, _ -> true }
            p.prepareAsync()
        } catch (_: Throwable) {
            release()
        }
    }

    /** O TextureView estica o vídeo para a view inteira; a matriz leva ao tamanho certo, centralizado na área útil. */
    private fun applyTransform() {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f || vw <= 0 || vh <= 0) return
        val aw = (w - insL - insR).coerceAtLeast(1f)
        val ah = (h - insT - insB).coerceAtLeast(1f)
        val fit = minOf(aw / vw, ah / vh)
        val sx: Float
        val sy: Float
        when (sizing.mode) {
            Sizing.FILL -> { sx = aw / vw; sy = ah / vh }
            Sizing.ORIGINAL -> { sx = 1f; sy = 1f }
            Sizing.MANUAL -> {
                val k = fit.toDouble().pow(sizing.level.coerceIn(0, 100) / 100.0).toFloat()
                sx = k; sy = k
            }
            else -> { sx = fit; sy = fit }
        }
        val dw = vw * sx * (sizing.stretchX.coerceIn(10, 500) / 100f)
        val dh = vh * sy * (sizing.stretchY.coerceIn(10, 500) / 100f)
        val m = Matrix()
        m.setScale(dw / w, dh / h, w / 2f, h / 2f)
        m.postTranslate(insL + aw / 2f - w / 2f, insT + ah / 2f - h / 2f)
        tv.setTransform(m)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        applyTransform()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        gd.onTouchEvent(e)
        return true
    }

    override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
        surface = Surface(st)
        startPlayer()
    }

    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {
        applyTransform()
    }

    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
        release()
        try { surface?.release() } catch (_: Throwable) {}
        surface = null
        return true
    }

    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
}
