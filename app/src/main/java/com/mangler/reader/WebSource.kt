package com.mangler.reader

import android.content.Context
import android.webkit.CookieManager
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Páginas de um capítulo online: baixa cada imagem uma vez (cache em disco)
 * usando Referer, User-Agent e cookies do navegador.
 */
class WebSource(
    ctx: Context,
    private val urls: List<String>,
    private val referer: String,
    private val ua: String
) : PageSource {

    private val dir = File(ctx.cacheDir, "web").apply {
        deleteRecursively()
        mkdirs()
    }
    private val locks = Array(urls.size) { Any() }

    override val count: Int get() = urls.size

    override fun open(i: Int): InputStream {
        val f = File(dir, i.toString())
        synchronized(locks[i]) {
            if (!f.exists()) download(urls[i], f)
        }
        return FileInputStream(f)
    }

    private fun download(url: String, dest: File) {
        require(url.startsWith("http://") || url.startsWith("https://")) { "URL inválida" }
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15000
            conn.readTimeout = 30000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", ua)
            conn.setRequestProperty("Referer", referer)
            conn.setRequestProperty("Accept", "image/avif,image/webp,image/*,*/*;q=0.8")
            CookieManager.getInstance().getCookie(url)?.let { conn.setRequestProperty("Cookie", it) }
            if (conn.responseCode != 200) error("HTTP ${conn.responseCode}")
            val tmp = File(dest.parentFile, dest.name + ".part")
            conn.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
            if (!tmp.renameTo(dest)) error("Falha ao salvar imagem")
        } finally {
            conn.disconnect()
        }
    }

    override fun close() {}
}
