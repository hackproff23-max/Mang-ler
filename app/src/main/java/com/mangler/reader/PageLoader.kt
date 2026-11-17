package com.mangler.reader

import android.app.ActivityManager
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Movie
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Handler
import android.util.LruCache
import androidx.annotation.RequiresApi
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
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

/**
 * Página decodificada. Estática: [bmp]. Animada: [anim] (drawable que reproduz os quadros sozinho,
 * decodificando um por vez). [origW] é a largura do arquivo original;
 * [sourceScale] = pixels originais por pixel exibido.
 */
class Loaded(
    val bmp: Bitmap?,
    val origW: Int,
    val original: Boolean,
    val anim: Drawable? = null,
    /** Mantém referência aos bytes de origem enquanto a animação existir. */
    @Suppress("unused") val data: Any? = null,
    approxBytes: Int = 0
) {
    val width: Int get() = (bmp?.width ?: anim?.intrinsicWidth ?: 1).coerceAtLeast(1)
    val sourceScale: Float get() = origW.toFloat() / width
    val isAnimated: Boolean get() = anim != null

    /** Custo aproximado em memória, usado pelo cache. */
    val cost: Int = if (approxBytes > 0) approxBytes else (bmp?.allocationByteCount ?: 1)
}

/**
 * Único ponto de decodificação das páginas: fila com prioridade (página atual primeiro),
 * tarefas descartadas quando a página deixa de ser necessária, sem trabalho duplicado
 * e com cache em memória que separa versão reduzida (por percentual) e original.
 * Reconhece GIF, WebP animado e APNG e mantém a animação (não converte em imagem estática).
 * Os arquivos de origem nunca são alterados.
 */
class PageLoader(private val ctx: Context, private val src: PageSource, private val ui: Handler) {

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
        override fun sizeOf(key: String, value: Loaded) = value.cost
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
        // Animações (GIF, WebP animado, APNG) mantêm todos os quadros; se não der, cai no caminho estático
        val kind = sniff(index)
        if (kind != Kind.STATIC) {
            val a = try {
                if (Build.VERSION.SDK_INT >= 28) decodeAnimated(index, pct)
                else if (kind == Kind.GIF) decodeMovie(index, pct)
                else null
            } catch (_: Exception) {
                null
            }
            if (a != null) return a
        }
        return decodeStatic(index, pct)
    }

    private fun decodeStatic(index: Int, pct: Int): Loaded {
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
        // piso: nunca abaixo da largura da tela, para o ajuste à largura não ampliar uma imagem borrada
        val floor = minOf(ow, ctx.resources.displayMetrics.widthPixels).toFloat() / ow
        val s2 = maxOf(s, floor)
        var tw = (ow * s2).roundToInt().coerceAtLeast(1)
        var th = (oh * s2).roundToInt().coerceAtLeast(1)
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

    // ---------------------------------------------------------------- animações

    private enum class Kind { STATIC, GIF, WEBP_ANIM, APNG }

    /** Descobre pelo cabeçalho do arquivo (não pela extensão) se a imagem é animada. */
    private fun sniff(index: Int): Kind {
        val head = ByteArray(32)
        val n = try {
            src.open(index).use { readUpTo(it, head) }
        } catch (_: IOException) {
            return Kind.STATIC
        }
        fun at(i: Int, c: Char) = i < n && head[i] == c.code.toByte()
        // GIF87a / GIF89a
        if (at(0, 'G') && at(1, 'I') && at(2, 'F')) return Kind.GIF
        // WebP animado: RIFF....WEBPVP8X com a flag de animação (bit 1 dos flags)
        if (at(0, 'R') && at(1, 'I') && at(2, 'F') && at(3, 'F') && at(8, 'W') && at(9, 'E') && at(10, 'B') && at(11, 'P') &&
            at(12, 'V') && at(13, 'P') && at(14, '8') && at(15, 'X') && n > 20 && (head[20].toInt() and 0x02) != 0
        ) return Kind.WEBP_ANIM
        // APNG: PNG com o bloco acTL antes dos dados da imagem
        if (n >= 8 && head[0] == 0x89.toByte() && at(1, 'P') && at(2, 'N') && at(3, 'G') && hasActl(index)) return Kind.APNG
        return Kind.STATIC
    }

    private fun readUpTo(ins: InputStream, buf: ByteArray): Int {
        var off = 0
        while (off < buf.size) {
            val r = ins.read(buf, off, buf.size - off)
            if (r < 0) break
            off += r
        }
        return off
    }

    private fun hasActl(index: Int): Boolean {
        return try {
            src.open(index).use { raw ->
                val ins = DataInputStream(BufferedInputStream(raw, 8192))
                skipFully(ins, 8)
                var found = false
                for (i in 0 until 64) {
                    val len = ins.readInt()
                    val type = ins.readInt()
                    if (type == 0x6163544C) { found = true; break }   // "acTL"
                    if (type == 0x49444154) break                      // "IDAT": acTL já não pode vir
                    skipFully(ins, len.toLong() + 4)                   // dados + CRC
                }
                found
            }
        } catch (_: IOException) {
            false
        }
    }

    private fun skipFully(ins: InputStream, count: Long) {
        var left = count
        while (left > 0) {
            val s = ins.skip(left)
            if (s > 0) left -= s
            else {
                if (ins.read() < 0) throw java.io.EOFException()
                left--
            }
        }
    }

    /** Tamanho de exibição da animação: mesma regra da versão estática, com orçamento menor de pixels. */
    private fun animTarget(ow: Int, oh: Int, pct: Int): Pair<Int, Int> {
        val budget = pixelBudget / 2
        var tw = ow
        var th = oh
        if (pct > 0) {
            val s = 1f - 0.75f * pct / 100f
            val floor = minOf(ow, ctx.resources.displayMetrics.widthPixels).toFloat() / ow
            val s2 = maxOf(s, floor)
            tw = (ow * s2).roundToInt().coerceAtLeast(1)
            th = (oh * s2).roundToInt().coerceAtLeast(1)
        }
        if (tw.toLong() * th > budget) {
            val f = sqrt(budget.toDouble() / (tw.toLong() * th)).toFloat()
            tw = (tw * f).roundToInt().coerceAtLeast(1)
            th = (th * f).roundToInt().coerceAtLeast(1)
        }
        return tw to th
    }

    /**
     * Android 9+: AnimatedImageDrawable (GIF, WebP animado e, conforme o sistema, APNG).
     * Os quadros são decodificados um a um durante a reprodução, na resolução alvo; duração de cada quadro,
     * transparência e repetição vêm do próprio arquivo. Devolve null se o arquivo não ficou animado.
     */
    @RequiresApi(28)
    private fun decodeAnimated(index: Int, pct: Int): Loaded? {
        val bytes = src.open(index).use { it.readBytes() }
        if (bytes.size > MAX_ANIM_BYTES) return null
        val buf = ByteBuffer.wrap(bytes)
        var ow = 0
        var oh = 0
        val d = ImageDecoder.decodeDrawable(ImageDecoder.createSource(buf)) { decoder, info, _ ->
            ow = info.size.width
            oh = info.size.height
            val (tw, th) = animTarget(ow, oh, pct)
            if (tw != ow || th != oh) decoder.setTargetSize(tw, th)
        }
        if (ow <= 0 || oh <= 0 || d !is Animatable) return null
        val approx = bytes.size + d.intrinsicWidth * d.intrinsicHeight * 4 * 2
        return Loaded(null, ow, pct <= 0, anim = d, data = buf, approxBytes = approx)
    }

    /**
     * Android 5 a 8: só GIF, via Movie (quadros, duração e repetição preservados).
     * Aqui a redução de resolução não diminui o consumo (o Movie decodifica no tamanho original).
     */
    private fun decodeMovie(index: Int, pct: Int): Loaded? {
        val bytes = src.open(index).use { it.readBytes() }
        if (bytes.size > MAX_ANIM_BYTES) return null
        val mv = Movie.decodeByteArray(bytes, 0, bytes.size) ?: return null
        if (mv.duration() <= 0 || mv.width() <= 0 || mv.height() <= 0) return null
        val d = MovieDrawable(mv)
        val approx = bytes.size + mv.width() * mv.height() * 4
        return Loaded(null, mv.width(), pct <= 0, anim = d, data = bytes, approxBytes = approx)
    }

    private companion object {
        /** Animações maiores que isto caem para a primeira imagem (evita estourar a memória). */
        const val MAX_ANIM_BYTES = 40 * 1024 * 1024
    }
}
