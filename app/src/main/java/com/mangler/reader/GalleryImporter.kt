package com.mangler.reader

import android.net.Uri
import android.webkit.CookieManager
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.Charset
import java.util.TreeSet
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class HttpStatusException(val code: Int, url: String) : IOException("HTTP $code em $url")

/** Requisições em segundo plano usando a sessão (cookies) e o User-Agent da WebView. */
object Http {
    private val charsetRegex = Regex("charset=([\\w-]+)", RegexOption.IGNORE_CASE)

    fun getHtml(url: String, referer: String, ua: String, cancelled: () -> Boolean, attempts: Int = 3): String {
        require(url.startsWith("http://") || url.startsWith("https://")) { "URL inválida: $url" }
        var last: Exception? = null
        for (a in 1..attempts) {
            if (cancelled()) throw InterruptedException("cancelado")
            try {
                return once(url, referer, ua)
            } catch (e: HttpStatusException) {
                last = e
                // 4xx (exceto 408/429) não adianta repetir
                if (e.code in 400..499 && e.code != 429 && e.code != 408) throw e
            } catch (e: IOException) {
                last = e
            }
            if (a < attempts) Thread.sleep(500L * a)
        }
        throw last ?: IOException("Falha ao carregar $url")
    }

    private fun once(url: String, referer: String, ua: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15000
            conn.readTimeout = 25000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", ua)
            if (referer.isNotEmpty()) conn.setRequestProperty("Referer", referer)
            conn.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            conn.setRequestProperty("Accept-Language", "pt-BR,pt;q=0.9,en;q=0.8")
            CookieManager.getInstance().getCookie(url)?.let { conn.setRequestProperty("Cookie", it) }

            val code = conn.responseCode
            // Mantém a sessão: guarda cookies novos no mesmo CookieManager da WebView
            for ((k, v) in conn.headerFields) {
                if (k != null && k.equals("Set-Cookie", ignoreCase = true)) {
                    v.forEach { CookieManager.getInstance().setCookie(url, it) }
                }
            }
            if (code !in 200..299) throw HttpStatusException(code, url)

            val cs = conn.contentType?.let { charsetRegex.find(it)?.groupValues?.get(1) }
                ?.let { try { Charset.forName(it) } catch (_: Exception) { null } }
                ?: Charsets.UTF_8
            val bytes = conn.inputStream.use { readLimited(it, 4_000_000) }
            return String(bytes, cs)
        } finally {
            conn.disconnect()
        }
    }

    private fun readLimited(input: InputStream, limit: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > limit) throw IOException("Página grande demais")
        }
        return out.toByteArray()
    }
}

/**
 * Importa galerias com miniaturas e paginação numérica:
 * acha todas as páginas numeradas, lê as miniaturas de cada uma (em segundo plano),
 * abre cada página individual para descobrir a imagem em tamanho grande e devolve
 * tudo na ordem original da galeria.
 */
class GalleryImporter(private val ua: String, private val listener: Listener) {

    interface Listener {
        fun onStatus(s: Status)
        fun onDone(r: ImportResult)
        fun onFailed(message: String)
        /** A página não é uma galeria deste tipo: o navegador usa o extrator comum. */
        fun onNotGallery()
    }

    data class Status(
        val phase: String,
        val pagesTotal: Int,
        val pagesFetched: Int,
        val imagesFound: Int,
        val expected: Int?,
        val resolved: Int
    )

    class ImportResult(
        val urls: List<String>,
        val referers: List<String>,
        val expected: Int?,
        val failedImages: Int,
        val failedPages: List<Int>
    )

    private class Pagination(val maxPage: Int, val urlFor: (Int) -> String)

    @Volatile private var cancelled = false
    @Volatile private var pool: ExecutorService? = null
    private val lock = Any()

    fun cancel() {
        cancelled = true
        pool?.shutdownNow()
    }

    fun start(pageUrl: String, html: String) {
        Thread({ doImport(pageUrl, html) }, "gallery-import").start()
    }

    private fun isCancelled() = cancelled

    // ---------------------------------------------------------------- fluxo principal

    private fun doImport(pageUrl: String, html: String) {
        val workers = Executors.newFixedThreadPool(4)
        pool = workers
        try {
            val doc0 = Jsoup.parse(html, pageUrl)
            val pag0 = findPagination(doc0, pageUrl)
            val hints = countHints(doc0)
            val group0 = bestGroup(doc0, null, hints)
            val thumbs0 = group0?.second ?: emptyList()

            val isGallery = group0 != null && thumbs0.size >= 2 && (
                (pag0 != null && (hints.isNotEmpty() || thumbs0.size >= 6)) || thumbs0.size in hints
                )
            if (!isGallery || group0 == null) {
                listener.onNotGallery()
                return
            }
            val sig = group0.first
            val expected = hints.firstOrNull { it >= thumbs0.size }

            var maxPage = pag0?.maxPage ?: 1
            val pageThumbs = HashMap<Int, List<String>>()
            val fetched = HashSet<Int>()
            val failedPages = TreeSet<Int>()
            var lastError: String? = null
            var imagesFound = 0
            val resolved = AtomicInteger(0)
            var lastPublish = 0L

            fun publish(phase: String, force: Boolean = false) {
                val st: Status? = synchronized(lock) {
                    val now = System.currentTimeMillis()
                    if (!force && now - lastPublish < 120) {
                        null
                    } else {
                        lastPublish = now
                        Status(phase, maxPage, fetched.size, imagesFound, expected, resolved.get())
                    }
                }
                if (st != null) listener.onStatus(st)
            }

            if (pag0 == null) {
                pageThumbs[1] = thumbs0
                fetched.add(1)
                imagesFound = thumbs0.size
            }
            publish("Lendo páginas da galeria", true)

            // ---- fase 1: todas as páginas numeradas (descobre novas se a paginação for "em janela")
            var rounds = 0
            while (pag0 != null && rounds++ < 40) {
                val todo = synchronized(lock) { (1..maxPage).filter { it !in fetched } }
                if (todo.isEmpty()) break
                if (maxPage > MAX_PAGES) {
                    throw IOException("A paginação indica $maxPage páginas, acima do limite de $MAX_PAGES")
                }
                val tasks = todo.map { n ->
                    Callable<Unit> {
                        val url = pag0.urlFor(n)
                        try {
                            val h = Http.getHtml(url, pageUrl, ua, ::isCancelled)
                            val d = Jsoup.parse(h, url)
                            val t = bestGroup(d, sig, emptyList())?.second ?: emptyList()
                            val p2 = findPagination(d, url)
                            synchronized(lock) {
                                pageThumbs[n] = t
                                fetched.add(n)
                                imagesFound = pageThumbs.values.sumOf { it.size }
                                if (p2 != null && p2.maxPage > maxPage) maxPage = p2.maxPage
                            }
                        } catch (e: Exception) {
                            if (!cancelled) {
                                synchronized(lock) {
                                    fetched.add(n)
                                    failedPages.add(n)
                                    lastError = "página $n: ${e.message ?: e.javaClass.simpleName}"
                                }
                            }
                        }
                        publish("Lendo páginas da galeria")
                    }
                }
                workers.invokeAll(tasks)
                if (cancelled) return
            }

            if (1 in failedPages) {
                listener.onFailed("Não consegui carregar a primeira página da galeria (${lastError ?: "erro desconhecido"}).")
                return
            }

            // ---- ordem original: página 1..N, dentro de cada página a ordem do HTML
            val seen = HashSet<String>()
            val items = ArrayList<Pair<String, String>>() // (página da imagem, página da galeria)
            for (n in pageThumbs.keys.sorted()) {
                val ref = if (pag0 != null) pag0.urlFor(n) else pageUrl
                for (h in pageThumbs[n].orEmpty()) {
                    if (items.size >= MAX_IMAGES) break
                    if (seen.add(h)) items.add(h to ref)
                }
            }
            if (items.isEmpty()) {
                if (failedPages.isNotEmpty()) {
                    listener.onFailed("Nenhuma página da galeria carregou (${lastError ?: "erro desconhecido"}).")
                } else {
                    listener.onNotGallery()
                }
                return
            }

            // ---- fase 2: descobrir a imagem grande de cada miniatura
            val total = items.size
            synchronized(lock) { imagesFound = total }
            publish("Resolvendo imagens", true)

            val results = arrayOfNulls<String>(total)
            val errors = arrayOfNulls<String>(total)

            fun resolveOne(i: Int) {
                val (href, ref) = items[i]
                try {
                    results[i] = if (imgExt.containsMatchIn(href)) {
                        href
                    } else {
                        val h = Http.getHtml(href, ref, ua, ::isCancelled)
                        pickMainImage(Jsoup.parse(h, href)) ?: throw IOException("imagem não encontrada em $href")
                    }
                    errors[i] = null
                    resolved.incrementAndGet()
                } catch (e: Exception) {
                    if (!cancelled) errors[i] = e.message ?: e.javaClass.simpleName
                }
                publish("Resolvendo imagens")
            }

            workers.invokeAll((0 until total).map { i -> Callable<Unit> { resolveOne(i) } })
            if (cancelled) return

            // segunda passada: falhas temporárias não descartam o que já foi achado
            val retry = (0 until total).filter { results[it] == null }
            if (retry.isNotEmpty()) {
                Thread.sleep(1000)
                workers.invokeAll(retry.map { i -> Callable<Unit> { resolveOne(i) } })
                if (cancelled) return
            }
            publish("Finalizando", true)

            // ---- monta a sequência final sem duplicar nem reordenar
            val used = HashSet<String>()
            val urls = ArrayList<String>()
            val refs = ArrayList<String>()
            for (i in 0 until total) {
                val u = results[i] ?: continue
                if (!used.add(u)) continue
                urls.add(u)
                refs.add(items[i].first)
            }

            if (urls.isEmpty()) {
                val firstErr = errors.firstOrNull { it != null }
                if (firstErr != null && firstErr.startsWith("HTTP")) {
                    listener.onFailed("O site recusou as páginas das imagens: $firstErr")
                } else {
                    listener.onNotGallery()
                }
                return
            }
            listener.onDone(ImportResult(urls, refs, expected, total - urls.size, failedPages.toList()))
        } catch (e: Throwable) {
            if (cancelled) return
            listener.onFailed(e.message ?: e.javaClass.simpleName)
        } finally {
            workers.shutdownNow()
        }
    }

    // ---------------------------------------------------------------- paginação

    private fun numericLinks(doc: Document): List<Pair<Int, String>> {
        val out = ArrayList<Pair<Int, String>>()
        for (a in doc.select("a[href]")) {
            val t = a.text().trim()
            if (!numberRegex.matches(t)) continue
            val n = t.toInt()
            if (n < 1) continue
            val h = a.absUrl("href").substringBefore('#')
            if (h.startsWith("http://") || h.startsWith("https://")) out.add(n to h)
        }
        return out
    }

    private fun sameOtherParams(a: Uri, b: Uri, skip: String): Boolean {
        val names = HashSet<String>()
        names.addAll(a.queryParameterNames)
        names.addAll(b.queryParameterNames)
        names.remove(skip)
        return names.all { a.getQueryParameter(it) == b.getQueryParameter(it) }
    }

    private fun withParam(cur: Uri, param: String, value: Int): String {
        val b = cur.buildUpon().clearQuery().fragment(null)
        for (name in cur.queryParameterNames) {
            if (name == param) continue
            for (v in cur.getQueryParameters(name)) b.appendQueryParameter(name, v)
        }
        b.appendQueryParameter(param, value.toString())
        return b.build().toString()
    }

    private fun segs(path: String?): List<String> = (path ?: "").split('/').filter { it.isNotEmpty() }

    /**
     * Reconhece a paginação numérica pelos links com texto "1", "2", "3"...
     * Aceita número em parâmetro de query (?page=2, ?p=1) ou em segmento de caminho (/page/2, /2),
     * tanto com base 1 quanto com base 0.
     */
    private fun findPagination(doc: Document, pageUrl: String): Pagination? {
        val links = numericLinks(doc)
        if (links.isEmpty()) return null
        val cur = Uri.parse(pageUrl.substringBefore('#'))
        val curHost = cur.host ?: return null
        val curPath = (cur.path ?: "").trimEnd('/')

        // 1) query
        val qVotes = HashMap<String, Int>()
        val qMax = HashMap<String, Int>()
        for ((n, h) in links) {
            val u = Uri.parse(h)
            if (u.host != curHost || (u.path ?: "").trimEnd('/') != curPath) continue
            for (p in u.queryParameterNames) {
                val v = u.getQueryParameter(p)?.toIntOrNull() ?: continue
                val off = n - v
                if (off !in 0..1) continue
                if (!sameOtherParams(cur, u, p)) continue
                val key = "$p|$off"
                qVotes[key] = (qVotes[key] ?: 0) + 1
                qMax[key] = maxOf(qMax[key] ?: 0, n)
            }
        }
        val qBest = qVotes.maxByOrNull { it.value }
        if (qBest != null) {
            val i = qBest.key.lastIndexOf('|')
            val param = qBest.key.substring(0, i)
            val off = qBest.key.substring(i + 1).toInt()
            return Pagination(qMax[qBest.key] ?: 1) { n -> withParam(cur, param, n - off) }
        }

        // 2) caminho
        val curSegs = segs(cur.path)
        val pVotes = HashMap<String, Int>()
        val pMax = HashMap<String, Int>()
        for ((n, h) in links) {
            val u = Uri.parse(h)
            if (u.host != curHost || u.query != cur.query) continue
            val us = segs(u.path)
            val key: String
            if (us.size == curSegs.size) {
                val diff = us.indices.filter { us[it] != curSegs[it] }
                if (diff.size != 1) continue
                val v = us[diff[0]].toIntOrNull() ?: continue
                val off = n - v
                if (off !in 0..1) continue
                key = "A|${diff[0]}|$off"
            } else if (us.size > curSegs.size && us.subList(0, curSegs.size) == curSegs) {
                val tail = us.subList(curSegs.size, us.size)
                val v = tail.last().toIntOrNull() ?: continue
                val off = n - v
                if (off !in 0..1) continue
                key = "B|${tail.dropLast(1).joinToString("/")}|$off"
            } else continue
            pVotes[key] = (pVotes[key] ?: 0) + 1
            pMax[key] = maxOf(pMax[key] ?: 0, n)
        }
        val pBest = pVotes.maxByOrNull { it.value } ?: return null
        val parts = pBest.key.split('|')
        val off = parts[2].toInt()
        val max = pMax[pBest.key] ?: 1
        return if (parts[0] == "A") {
            val idx = parts[1].toInt()
            Pagination(max) { n ->
                val s = curSegs.toMutableList()
                s[idx] = (n - off).toString()
                cur.buildUpon().path("/" + s.joinToString("/")).fragment(null).build().toString()
            }
        } else {
            val mid = if (parts[1].isEmpty()) emptyList() else parts[1].split('/')
            Pagination(max) { n ->
                val s = curSegs + mid + (n - off).toString()
                cur.buildUpon().path("/" + s.joinToString("/")).fragment(null).build().toString()
            }
        }
    }

    // ---------------------------------------------------------------- miniaturas

    private fun hostOf(url: String): String = Uri.parse(url).host?.removePrefix("www.") ?: ""

    private fun looksLikeAd(e: Element): Boolean {
        var p: Element? = e
        var depth = 0
        while (p != null && depth < 4) {
            val s = (p.className() + " " + p.id()).lowercase()
            if (adRegex.containsMatchIn(s)) return true
            if (p.attr("rel").lowercase().contains("sponsored")) return true
            p = p.parent()
            depth++
        }
        return false
    }

    /**
     * O link tem uma miniatura? Vale <img>, mas também miniatura desenhada como imagem de fundo
     * (<div style="background:url(...)">), que é o formato de várias galerias e não tem <img>.
     */
    private fun hasThumbMedia(a: Element): Boolean {
        if (a.selectFirst("img, picture, video[poster]") != null) return true
        if (a.attr("style").contains("url(", ignoreCase = true)) return true
        if (a.select("[style]").any { it.attr("style").contains("url(", ignoreCase = true) }) return true
        return a.selectFirst("[data-src], [data-bg], [data-background], [data-thumb]") != null
    }

    /** Assinatura do formato do link: segmentos com número viram "#", e os nomes de query contam. */
    private fun signature(url: String): String {
        val u = Uri.parse(url)
        val path = segs(u.path).joinToString("/") { seg ->
            if (seg.any { it.isDigit() }) "#" else seg.lowercase()
        }
        val q = u.queryParameterNames.sorted().joinToString(",")
        return (u.host?.removePrefix("www.") ?: "") + "/" + path + "?" + q
    }

    /**
     * Grupos de links de miniatura, por assinatura, e quantos de cada grupo estão em blocos "relacionados".
     * No modo [loose] também entram links sem mídia detectável (exceto números de paginação);
     * só é usado quando o modo estrito não acha nenhum grupo.
     */
    private fun thumbGroups(doc: Document, loose: Boolean): Pair<Map<String, List<String>>, Map<String, Int>> {
        val pageUrl = doc.location()
        val host = hostOf(pageUrl)
        val groups = LinkedHashMap<String, MutableList<String>>()
        val side = HashMap<String, Int>()
        val seen = HashSet<String>()
        for (a in doc.select("a[href]")) {
            if (!hasThumbMedia(a)) {
                if (!loose) continue
                if (numberRegex.matches(a.text().trim())) continue
            }
            if (looksLikeAd(a)) continue
            val h = a.absUrl("href").substringBefore('#')
            if (!(h.startsWith("http://") || h.startsWith("https://"))) continue
            if (hostOf(h) != host || h == pageUrl.substringBefore('#')) continue
            if (!seen.add(h)) continue
            val sig = signature(h)
            groups.getOrPut(sig) { ArrayList() }.add(h)
            if (relatedRegex.containsMatchIn(ancestorsInfo(a))) side[sig] = (side[sig] ?: 0) + 1
        }
        return groups to side
    }

    private fun pickBest(
        gs: Pair<Map<String, List<String>>, Map<String, Int>>,
        hints: List<Int>,
        minSize: Int
    ): Pair<String, List<String>>? {
        val (g, side) = gs
        var cands = g.entries.filter { (side[it.key] ?: 0) * 2 <= it.value.size }
        if (cands.isEmpty()) cands = g.entries.toList()
        val best = cands.filter { it.value.size in hints }.maxByOrNull { it.value.size }
            ?: cands.maxByOrNull { it.value.size }
            ?: return null
        return if (best.value.size >= minSize) best.key to best.value else null
    }

    /**
     * Grupo de miniaturas da galeria: o de assinatura já conhecida ou, na primeira página,
     * o que bate com a contagem anunciada ("94 images") e não está em bloco de relacionados;
     * sem essas pistas, o maior. Tenta primeiro links com miniatura detectada e só depois o modo solto.
     */
    private fun bestGroup(doc: Document, preferSig: String?, hints: List<Int>): Pair<String, List<String>>? {
        val strict = thumbGroups(doc, false)
        if (preferSig != null) {
            strict.first[preferSig]?.let { return preferSig to it }
            return thumbGroups(doc, true).first[preferSig]?.let { preferSig to it }
        }
        pickBest(strict, hints, 2)?.let { return it }
        return pickBest(thumbGroups(doc, true), hints, MIN_LOOSE)
    }

    private fun countHints(doc: Document): List<Int> {
        val text = try { doc.body().text() } catch (_: Exception) { return emptyList() }
        return hintRegex.findAll(text)
            .mapNotNull { it.groupValues[1].toIntOrNull() }
            .filter { it >= 2 }
            .distinct()
            .toList()
    }

    // ---------------------------------------------------------------- imagem grande

    private fun pickMainImage(doc: Document): String? {
        // link explícito para o original (só se apontar direto para um arquivo de imagem).
        // "fullimg" é o download do original que consome cota do site: não é usado automaticamente.
        for (a in doc.select("a[href]")) {
            if (!originalRegex.containsMatchIn(a.text())) continue
            val h = a.absUrl("href")
            if (h.contains("fullimg", ignoreCase = true)) continue
            if (h.startsWith("http") && imgExt.containsMatchIn(h)) return h
        }

        var best: String? = null
        var bestScore = Int.MIN_VALUE
        for (img in doc.select("img")) {
            var u = ""
            for (attr in IMG_ATTRS) {
                val v = img.absUrl(attr)
                if (v.startsWith("http://") || v.startsWith("https://")) {
                    u = v
                    break
                }
            }
            if (u.isEmpty()) continue
            val meta = (img.className() + " " + img.id()).lowercase()
            if (badRegex.containsMatchIn(u) || badRegex.containsMatchIn(meta)) continue
            if (looksLikeAd(img)) continue

            var s = 0
            if (mainRegex.containsMatchIn(meta)) s += 50
            if (img.id().lowercase() in MAIN_IDS) s += 30
            val w = img.attr("width").toIntOrNull() ?: 0
            val h = img.attr("height").toIntOrNull() ?: 0
            s += minOf(w * h / 10000, 50)
            if (imgExt.containsMatchIn(u)) s += 10
            if (thumbRegex.containsMatchIn(u)) s -= 40
            if (sideRegex.containsMatchIn(ancestorsInfo(img))) s -= 40
            if (s > bestScore) {
                bestScore = s
                best = u
            }
        }
        if (best != null) return best

        val og = doc.selectFirst("meta[property=og:image]")?.absUrl("content") ?: ""
        return if (og.startsWith("http")) og else null
    }

    private fun ancestorsInfo(e: Element): String {
        val sb = StringBuilder()
        var p: Element? = e.parent()
        var depth = 0
        while (p != null && depth < 5) {
            sb.append(p.className()).append(' ').append(p.id()).append(' ')
            p = p.parent()
            depth++
        }
        return sb.toString().lowercase()
    }

    companion object {
        private const val MAX_PAGES = 300
        private const val MAX_IMAGES = 3000
        private const val MIN_LOOSE = 6
        private val IMG_ATTRS = listOf("src", "data-src", "data-lazy-src", "data-original", "data-url", "data-lazy")
        private val MAIN_IDS = setOf("img", "image", "main", "mainimage", "full", "photo")

        private val numberRegex = Regex("\\d{1,4}")
        private val imgExt = Regex("\\.(jpe?g|png|webp|avif|gif|bmp)(\\?.*)?$", RegexOption.IGNORE_CASE)
        private val adRegex = Regex("(^|[\\s_-])(ad|ads|adv|advert|advertisement|banner|sponsor|sponsored|promo|popup)([\\s_-]|$)")
        private val badRegex = Regex(
            "(logo|banner|sprite|icon|avatar|spinner|loading|favicon|emoji|button|/ads?/|[/_.-]ads?[_.-])",
            RegexOption.IGNORE_CASE
        )
        private val thumbRegex = Regex("(thumb|thumbnail|small|preview|mini)", RegexOption.IGNORE_CASE)
        private val mainRegex = Regex("(^|[\\s_-])(img|image|main|full|photo|picture|viewer|page|manga|comic)([\\s_-]|$)")
        private val sideRegex = Regex("(related|recommend|sidebar|thumb|nav|footer|header|comment|suggest)")
        private val relatedRegex = Regex("(related|recommend|sidebar|suggest|similar|popular|trending)")
        private val originalRegex = Regex("(original|full ?size|full image|tamanho original)", RegexOption.IGNORE_CASE)
        private val hintRegex = Regex(
            "(\\d{1,5})\\s*(?:images?|pages?|p[áa]ginas?|imagens?|fotos?|pics?|pictures?|files?)\\b",
            RegexOption.IGNORE_CASE
        )
    }
}
