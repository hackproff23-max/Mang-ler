package com.mangler.reader

import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
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
import androidx.core.view.ViewCompat
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

    // Painel de tamanho
    private lateinit var sizePanel: LinearLayout
    private lateinit var btnAuto: TextView
    private lateinit var btnOriginal: TextView
    private lateinit var sizeSeek: SeekBar
    private lateinit var sizeValue: TextView
    private var updatingPanel = false

    private val io: ExecutorService = Executors.newFixedThreadPool(2)
    private val ui = Handler(Looper.getMainLooper())
    private var source: PageSource? = null
    private var adapter: PageAdapter? = null
    private var rtl = true
    private var overlayVisible = false
    private var webMode = false

    // Padrão: 0 (tamanho original). Salvo entre páginas e entre aberturas do app.
    private var sizing = Sizing(Sizing.ORIGINAL, 0)
    private var sizeLevel = 0
    private var insets = intArrayOf(0, 0, 0, 0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("mangler", MODE_PRIVATE)
        rtl = prefs.getBoolean("rtl", true)
        sizeLevel = prefs.getInt("size_level", 0).coerceIn(0, 100)
        sizing = Sizing(prefs.getInt("size_mode", Sizing.ORIGINAL).coerceIn(0, 2), sizeLevel)

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
        val referers = intent.getStringArrayExtra("referers")?.toList()

        val uri = if (webMode) null else Uri.parse(intent.getStringExtra("uri"))
        val folder = intent.getBooleanExtra("folder", false)
        val resume = intent.getBooleanExtra("resume", false)

        io.execute {
            try {
                val s = when {
                    webUrls != null -> WebSource(this, webUrls.toList(), referer, ua, referers)
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

        // Barra superior: voltar, contador, tamanho e direção de leitura
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
        topBar.addView(textBtn("⤢") { toggleSizePanel() })
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

        buildSizePanel(root)

        // Área útil: desconta recortes de tela (e barras do sistema, se estiverem visíveis)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, wi ->
            val i = wi.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            insets = intArrayOf(i.left, i.top, i.right, i.bottom)
            pushSizing()
            wi
        }

        setContentView(root)
    }

    private fun buildSizePanel(root: FrameLayout) {
        sizePanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F2241C3A"))
                cornerRadius = dp(16).toFloat()
            }
            setPadding(dp(14), dp(10), dp(14), dp(10))
            layoutParams = FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply {
                leftMargin = dp(12); rightMargin = dp(12); bottomMargin = dp(84)
            }
            elevation = dp(8).toFloat()
            isClickable = true
            visibility = View.GONE
        }

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        btnAuto = chip("Automático") {
            sizing = Sizing(Sizing.AUTO, sizeLevel)
            sizeChanged(true)
        }
        btnOriginal = chip("Tamanho original") {
            sizeLevel = 0
            sizing = Sizing(Sizing.ORIGINAL, 0)
            sizeChanged(true)
        }
        row.addView(btnAuto)
        row.addView(btnOriginal)
        sizePanel.addView(row)

        val sliderRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            setPadding(0, dp(6), 0, 0)
        }
        sizeSeek = SeekBar(this).apply {
            max = 100
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                    if (!fromUser || updatingPanel) return
                    sizeLevel = p
                    sizing = Sizing(Sizing.MANUAL, p)
                    sizeChanged(false)
                }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) { saveSizing() }
            })
        }
        sizeValue = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 15f
            gravity = Gravity.END
            minimumWidth = dp(36)
        }
        sliderRow.addView(sizeSeek)
        sliderRow.addView(sizeValue)
        sizePanel.addView(sliderRow)
        root.addView(sizePanel)
        updatePanel()
    }

    private fun chip(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label
        setTextColor(Color.WHITE)
        textSize = 14f
        gravity = Gravity.CENTER
        setPadding(dp(14), dp(8), dp(14), dp(8))
        layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(6) }
        setOnClickListener { onClick() }
    }

    private fun chipBg(selected: Boolean) = GradientDrawable().apply {
        setColor(Color.parseColor(if (selected) "#E94F8B" else "#3A3058"))
        cornerRadius = dp(12).toFloat()
    }

    private fun updatePanel() {
        updatingPanel = true
        val isOriginal = sizing.mode == Sizing.ORIGINAL || (sizing.mode == Sizing.MANUAL && sizing.level == 0)
        btnAuto.background = chipBg(sizing.mode == Sizing.AUTO)
        btnOriginal.background = chipBg(isOriginal)
        val shown = if (sizing.mode == Sizing.ORIGINAL) 0 else sizeLevel
        sizeSeek.progress = shown
        sizeSeek.alpha = if (sizing.mode == Sizing.AUTO) 0.5f else 1f
        sizeValue.text = shown.toString()
        updatingPanel = false
    }

    private fun sizeChanged(save: Boolean) {
        updatePanel()
        pushSizing()
        if (save) saveSizing()
    }

    private fun saveSizing() {
        prefs.edit().putInt("size_mode", sizing.mode).putInt("size_level", sizeLevel).apply()
    }

    /** Reaplica o ajuste nas páginas visíveis (só recalcula a matriz, sem recarregar imagem). */
    private fun pushSizing() {
        val a = adapter ?: return
        a.sizing = sizing
        a.insets = insets
        val rv = pager.getChildAt(0) as? RecyclerView ?: return
        for (i in 0 until rv.childCount) {
            (rv.getChildAt(i) as? ZoomImageView)?.configure(sizing, insets[0], insets[1], insets[2], insets[3])
        }
    }

    private fun toggleSizePanel() {
        sizePanel.visibility = if (sizePanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
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
        val a = PageAdapter(s, io, ui, dm.widthPixels * 2, dm.heightPixels * 2) { handleTap(it) }
        a.sizing = sizing
        a.insets = insets
        adapter = a
        pager.adapter = a
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
        if (!overlayVisible) sizePanel.visibility = View.GONE
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

    var sizing = Sizing(Sizing.ORIGINAL, 0)
    var insets = intArrayOf(0, 0, 0, 0)

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
        holder.img.configure(sizing, insets[0], insets[1], insets[2], insets[3])
        holder.img.tag = position
        io.execute {
            val r = decode(position)
            ui.post {
                if (holder.img.tag == position && r != null) {
                    holder.img.sourceScale = r.second
                    holder.img.configure(sizing, insets[0], insets[1], insets[2], insets[3])
                    holder.img.setImageDrawable(android.graphics.drawable.BitmapDrawable(holder.img.resources, r.first))
                }
            }
        }
    }

    override fun onViewRecycled(holder: VH) {
        holder.img.tag = -1
        holder.img.setImageDrawable(null)
        holder.img.sourceScale = 1
    }

    /** Devolve o bitmap e o fator de redução usado, para que "tamanho original" seja o do arquivo. */
    private fun decode(i: Int): Pair<Bitmap, Int>? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        src.open(i).use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxW && bounds.outHeight / (sample * 2) >= maxH) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = src.open(i).use { BitmapFactory.decodeStream(it, null, opts) }
        if (bmp == null) null else bmp to sample
    } catch (e: Throwable) {
        null
    }
}
