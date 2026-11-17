package com.mangler.reader

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Movie
import android.graphics.PixelFormat
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.os.SystemClock

/**
 * GIF animado para Android anterior ao 9 (onde não existe ImageDecoder).
 * Usa android.graphics.Movie: respeita a duração de cada quadro, a transparência e a repetição do arquivo.
 * No Android 9+ o leitor usa AnimatedImageDrawable (GIF, WebP animado e APNG) e não passa por aqui.
 */
class MovieDrawable(private val movie: Movie) : Drawable(), Animatable {

    private val dur = movie.duration().coerceAtLeast(1)
    private var running = false
    private var t0 = 0L
    private var lastTime = 0

    override fun getIntrinsicWidth() = movie.width()
    override fun getIntrinsicHeight() = movie.height()

    override fun draw(canvas: Canvas) {
        if (running) lastTime = ((SystemClock.uptimeMillis() - t0) % dur).toInt()
        movie.setTime(lastTime)
        val b = bounds
        val w = movie.width().coerceAtLeast(1)
        val h = movie.height().coerceAtLeast(1)
        canvas.save()
        canvas.translate(b.left.toFloat(), b.top.toFloat())
        if (b.width() != w || b.height() != h) canvas.scale(b.width().toFloat() / w, b.height().toFloat() / h)
        movie.draw(canvas, 0f, 0f)
        canvas.restore()
        if (running) invalidateSelf()
    }

    override fun start() {
        if (running) return
        running = true
        t0 = SystemClock.uptimeMillis() - lastTime
        invalidateSelf()
    }

    override fun stop() {
        running = false
    }

    override fun isRunning() = running

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
