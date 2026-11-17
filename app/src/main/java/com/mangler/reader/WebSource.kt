package com.mangler.reader

import android.content.Context
import android.webkit.CookieManager
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Páginas de um capítulo/galeria online: baixa cada imagem uma vez (cache em disco)
 * usando Referer, User-Agent e cookies do navegador.
 * [referers] (opcional) traz um Referer por imagem, na mesma ordem de [urls].
 * Endereços terminados em "#mv" (ou com extensão de vídeo) são vídeos WebM/MP4: baixados do mesmo jeito,
 * mas exibidos pelo player em tela cheia do leitor.
 */
class WebSource(
    ctx: Context,
    private val urls: List<String>,
    private val referer: String,
    private val ua: String,
    private val referers: List<String>? = null
) : PageSource {

    private val dir = File(ctx.cacheDir, "web").apply {
        deleteRecursively()
        mkdirs()
    }
    private val locks = Array(urls.size) { Any() }

    override val count: Int get() = urls.size

    /** A página [i] é um vídeo (WebM/MP4)? */
    fun isVideo(i: Int): Boolean {
        val u = urls.getOrNull(i) ?: return false
        if (u.substringAfter('#', "") == "mv") return true
        val p = u.substringBefore('#').substringBefore('?').lowercase()
        return VIDEO_EXT.any { p.endsWith(it) }
    }

    /** Baixa (se ainda não baixou) e devolve o arquivo do vídeo da página [i]. */
    fun videoFile(i: Int): File {
        open(i).close()
        return File(dir, i.toString())
    }

    override fun open(i: Int): InputStream {
        val f = File(dir, i.toString())
        synchronized(locks[i]) {
            if (!f.exists()) download(urls[i].substringBefore('#'), f, referers?.getOrNull(i) ?: referer)
        }
        return FileInputStream(f)
    }

    private fun download(url: String, dest: File, ref: String) {
        require(url.startsWith("http://") || url.startsWith("https://")) { "URL inválida" }
        var last: Exception? = null
        for (attempt in 1..3) {
            try {
                downloadOnce(url, dest, ref)
                return
            } catch (e: HttpStatusException) {
                last = e
                if (e.code in 400..499 && e.code != 429 && e.code != 408) throw e
            } catch (e: IOException) {
                last = e
            }
            if (attempt < 3) Thread.sleep(500L * attempt)
        }
        throw last ?: IOException("Falha ao baixar imagem")
    }

    private fun downloadOnce(url: String, dest: File, ref: String) {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15000
            conn.readTimeout = 30000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", ua)
            if (ref.isNotEmpty()) conn.setRequestProperty("Referer", ref)
            conn.setRequestProperty("Accept", "image/avif,image/webp,image/*,video/*,*/*;q=0.8")
            CookieManager.getInstance().getCookie(url)?.let { conn.setRequestProperty("Cookie", it) }
            if (conn.responseCode != 200) throw HttpStatusException(conn.responseCode, url)
            val tmp = File(dest.parentFile, dest.name + ".part")
            conn.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
            if (!tmp.renameTo(dest)) throw IOException("Falha ao salvar imagem")
        } finally {
            conn.disconnect()
        }
    }

    override fun close() {}

    private companion object {
        val VIDEO_EXT = listOf(".webm", ".mp4", ".m4v", ".mov")
    }
}
