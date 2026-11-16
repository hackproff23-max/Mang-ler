package com.mangler.reader

import android.annotation.SuppressLint
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.net.URLEncoder

/**
 * Mini-navegador para ler mangá online.
 * Ao abrir um capítulo (URL de leitura), a barra some e a tela fica cheia (imersivo).
 */
class BrowserActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var web: WebView
    private lateinit var bar: LinearLayout
    private lateinit var urlBox: EditText
    private lateinit var progress: ProgressBar
    private lateinit var exitFs: TextView
    private lateinit var backBtn: TextView
    private lateinit var fwdBtn: TextView

    private var fullscreen = false
    private var autoFs: Boolean = true

    private val home = "https://mangadex.org"

    // Heurística: URLs que parecem página de leitura de capítulo
    private val chapterRegex = Regex(
        "(/|-|_)(chapter|chapters|capitulo|capitulos|chapitre|episodio|episode|read|ler|viewer|reader)(/|-|_|\\d|\\?|$)" +
            "|/(cap|ch|c)[-_]?\\d+"
    )

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("mangler", MODE_PRIVATE)
        autoFs = prefs.getBoolean("browser_auto_fs", true)

        buildUi()
        configureWebView()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    web.canGoBack() -> web.goBack()
                    fullscreen -> setFullscreen(false)
                    else -> finish()
                }
            }
        })

        val start = intent.getStringExtra("url")
            ?: prefs.getString("browser_last_url", null)
            ?: home
        web.loadUrl(start)
    }

    // ---------- UI ----------

    private fun buildUi() {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(-1, -1)
        }

        bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#1E1830"))
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        backBtn = barBtn("‹") { if (web.canGoBack()) web.goBack() }
        fwdBtn = barBtn("›") { if (web.canGoForward()) web.goForward() }
        bar.addView(backBtn)
        bar.addView(fwdBtn)

        urlBox = EditText(this).apply {
            setSingleLine(true)
            textSize = 14f
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#8A82A8"))
            hint = "Buscar ou digitar endereço"
            imeOptions = EditorInfo.IME_ACTION_GO
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#2C2444"))
                cornerRadius = dp(18).toFloat()
            }
            setPadding(dp(14), dp(8), dp(14), dp(8))
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply {
                leftMargin = dp(4); rightMargin = dp(4)
            }
            setOnEditorActionListener { _, actionId, event ->
                if (actionId == EditorInfo.IME_ACTION_GO ||
                    (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER)
                ) {
                    go(text.toString())
                    true
                } else false
            }
            setOnFocusChangeListener { _, has -> if (has) post { selectAll() } }
        }
        bar.addView(urlBox)

        bar.addView(barBtn("⛶") { setFullscreen(true) })
        bar.addView(barBtn("⋮") { showMenu(it) })
        column.addView(bar)

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            layoutParams = LinearLayout.LayoutParams(-1, dp(3))
            progressDrawable.setColorFilter(Color.parseColor("#E94F8B"), android.graphics.PorterDuff.Mode.SRC_IN)
            visibility = View.GONE
        }
        column.addView(progress)

        web = WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
            setBackgroundColor(Color.BLACK)
        }
        column.addView(web)
        root.addView(column)

        // Botão flutuante para sair da tela cheia
        exitFs = TextView(this).apply {
            text = "✕"
            setTextColor(Color.WHITE)
            textSize = 16f
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0x66000000)
            }
            layoutParams = FrameLayout.LayoutParams(dp(36), dp(36), Gravity.TOP or Gravity.END).apply {
                topMargin = dp(10); rightMargin = dp(10)
            }
            alpha = 0.7f
            visibility = View.GONE
            setOnClickListener { setFullscreen(false) }
        }
        root.addView(exitFs)

        setContentView(root)
    }

    private fun barBtn(label: String, onClick: (View) -> Unit) = TextView(this).apply {
        text = label
        setTextColor(Color.WHITE)
        textSize = 20f
        gravity = Gravity.CENTER
        minimumWidth = dp(40)
        setPadding(dp(6), dp(6), dp(6), dp(6))
        setOnClickListener { onClick(it) }
    }

    // ---------- WebView ----------

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            setSupportMultipleWindows(false)
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            mediaPlaybackRequiresUserGesture = true
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(web, true)
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progress.progress = newProgress
                progress.visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
            }
        }

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u = request.url
                val scheme = u.scheme ?: return false
                if (scheme == "http" || scheme == "https") return false
                // Esquemas externos (intent://, market://, mailto: ...)
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, u))
                } catch (_: Throwable) {
                    Toast.makeText(this@BrowserActivity, "Não consegui abrir o link", Toast.LENGTH_SHORT).show()
                }
                return true
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                onUrlChanged(url)
            }

            // Sites SPA (ex.: MangaDex) trocam de página sem recarregar
            override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                onUrlChanged(url)
            }

            override fun onPageFinished(view: WebView, url: String) {
                onUrlChanged(url)
            }
        }
    }

    private var lastHandledUrl: String? = null

    private fun onUrlChanged(url: String) {
        if (!urlBox.hasFocus()) urlBox.setText(url)
        backBtn.alpha = if (web.canGoBack()) 1f else 0.35f
        fwdBtn.alpha = if (web.canGoForward()) 1f else 0.35f
        prefs.edit().putString("browser_last_url", url).apply()

        if (url == lastHandledUrl) return
        lastHandledUrl = url
        if (autoFs) setFullscreen(chapterRegex.containsMatchIn(url.lowercase()))
    }

    private fun go(input: String) {
        val q = input.trim()
        if (q.isEmpty()) return
        val url = when {
            q.startsWith("http://") || q.startsWith("https://") -> q
            !q.contains(' ') && q.contains('.') -> "https://$q"
            else -> "https://duckduckgo.com/?q=" + URLEncoder.encode(q, "UTF-8")
        }
        urlBox.clearFocus()
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(urlBox.windowToken, 0)
        web.loadUrl(url)
    }

    // ---------- Tela cheia ----------

    private fun setFullscreen(on: Boolean) {
        fullscreen = on
        bar.visibility = if (on) View.GONE else View.VISIBLE
        exitFs.visibility = if (on) View.VISIBLE else View.GONE
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        WindowCompat.setDecorFitsSystemWindows(window, !on)
        val c = WindowCompat.getInsetsController(window, window.decorView)
        if (on) {
            c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            c.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            c.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && fullscreen) {
            WindowCompat.getInsetsController(window, window.decorView)
                .hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    // ---------- Menu e favoritos ----------

    private fun showMenu(anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add(0, 1, 0, "Início")
            menu.add(0, 2, 1, "Recarregar")
            menu.add(0, 3, 2, "Favoritar esta página")
            menu.add(0, 4, 3, "Favoritos")
            menu.add(0, 5, 4, "Tela cheia ao abrir capítulo").apply {
                isCheckable = true
                isChecked = autoFs
            }
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> web.loadUrl(home)
                    2 -> web.reload()
                    3 -> addFavorite()
                    4 -> showFavorites()
                    5 -> {
                        autoFs = !autoFs
                        prefs.edit().putBoolean("browser_auto_fs", autoFs).apply()
                        Toast.makeText(
                            this@BrowserActivity,
                            if (autoFs) "Tela cheia automática: ligada" else "Tela cheia automática: desligada",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
                true
            }
            show()
        }
    }

    private fun favorites(): MutableList<Pair<String, String>> {
        val raw = prefs.getString("browser_favs", "") ?: ""
        if (raw.isEmpty()) return mutableListOf()
        return raw.split('\n').mapNotNull {
            val i = it.indexOf('\t')
            if (i <= 0) null else it.substring(0, i) to it.substring(i + 1)
        }.toMutableList()
    }

    private fun saveFavorites(list: List<Pair<String, String>>) {
        prefs.edit().putString("browser_favs", list.joinToString("\n") { "${it.first}\t${it.second}" }).apply()
    }

    private fun addFavorite() {
        val url = web.url ?: return
        val title = (web.title?.takeIf { it.isNotBlank() } ?: url).replace('\n', ' ').replace('\t', ' ')
        val list = favorites()
        if (list.none { it.second == url }) {
            list.add(title to url)
            saveFavorites(list)
        }
        Toast.makeText(this, "Favoritado", Toast.LENGTH_SHORT).show()
    }

    private fun showFavorites() {
        val list = favorites()
        if (list.isEmpty()) {
            Toast.makeText(this, "Nenhum favorito ainda", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Favoritos (segure para remover)")
            .setItems(list.map { it.first }.toTypedArray()) { _, i -> web.loadUrl(list[i].second) }
            .setNegativeButton("Fechar", null)
            .create().also { dlg ->
                dlg.show()
                dlg.listView.setOnItemLongClickListener { _, _, pos, _ ->
                    val l = favorites()
                    if (pos in l.indices) {
                        l.removeAt(pos)
                        saveFavorites(l)
                    }
                    dlg.dismiss()
                    Toast.makeText(this, "Removido", Toast.LENGTH_SHORT).show()
                    true
                }
            }
    }

    // ---------- Ciclo de vida ----------

    override fun onPause() {
        super.onPause()
        web.onPause()
        CookieManager.getInstance().flush()
    }

    override fun onResume() {
        super.onResume()
        web.onResume()
    }

    override fun onDestroy() {
        web.stopLoading()
        web.destroy()
        super.onDestroy()
    }
}
