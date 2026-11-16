package com.mangler.reader

import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class ReaderActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var pager: ViewPager2
    private lateinit var topBar: LinearLayout
    private lateinit var bottomBar: LinearLayout
    private lateinit var pageLabel: TextView
    private lateinit var seek: SeekBar
    private lateinit var progress: ProgressBar

    private val io: ExecutorService = Executors.newFixedThreadPool(2)
    private val ui = Handler(Looper.getMainLooper())
    private var source: PageSource? = null
    private var rtl = true
    private var overlayVisible = false
    private var webMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("mangler", MODE_PRIVATE)
        rtl = prefs.getBoolean("rtl", true)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        buildUi()
        enterImmersive()

        // Capítulo vindo do navegador: lista de URLs de imagens
        val webUrls = intent.getStringArrayExtra("urls")
        webMode = webUrls != null
        val referer = intent.getStringExtra("referer") ?: ""
        val ua = intent.getStringExtra("ua") ?: "Mozilla/5.0"

        val uri = if (webMode) null else Uri.parse(intent.getStringExtra("uri"))
        val folder = intent.getBooleanExtra("folder", false)
        val resume = intent.getBooleanExtra("resume", false)

        io.execute {
            try {
                val s = when {
                    webUrls != null -> WebSource(this, webUrls.toList(), referer, ua)
                    folder -> Sources.openFolder(this, uri!!)
                    else -> Sources.openZip(this, uri!!)
                }
                ui.post { show(s, if (resume && !webMode) prefs.getInt("page", 0) else 0) }
            } catch (e: Throwable) {
                ui.post {
                    Toast.makeText(this, "Não consegui abrir: ${e.message}", Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        }
    }

    private fun buildUi() {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        pager = ViewPager2(this).apply {
            layoutParams = FrameLayout.LayoutParams(-1, -1)
            offscreenPageLimit = 1
            setPageTransformer(PageFlipTransformer())
            layoutDirection = if (rtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
        }
        root.addView(pager)

        progress = ProgressBar(this).apply {
            layoutParams = FrameLayout.LayoutParams(-2, -2, Gravity.CENTER)
        }
        root.addView(progress)

        // Barra superior: voltar, contador e direção de leitura
        topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xCC000000.toInt())
            setPadding(dp(8), dp(28), dp(8), dp(8))
            layoutParams = FrameLayout.LayoutParams(-1, -2, Gravity.TOP)
            visibility = View.GONE
        }
        topBar.addView(textBtn("←") { finish() })
        pageLabel = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 16f
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        topBar.addView(pageLabel)
        val dirBtn = textBtn(dirText()) {}
        dirBtn.setOnClickListener {
            rtl = !rtl
            prefs.edit().putBoolean("rtl", rtl).apply()
            applyDirection()
            dirBtn.text = dirText()
        }
        topBar.addView(dirBtn)
        root.addView(topBar)

        // Barra inferior: ir para a página
        bottomBar = LinearLayout(this).apply {
            setBackgroundColor(0xCC000000.toInt())
            setPadding(dp(16), dp(8), dp(16), dp(20))
            layoutParams = FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM)
            visibility = View.GONE
        }
        seek = SeekBar(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, -2)
            layoutDirection = pager.layoutDirection
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                    if (fromUser) pager.setCurrentItem(p, false)
                }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        }
        bottomBar.addView(seek)
        root.addView(bottomBar)

        setContentView(root)
    }

    private fun textBtn(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label
        setTextColor(Color.WHITE)
        textSize = 16f
        setPadding(dp(14), dp(8), dp(14), dp(8))
        setOnClickListener { onClick() }
    }

    private fun dirText() = if (rtl) "Mangá ←" else "Livro →"

    private fun applyDirection() {
        val d = if (rtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
        pager.layoutDirection = d
        seek.layoutDirection = d
        pager.adapter?.notifyDataSetChanged()
    }

    private fun show(s: PageSource, startPage: Int) {
        progress.visibility = View.GONE
        if (s.count == 0) {
            Toast.makeText(this, "Nenhuma imagem encontrada", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        source = s
        val dm = resources.displayMetrics
        pager.adapter = PageAdapter(s, io, ui, dm.widthPixels * 2, dm.heightPixels * 2) { handleTap(it) }
        seek.max = s.count - 1
        val start = startPage.coerceIn(0, s.count - 1)
        pager.setCurrentItem(start, false)
        updateLabel(start)
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updateLabel(position)
                // Só guarda a posição de arquivos locais; capítulo online não substitui o "Continuar lendo"
                if (!webMode) prefs.edit().putInt("page", position).apply()
            }
        })
    }

    private fun updateLabel(pos: Int) {
        val total = source?.count ?: 0
        pageLabel.text = "${pos + 1} / $total"
        seek.progress = pos
    }

    private fun handleTap(f: Float) {
        when {
            f < 0.25f -> go(if (rtl) 1 else -1)
            f > 0.75f -> go(if (rtl) -1 else 1)
            else -> toggleOverlay()
        }
    }

    private fun go(delta: Int) {
        val target = pager.currentItem + delta
        if (target in 0 until (source?.count ?: 0)) pager.setCurrentItem(target, true)
    }

    private fun toggleOverlay() {
        overlayVisible = !overlayVisible
        val vis = if (overlayVisible) View.VISIBLE else View.GONE
        topBar.visibility = vis
        bottomBar.visibility = vis
    }

    private fun enterImmersive() {
        val c = WindowCompat.getInsetsController(window, window.decorView)
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        c.hide(WindowInsetsCompat.Type.systemBars())
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterImmersive()
    }

    override fun onDestroy() {
        super.onDestroy()
        io.shutdownNow()
        try { source?.close() } catch (_: Throwable) {}
    }
}

class PageAdapter(
    private val src: PageSource,
    private val io: ExecutorService,
    private val ui: Handler,
    private val maxW: Int,
    private val maxH: Int,
    private val onTap: (Float) -> Unit
) : RecyclerView.Adapter<PageAdapter.VH>() {

    class VH(val img: ZoomImageView) : RecyclerView.ViewHolder(img)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = ZoomImageView(parent.context)
        v.layoutParams = ViewGroup.LayoutParams(-1, -1)
        v.onTap = onTap
        return VH(v)
    }

    override fun getItemCount() = src.count

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.img.setImageDrawable(null)
        holder.img.tag = position
        io.execute {
            val bmp = decode(position)
            ui.post {
                if (holder.img.tag == position && bmp != null) holder.img.setImageDrawable(android.graphics.drawable.BitmapDrawable(holder.img.resources, bmp))
            }
        }
    }

    override fun onViewRecycled(holder: VH) {
        holder.img.tag = -1
        holder.img.setImageDrawable(null)
    }

    private fun decode(i: Int): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        src.open(i).use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxW && bounds.outHeight / (sample * 2) >= maxH) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        src.open(i).use { BitmapFactory.decodeStream(it, null, opts) }
    } catch (e: Throwable) {
        null
    }
}
