package com.mangler.reader

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile

interface PageSource : Closeable {
    val count: Int
    fun open(i: Int): InputStream
}

private val IMAGE_EXT = setOf("jpg", "jpeg", "png", "webp", "bmp", "gif")

private fun isImage(name: String) = name.substringAfterLast('.', "").lowercase() in IMAGE_EXT

/** Ordena "pag2" antes de "pag10". */
fun naturalCompare(a: String, b: String): Int {
    var i = 0
    var j = 0
    while (i < a.length && j < b.length) {
        if (a[i].isDigit() && b[j].isDigit()) {
            var ei = i
            while (ei < a.length && a[ei].isDigit()) ei++
            var ej = j
            while (ej < b.length && b[ej].isDigit()) ej++
            val na = a.substring(i, ei).trimStart('0')
            val nb = b.substring(j, ej).trimStart('0')
            if (na.length != nb.length) return na.length - nb.length
            val c = na.compareTo(nb)
            if (c != 0) return c
            i = ei
            j = ej
        } else {
            val c = a[i].lowercaseChar().compareTo(b[j].lowercaseChar())
            if (c != 0) return c
            i++
            j++
        }
    }
    return (a.length - i) - (b.length - j)
}

class ZipSource(private val zip: ZipFile, private val names: List<String>) : PageSource {
    override val count: Int get() = names.size
    override fun open(i: Int): InputStream = zip.getInputStream(zip.getEntry(names[i]))
    override fun close() = zip.close()
}

class FolderSource(private val ctx: Context, private val files: List<DocumentFile>) : PageSource {
    override val count: Int get() = files.size
    override fun open(i: Int): InputStream = ctx.contentResolver.openInputStream(files[i].uri)!!
    override fun close() {}
}

object Sources {
    fun openZip(ctx: Context, uri: Uri): PageSource {
        val tmp = File(ctx.cacheDir, "current.cbz")
        ctx.contentResolver.openInputStream(uri)!!.use { input ->
            tmp.outputStream().use { out -> input.copyTo(out) }
        }
        val zip = ZipFile(tmp)
        val names = zip.entries().asSequence()
            .filter { !it.isDirectory && isImage(it.name) }
            .filter { !it.name.contains("__MACOSX") && !it.name.substringAfterLast('/').startsWith(".") }
            .map { it.name }
            .sortedWith(Comparator { a, b -> naturalCompare(a, b) })
            .toList()
        return ZipSource(zip, names)
    }

    fun openFolder(ctx: Context, uri: Uri): PageSource {
        val dir = DocumentFile.fromTreeUri(ctx, uri) ?: error("Pasta inválida")
        val files = dir.listFiles()
            .filter { it.isFile && isImage(it.name ?: "") }
            .sortedWith(Comparator { a, b -> naturalCompare(a.name ?: "", b.name ?: "") })
        return FolderSource(ctx, files)
    }
}
