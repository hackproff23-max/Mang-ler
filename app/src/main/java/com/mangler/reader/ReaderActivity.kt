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
import android.view.View.MeasureSpec
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
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

    // Painel "Qualidade e carregamento de imagens"
    private lateinit var qualityPanel: View
    private lateinit var btnOrigFirst: TextView
    private lateinit var btnReducedFirst: TextView
    private lateinit var modeDesc: TextView
    private lateinit var reduceBox: LinearLayout
    private lateinit var reduceSeek: SeekBar
    private lateinit var reduceValue: TextView
    private lateinit var btnPreload: TextView
    private lateinit var preloadSeek: SeekBar
    private lateinit var preloadLabel: TextView
    private lateinit var btnOriginalNow: TextView
    private lateinit var hdBadge: TextView
    private var settings = LoadSettings()
    private var loader: PageLoader? = null

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
        settings = LoadSettings.load(prefs)
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
        topBar.addView(textBtn("⚙") { toggleQualityPanel() })
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
        buildQualityPanel(root)
        hdBadge = TextView(this).apply {
            text = "Carregando original…"
            setTextColor(Color.WHITE)
            textSize = 12f
            background = GradientDrawable().apply { setColor(0xB3000000.toInt()); cornerRadius = dp(12).toFloat() }
            setPadding(dp(12), dp(5), dp(12), dp(5))
            layoutParams = FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(40) }
            visibility = View.GONE
        }
        root.addView(hdBadge)

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

    // ---------- Qualidade e carregamento de imagens ----------

    private fun buildQualityPanel(root: FrameLayout) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        box.addView(TextView(this).apply {
            text = "Qualidade e carregamento de imagens"
            setTextColor(Color.WHITE)
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, dp(8))
        })
        val modes = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        btnOrigFirst = chip("Original primeiro") { settings = settings.copy(reducedFirst = false); settingsChanged() }
        btnReducedFirst = chip("Reduzida primeiro") { settings = settings.copy(reducedFirst = true); settingsChanged() }
        modes.addView(btnOrigFirst)
        modes.addView(btnReducedFirst)
        box.addView(modes)
        modeDesc = note("")
        box.addView(modeDesc)

        reduceBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val rr = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }
        rr.addView(TextView(this).apply { text = "Redução"; setTextColor(Color.WHITE); textSize = 14f })
        reduceSeek = SeekBar(this).apply {
            max = 100
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                    if (!fromUser || updatingPanel) return
                    settings = settings.copy(reducePct = p)
                    reduceValue.text = "$p%"
                }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) { settingsChanged() }
            })
        }
        reduceValue = TextView(this).apply { setTextColor(Color.WHITE); textSize = 14f; gravity = Gravity.END; minimumWidth = dp(40) }
        rr.addView(reduceSeek)
        rr.addView(reduceValue)
        reduceBox.addView(rr)
        reduceBox.addView(note("0% = resolução original · 100% = redução máxima. Vale para as próximas páginas carregadas."))
        box.addView(reduceBox)

        val pr = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            setPadding(0, dp(8), 0, 0)
        }
        btnPreload = chip("") { settings = settings.copy(preload = !settings.preload); settingsChanged() }
        preloadSeek = SeekBar(this).apply {
            max = 2
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                    if (!fromUser || updatingPanel) return
                    settings = settings.copy(preloadCount = p + 1)
                    preloadLabel.text = "${p + 1} pág."
                }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) { settingsChanged() }
            })
        }
        preloadLabel = TextView(this).apply { setTextColor(Color.WHITE); textSize = 14f; gravity = Gravity.END; minimumWidth = dp(52) }
        pr.addView(btnPreload)
        pr.addView(preloadSeek)
        pr.addView(preloadLabel)
        box.addView(pr)
        box.addView(note("Prepara as próximas páginas em segundo plano (menos em aparelhos com pouca memória)."))

        btnOriginalNow = chip("") { adapter?.toggleOriginal(pager.currentItem) }.apply {
            (layoutParams as LinearLayout.LayoutParams).apply { topMargin = dp(8); width = -1 }
        }
        box.addView(btnOriginalNow)

        qualityPanel = MaxHeightScroll(this, (resources.displayMetrics.heightPixels * 0.6f).toInt()).apply {
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F2241C3A"))
                cornerRadius = dp(16).toFloat()
            }
            elevation = dp(8).toFloat()
            isClickable = true
            layoutParams = FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply {
                leftMargin = dp(12); rightMargin = dp(12); bottomMargin = dp(84)
            }
            visibility = View.GONE
            addView(box)
        }
        root.addView(qualityPanel)
        updateQualityPanel()
    }

    private fun note(s: String) = TextView(this).apply {
        text = s
        setTextColor(Color.parseColor("#B8B0D0"))
        textSize = 12f
        setPadding(0, dp(4), 0, 0)
    }

    private fun updateQualityPanel() {
        updatingPanel = true
        btnOrigFirst.background = chipBg(!settings.reducedFirst)
        btnReducedFirst.background = chipBg(settings.reducedFirst)
        modeDesc.text = if (settings.reducedFirst)
            "Mostra uma versão menor primeiro: mais rápido e usa menos memória. A original só é carregada ao ampliar ou pelo botão abaixo."
        else
            "Carrega a página direto na qualidade máxima. Usa mais memória e pode demorar mais."
        reduceBox.visibility = if (settings.reducedFirst) View.VISIBLE else View.GONE
        reduceSeek.progress = settings.reducePct
        reduceValue.text = "${settings.reducePct}%"
        btnPreload.text = if (settings.preload) "Pré-carregar: sim" else "Pré-carregar: não"
        btnPreload.background = chipBg(settings.preload)
        preloadSeek.progress = settings.preloadCount - 1
        preloadSeek.isEnabled = settings.preload
        preloadSeek.alpha = if (settings.preload) 1f else 0.4f
        preloadLabel.text = "${settings.preloadCount} pág."
        updatingPanel = false
        updateOriginalButton()
    }

    private fun updateOriginalButton() {
        val a = adapter
        val pos = pager.currentItem
        when {
            !settings.reducedFirst -> {
                btnOriginalNow.text = "Esta página já está em qualidade original"
                btnOriginalNow.alpha = 0.5f
            }
            a != null && a.isLoadingOriginal(pos) -> {
                btnOriginalNow.text = "Carregando original…"
                btnOriginalNow.alpha = 0.5f
            }
            a != null && a.isOriginal(pos) -> {
                btnOriginalNow.text = "Voltar à versão reduzida"
                btnOriginalNow.alpha = 1f
            }
            else -> {
                btnOriginalNow.text = "Carregar original desta página"
                btnOriginalNow.alpha = 1f
            }
        }
        btnOriginalNow.background = chipBg(false)
    }

    private fun settingsChanged() {
        settings.save(prefs)
        adapter?.settings = settings
        updateQualityPanel()
        // vale para as próximas páginas; refaz o pré-carregamento com a nova configuração
        loader?.preload(settings, pager.currentItem)
    }

    private fun onOriginalState(pos: Int, loading: Boolean) {
        if (pos == pager.currentItem) hdBadge.visibility = if (loading) View.VISIBLE else View.GONE
        updateOriginalButton()
    }

    private fun toggleQualityPanel() {
        sizePanel.visibility = View.GONE
        if (qualityPanel.visibility == View.VISIBLE) qualityPanel.visibility = View.GONE
        else { updateQualityPanel(); qualityPanel.visibility = View.VISIBLE }
    }

    private fun toggleSizePanel() {
        qualityPanel.visibility = View.GONE
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
        val l = PageLoader(this, s, ui)
        loader = l
        val a = PageAdapter(s, l, { handleTap(it) }) { pos, loading -> onOriginalState(pos, loading) }
        a.sizing = sizing
        a.insets = insets
        a.settings = settings
        adapter = a
        pager.adapter = a
        seek.max = s.count - 1
        val start = startPage.coerceIn(0, s.count - 1)
        l.current = start
        pager.setCurrentItem(start, false)
        l.preload(settings, start)
        updateLabel(start)
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updateLabel(position)
                // página atual passa a ter prioridade; tarefas de páginas fora da janela são descartadas
                loader?.current = position
                loader?.preload(settings, position)
                hdBadge.visibility = if (adapter?.isLoadingOriginal(position) == true) View.VISIBLE else View.GONE
                updateOriginalButton()
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
        if (!overlayVisible) { sizePanel.visibility = View.GONE; qualityPanel.visibility = View.GONE }
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

    override fun onLowMemory() {
        super.onLowMemory()
        loader?.trim()
    }

    override fun onDestroy() {
        super.onDestroy()
        io.shutdownNow()
        loader?.shutdown()
        try { source?.close() } catch (_: Throwable) {}
    }
}

/** ScrollView com altura máxima (o painel de qualidade rola em telas pequenas). */
class MaxHeightScroll(ctx: android.content.Context, private val maxH: Int) : ScrollView(ctx) {
    override fun onMeasure(w: Int, h: Int) {
        super.onMeasure(w, MeasureSpec.makeMeasureSpec(maxH, MeasureSpec.AT_MOST))
    }
}

class PageAdapter(
    private val src: PageSource,
    private val loader: PageLoader,
    private val onTap: (Float) -> Unit,
    private val onOriginalState: (Int, Boolean) -> Unit
) : RecyclerView.Adapter<PageAdapter.VH>() {

    var sizing = Sizing(Sizing.ORIGINAL, 0)
    var insets = intArrayOf(0, 0, 0, 0)
    var settings = LoadSettings()

    // páginas em que a original foi pedida (zoom ou botão) e as que ainda estão carregando
    private val originalPages = HashSet<Int>()
    private val loadingOriginal = HashSet<Int>()
    private val bound = HashMap<Int, VH>()

    class VH(val img: ZoomImageView) : RecyclerView.ViewHolder(img)

    fun isOriginal(pos: Int) = !settings.reducedFirst || pos in originalPages
    fun isLoadingOriginal(pos: Int) = pos in loadingOriginal

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = ZoomImageView(parent.context)
        v.layoutParams = ViewGroup.LayoutParams(-1, -1)
        v.onTap = onTap
        return VH(v)
    }

    override fun getItemCount() = src.count

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.img.setImageDrawable(null)
        holder.img.sourceScale = 1f
        holder.img.configure(sizing, insets[0], insets[1], insets[2], insets[3])
        holder.img.tag = position
        bound[position] = holder
        holder.img.onNeedOriginal = { if (!isOriginal(position)) requestOriginal(position) }

        val pct = if (isOriginal(position)) 0 else settings.reducePct
        loader.request(position, pct, object : PageLoader.Cb {
            override fun active() = holder.img.tag == position
            override fun onLoaded(l: Loaded) = show(holder, l)
            override fun onError() {}
        })
    }

    override fun onViewRecycled(holder: VH) {
        val pos = holder.img.tag as? Int
        holder.img.tag = -1
        holder.img.setImageDrawable(null)
        holder.img.sourceScale = 1f
        if (pos != null && pos >= 0) {
            if (bound[pos] === holder) bound.remove(pos)
            if (loadingOriginal.remove(pos)) onOriginalState(pos, false)
        }
    }

    /** Mostra a página; se já havia uma versão, troca mantendo zoom e posição. */
    private fun show(holder: VH, l: Loaded) {
        val d = android.graphics.drawable.BitmapDrawable(holder.img.resources, l.bmp)
        if (holder.img.drawable != null) {
            holder.img.swapDrawable(d, l.sourceScale)
        } else {
            holder.img.sourceScale = l.sourceScale
            holder.img.configure(sizing, insets[0], insets[1], insets[2], insets[3])
            holder.img.setImageDrawable(d)
        }
    }

    /** Pede a qualidade original da página (por zoom ou pelo botão). Não baixa nada antes disso. */
    fun requestOriginal(pos: Int) {
        if (pos in originalPages) return
        originalPages.add(pos)
        val holder = bound[pos] ?: return
        loadingOriginal.add(pos)
        onOriginalState(pos, true)
        loader.request(pos, 0, object : PageLoader.Cb {
            override fun active() = holder.img.tag == pos
            override fun onLoaded(l: Loaded) {
                loadingOriginal.remove(pos)
                show(holder, l)
                onOriginalState(pos, false)
            }
            override fun onError() {
                loadingOriginal.remove(pos)
                originalPages.remove(pos)
                onOriginalState(pos, false)
            }
        })
    }

    /** Botão do painel: carrega a original ou volta à reduzida (sem baixar de novo). */
    fun toggleOriginal(pos: Int) {
        if (!settings.reducedFirst || pos in loadingOriginal) return
        if (pos in originalPages) {
            originalPages.remove(pos)
            val holder = bound[pos] ?: return
            loader.request(pos, settings.reducePct, object : PageLoader.Cb {
                override fun active() = holder.img.tag == pos
                override fun onLoaded(l: Loaded) { show(holder, l); onOriginalState(pos, false) }
                override fun onError() {}
            })
        } else {
            requestOriginal(pos)
        }
    }
}
