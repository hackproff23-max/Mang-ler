package com.mangler.reader

import android.graphics.Color
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/** Diálogo "Importando galeria..." com páginas, imagens encontradas/resolvidas e progresso geral. */
class ImportProgressDialog(
    private val act: AppCompatActivity,
    private val onCancel: () -> Unit
) {
    private var dialog: AlertDialog? = null
    private lateinit var phase: TextView
    private lateinit var pages: TextView
    private lateinit var images: TextView
    private lateinit var resolved: TextView
    private lateinit var bar: ProgressBar

    fun show() {
        val box = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(act.dp(24), act.dp(12), act.dp(24), act.dp(4))
        }
        fun line() = TextView(act).apply {
            setTextColor(Color.parseColor("#D8D2EA"))
            textSize = 14f
            setPadding(0, act.dp(3), 0, act.dp(3))
        }
        phase = line().apply { text = "Analisando a galeria…" }
        pages = line()
        images = line()
        resolved = line()
        bar = ProgressBar(act, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            layoutParams = LinearLayout.LayoutParams(-1, act.dp(8)).apply { topMargin = act.dp(10) }
            progressDrawable.setColorFilter(Color.parseColor("#E94F8B"), android.graphics.PorterDuff.Mode.SRC_IN)
        }
        box.addView(phase)
        box.addView(pages)
        box.addView(images)
        box.addView(resolved)
        box.addView(bar)

        dialog = AlertDialog.Builder(act)
            .setTitle("Importando galeria...")
            .setView(box)
            .setCancelable(false)
            .setNegativeButton("Cancelar") { _, _ -> onCancel() }
            .create()
        dialog?.show()
    }

    fun update(s: GalleryImporter.Status) {
        if (dialog == null) return
        phase.text = s.phase
        pages.text = "Páginas identificadas: ${s.pagesFetched}/${s.pagesTotal}"
        images.text = "Imagens encontradas: ${s.imagesFound}" + (s.expected?.let { " (esperado: $it)" } ?: "")
        resolved.text = "Imagens resolvidas: ${s.resolved}/${s.imagesFound}"

        val pagesPart = if (s.pagesTotal > 0) s.pagesFetched.toFloat() / s.pagesTotal else 0f
        val resPart = if (s.imagesFound > 0) s.resolved.toFloat() / s.imagesFound else 0f
        bar.progress = ((0.3f * pagesPart + 0.7f * resPart) * 1000).toInt().coerceIn(0, 1000)
    }

    fun dismiss() {
        try { dialog?.dismiss() } catch (_: Throwable) {}
        dialog = null
    }
}
