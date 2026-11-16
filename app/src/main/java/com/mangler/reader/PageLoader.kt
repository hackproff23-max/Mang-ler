package com.mangler.reader

import android.app.ActivityManager
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.util.LruCache
import java.io.IOException
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Configurações de carregamento (persistidas em SharedPreferences). */
data class LoadSettings(
    val reducedFirst: Boolean = true,
    val reducePct: Int = 50,
    val preload: Boolean = true,
    val preloadCount: Int = 2
) {
    fun save(p: SharedPreferences) {
        p.edit()
            .putBoolean("load_reduced_first", reducedFirst)
            .putInt("load_reduce_pct", reducePct)
            .putBoolean("load_preload", preload)
            .putInt("load_preload_count", preloadCount)
            .apply()
    }

    companion object {
        fun load(p: SharedPreferences) = LoadSettings(
            p.getBoolean("load_reduced_first", true),
            p.getInt("load_reduce_pct", 50).coerceIn(0, 100),
            p.getBoolean("load_preload", true),
            p.getInt("load_preload_count", 2).coerceIn(1, 3)
        )
    }
}

/** Página decodificada. [origW] é a largura do arquivo original; [sourceScale] = pixels originais por pixel do bitmap. */
class Loaded(val bmp: Bitmap, val origW: Int, val original: Boolean) {
    val sourceScale: Float get() = origW.toFloat() / bmp.width
}

/**
 * Único ponto de decodificação das páginas: fila com prioridade (página atual primeiro),
 * tarefas descartadas quando a página deixa de ser necessária, sem trabalho duplicado
 * e com cache em memória que separa versão reduzida (por percentual) e original.
 * Os arquivos de origem nunca são alterados.
 */
class PageLoader(ctx: Context, private val src: PageSource, private val ui: Handler) {

    interface Cb {
        fun onLoaded(l: Loaded)
        fun onError()
        /** Ainda interessa o resultado? Lido em thread de fundo, deve ser barato. */
        fun active(): Boolean
    }

    @Volatile var current = 0

    private val lock = Any()
    private val inflight = HashMap<String, Task>()
    private val seq = AtomicLong()

    private val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    private val lowRam = am.isLowRamDevice || am.memoryClass <= 96
    private val maxMem = Runtime.getRuntime().maxMemory()
    /** Máximo de pixels de uma página decodificada (4 bytes por pixel). */
    private val pixelBudget: Long = minOf(maxMem / 6, 96L * 1024 * 1024) / 4

    private val cache = object : LruCache<String, Loaded>((maxMem / 8).toInt().coerceAtLeast(8 * 1024 * 1024)) {
        override fun sizeOf(key: String, value: Loaded) = value.bmp.allocationByteCount
    }

    private val pool = ThreadPoolExecutor(
        if (lowRam) 1 else 2, if (lowRam) 1 else 2, 30, TimeUnit.SECONDS,
        PriorityBlockingQueue<Runnable>(16, Comparator { a, b -> (a as Task).compareTo(b as Task) })
    )

    private inner class Task(
        val key: String, val index: Int, val pct: Int, val prio: Int, val order: Long,
        val cbs: MutableList<Cb>
    ) : Runnable, Comparable<Task> {
        @Volatile var started = false
        @Volatile var dead = false

        override fun compareTo(other: Task): Int =
            if (prio != other.prio) prio - other.prio else order.compareTo(other.order)

        override fun run() {
            if (dead) return
            started = true
            val wanted = synchronized(lock) { cbs.any { it.active() } }
            if (!wanted) {
                synchronized(lock) { if (inflight[key] === this) inflight.remove(key) }
                return
            }
            var result: Loaded? = null
            try {
                result = try {
                    decode(index, pct)
                } catch (_: OutOfMemoryError) {
                    cache.evictAll()
                    decode(index, pct)
                }
            } catch (_: Throwable) {
            }
            val notify: List<Cb> = synchronized(lock) {
                if (inflight[key] === this) inflight.remove(key)
                ArrayList(cbs)
            }
            if (result != null) cache.put(key, result)
            val r = result
            ui.post {
                for (cb in notify) if (cb.active()) { if (r != null) cb.onLoaded(r) else cb.onError() }
            }
        }
    }

    private fun keyOf(index: Int, pct: Int) = if (pct <= 0) "$index:o" else "$index:r$pct"

    /**
     * Pede a página [index] na versão reduzida [pct] (1..100) ou original (0).
     * Chamar na thread principal: se já estiver em cache, [cb] é chamado na hora.
     */
    fun request(index: Int, pct: Int, cb: Cb) {
        val key = keyOf(index, pct)
        cache.get(key)?.let {
            if (cb.active()) cb.onLoaded(it)
            return
        }
        val prio = abs(index - current)
        synchronized(lock) {
            val ex = inflight[key]
            if (ex != null) {
                ex.cbs.add(cb)
                if (prio < ex.prio && !ex.started) {
                    // era pré-carga e agora é necessária: sobe na fila sem repetir o trabalho
                    ex.dead = true
                    val t = Task(key, index, pct, prio, seq.incrementAndGet(), ex.cbs)
                    inflight[key] = t
                    pool.execute(t)
                }
                return
            }
            val t = Task(key, index, pct, prio, seq.incrementAndGet(), mutableListOf(cb))
            inflight[key] = t
            pool.execute(t)
        }
    }

    fun peek(index: Int, pct: Int): Loaded? = cache.get(keyOf(index, pct))

    /** Quantas páginas à frente preparar, limitado em aparelhos com pouca memória. */
    fun effectivePreload(s: LoadSettings) = if (!s.preload) 0 else if (lowRam) minOf(1, s.preloadCount) else s.preloadCount

    /** Prepara as próximas páginas em segundo plano (prioridade menor que a página atual). */
    fun preload(s: LoadSettings, from: Int) {
        val n = effectivePreload(s)
        val pct = if (s.reducedFirst) s.reducePct else 0
        for (k in 1..n) {
            val idx = from + k
            if (idx >= src.count) break
            if (cache.get(keyOf(idx, pct)) != null) continue
            request(idx, pct, object : Cb {
                override fun onLoaded(l: Loaded) {}
                override fun onError() {}
                override fun active() = abs(idx - current) in 1..n
            })
        }
    }

    fun trim() = cache.evictAll()

    fun shutdown() {
        pool.shutdownNow()
        cache.evictAll()
    }

    // ---------------------------------------------------------------- decodificação

    private fun decodeSample(index: Int, sample: Int): Bitmap {
        val o = BitmapFactory.Options().apply { inSampleSize = sample }
        return src.open(index).use { BitmapFactory.decodeStream(it, null, o) } ?: throw IOException("imagem inválida")
    }

    private fun decode(index: Int, pct: Int): Loaded {
        val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        src.open(index).use { BitmapFactory.decodeStream(it, null, b) }
        val ow = b.outWidth
        val oh = b.outHeight
        if (ow <= 0 || oh <= 0) throw IOException("imagem inválida")

        if (pct <= 0) {
            // Original: só reduz se passar do limite de memória (páginas gigantes)
            var sample = 1
            while ((ow / sample).toLong() * (oh / sample) > pixelBudget) sample *= 2
            return Loaded(decodeSample(index, sample), ow, true)
        }

        // Reduzida: 0% = original, 100% = 25% das dimensões. Proporção preservada.
        val s = 1f - 0.75f * pct / 100f
        var tw = (ow * s).roundToInt().coerceAtLeast(1)
        var th = (oh * s).roundToInt().coerceAtLeast(1)
        if (tw.toLong() * th > pixelBudget) {
            val f = sqrt(pixelBudget.toDouble() / (tw.toLong() * th)).toFloat()
            tw = (tw * f).roundToInt().coerceAtLeast(1)
            th = (th * f).roundToInt().coerceAtLeast(1)
        }
        // decodifica já reduzido (potência de 2) e só então ajusta ao tamanho exato
        var sample = 1
        while (ow / (sample * 2) >= tw && oh / (sample * 2) >= th) sample *= 2
        var bmp = decodeSample(index, sample)
        if (bmp.width != tw || bmp.height != th) {
            val scaled = Bitmap.createScaledBitmap(bmp, tw, th, true)
            if (scaled !== bmp) bmp.recycle()
            bmp = scaled
        }
        return Loaded(bmp, ow, false)
    }
}
