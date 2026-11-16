package com.mangler.reader

import android.annotation.SuppressLint
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
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
import org.json.JSONArray
import org.json.JSONTokener
import java.net.URLEncoder
import java.util.UUID

/**
 * Mini-navegador (busca pelo Google) para achar mangá online.
 * Ao abrir um capítulo aparece um aviso: "Abrir em tela cheia" leva as páginas
 * para o leitor, com efeito de virar página.
 */
class BrowserActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var web: WebView
    private lateinit var urlBox: EditText
    private lateinit var progress: ProgressBar
    private lateinit var prompt: LinearLayout
    private lateinit var backBtn: TextView
    private lateinit var fwdBtn: TextView

    private val ui = Handler(Looper.getMainLooper())
    private val home = "https://www.google.com"

    private var suggest = true
    private var extracting = false
    private var token = ""
    private var lastHandledUrl: String? = null

    // Importação de galerias com paginação numérica
    private var importer: GalleryImporter? = null
    private var importDialog: ImportProgressDialog? = null
    private var importId = 0
    private var probing = false

    // Heurística: URLs que parecem página de leitura de capítulo
    private val chapterRegex = Regex(
        "(/|-|_)(chapter|chapters|capitulo|capitulos|chapitre|episodio|episode|read|ler|viewer|reader)(/|-|_|\\d|\\?|$)" +
            "|/(cap|ch|c)[-_]?\\d+"
    )

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("mangler", MODE_PRIVATE)
        suggest = prefs.getBoolean("browser_suggest", true)

        buildUi()
        configureWebView()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (web.canGoBack()) web.goBack() else finish()
            }
        })

        web.loadUrl(intent.getStringExtra("url") ?: prefs.getString("browser_last_url", null) ?: home)
    }

    // ---------- UI ----------

    private fun buildUi() {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(-1, -1)
        }

        val bar = LinearLayout(this).apply {
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
            hint = "Pesquisar no Google ou digitar endereço"
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

        bar.addView(barBtn("📖") { startReader() })
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

        // Aviso flutuante: abrir o capítulo no leitor em tela cheia
        prompt = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F2241C3A"))
                cornerRadius = dp(16).toFloat()
            }
            setPadding(dp(14), dp(8), dp(8), dp(8))
            layoutParams = FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply {
                leftMargin = dp(12); rightMargin = dp(12); bottomMargin = dp(20)
            }
            elevation = dp(8).toFloat()
            visibility = View.GONE
        }
        prompt.addView(TextView(this).apply {
            text = "Abrir em tela cheia com virar página?"
            setTextColor(Color.WHITE)
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        })
        prompt.addView(TextView(this).apply {
            text = "Abrir"
            setTextColor(Color.WHITE)
            textSize = 14f
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#E94F8B"))
                cornerRadius = dp(12).toFloat()
            }
            setPadding(dp(16), dp(8), dp(16), dp(8))
            setOnClickListener { startReader() }
        })
        prompt.addView(TextView(this).apply {
            text = "✕"
            setTextColor(Color.parseColor("#B8B0D0"))
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(8), dp(14), dp(8))
            setOnClickListener { prompt.visibility = View.GONE }
        })
        root.addView(prompt)

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
        web.addJavascriptInterface(Bridge(), "ManglerBridge")

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

    private fun onUrlChanged(url: String) {
        if (!urlBox.hasFocus()) urlBox.setText(url)
        backBtn.alpha = if (web.canGoBack()) 1f else 0.35f
        fwdBtn.alpha = if (web.canGoForward()) 1f else 0.35f
        prefs.edit().putString("browser_last_url", url).apply()

        if (url == lastHandledUrl) return
        lastHandledUrl = url
        val isChapter = chapterRegex.containsMatchIn(url.lowercase())
        prompt.visibility = if (suggest && isChapter) View.VISIBLE else View.GONE
    }

    private fun go(input: String) {
        val q = input.trim()
        if (q.isEmpty()) return
        val url = when {
            q.startsWith("http://") || q.startsWith("https://") -> q
            !q.contains(' ') && q.contains('.') -> "https://$q"
            else -> "https://www.google.com/search?q=" + URLEncoder.encode(q, "UTF-8")
        }
        urlBox.clearFocus()
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(urlBox.windowToken, 0)
        web.loadUrl(url)
    }

    // ---------- Extrair páginas e abrir no leitor ----------

    private inner class Bridge {
        @JavascriptInterface
        fun onImages(t: String, json: String) {
            runOnUiThread {
                if (!extracting || t != token) return@runOnUiThread
                extracting = false
                val urls = ArrayList<String>()
                try {
                    val arr = JSONArray(json)
                    for (i in 0 until arr.length()) {
                        val u = arr.getString(i)
                        if (u.startsWith("http://") || u.startsWith("https://")) urls.add(u)
                    }
                } catch (_: Throwable) {}
                if (urls.isEmpty()) {
                    Toast.makeText(
                        this@BrowserActivity,
                        "Não achei páginas aqui. Abra um capítulo e tente de novo.",
                        Toast.LENGTH_LONG
                    ).show()
                    return@runOnUiThread
                }
                startActivity(
                    Intent(this@BrowserActivity, ReaderActivity::class.java)
                        .putExtra("urls", urls.toTypedArray())
                        .putExtra("referer", web.url ?: home)
                        .putExtra("ua", web.settings.userAgentString)
                )
            }
        }
    }

    /**
     * Botão de leitura: primeiro tenta o mecanismo de galeria (miniaturas + paginação numérica),
     * usando o HTML da página aberta. Se não for galeria, cai no extrator de capítulo.
     */
    private fun startReader() {
        if (extracting || importer != null || probing) return
        val pageUrl = web.url ?: return
        prompt.visibility = View.GONE
        probing = true
        web.evaluateJavascript("(function(){return document.documentElement.outerHTML;})()") { res ->
            probing = false
            val html = decodeJsString(res)
            if (html.isNullOrEmpty()) startJsExtraction() else startGallery(pageUrl, html)
        }
    }

    private fun decodeJsString(res: String?): String? {
        if (res == null || res == "null") return null
        return try { JSONTokener(res).nextValue() as? String } catch (_: Throwable) { null }
    }

    private fun startGallery(pageUrl: String, html: String) {
        val id = ++importId
        val ua = web.settings.userAgentString
        fun alive() = id == importId && importer != null && !isFinishing && !isDestroyed

        val imp = GalleryImporter(ua, object : GalleryImporter.Listener {
            override fun onStatus(s: GalleryImporter.Status) {
                ui.post {
                    if (!alive()) return@post
                    if (importDialog == null) {
                        importDialog = ImportProgressDialog(this@BrowserActivity) { cancelImport() }
                        importDialog?.show()
                    }
                    importDialog?.update(s)
                }
            }

            override fun onDone(r: GalleryImporter.ImportResult) {
                ui.post {
                    if (!alive()) return@post
                    finishImport()
                    val expected = r.expected
                    if (r.failedImages > 0 || r.failedPages.isNotEmpty() || (expected != null && r.urls.size < expected)) {
                        val msg = buildString {
                            append("Importadas ${r.urls.size}")
                            if (expected != null) append(" de $expected")
                            append(" imagens")
                            if (r.failedImages > 0) append(" · ${r.failedImages} falharam")
                            if (r.failedPages.isNotEmpty()) append(" · páginas ${r.failedPages.joinToString()} indisponíveis")
                        }
                        Toast.makeText(this@BrowserActivity, msg, Toast.LENGTH_LONG).show()
                    }
                    startActivity(
                        Intent(this@BrowserActivity, ReaderActivity::class.java)
                            .putExtra("urls", r.urls.toTypedArray())
                            .putExtra("referers", r.referers.toTypedArray())
                            .putExtra("referer", pageUrl)
                            .putExtra("ua", ua)
                    )
                }
            }

            override fun onFailed(message: String) {
                ui.post {
                    if (!alive()) return@post
                    finishImport()
                    AlertDialog.Builder(this@BrowserActivity)
                        .setTitle("Não consegui importar a galeria")
                        .setMessage(message)
                        .setPositiveButton("OK", null)
                        .show()
                }
            }

            override fun onNotGallery() {
                ui.post {
                    if (!alive()) return@post
                    finishImport()
                    startJsExtraction()
                }
            }
        })
        importer = imp
        imp.start(pageUrl, html)
    }

    private fun cancelImport() {
        importer?.cancel()
        finishImport()
        Toast.makeText(this, "Importação cancelada", Toast.LENGTH_SHORT).show()
    }

    private fun finishImport() {
        importDialog?.dismiss()
        importDialog = null
        importer = null
    }

    private fun startJsExtraction() {
        if (extracting) return
        extracting = true
        token = UUID.randomUUID().toString()
        prompt.visibility = View.GONE
        Toast.makeText(this, "Carregando páginas do capítulo…", Toast.LENGTH_SHORT).show()

        val t = token
        ui.postDelayed({
            if (extracting && token == t) {
                extracting = false
                Toast.makeText(this, "Demorou demais para carregar as páginas", Toast.LENGTH_LONG).show()
            }
        }, 30000)

        web.evaluateJavascript(extractJs.replace("__TOKEN__", token), null)
    }

    // Rola a página para forçar o carregamento das imagens (lazy load) e coleta as páginas
    private val extractJs = """
        (function(){
          var TOKEN='__TOKEN__';
          function abs(u){try{return new URL(u,location.href).href}catch(e){return ''}}
          function pick(im){
            var c=[im.currentSrc,im.src,im.getAttribute('data-src'),im.getAttribute('data-lazy-src'),im.getAttribute('data-original'),im.getAttribute('data-url')];
            for(var i=0;i<c.length;i++){
              var u=c[i];
              if(u&&u.indexOf('data:')!==0&&u.indexOf('blob:')!==0){
                var a=abs(u);
                if(a.indexOf('http')===0) return a;
              }
            }
            return '';
          }
          function collect(){
            var imgs=document.images, items=[], maxW=0;
            for(var i=0;i<imgs.length;i++){
              var im=imgs[i], u=pick(im);
              if(!u) continue;
              var r=im.getBoundingClientRect();
              var w=r.width||im.naturalWidth, h=r.height||im.naturalHeight;
              if(w<200||h<200) continue;
              items.push({u:u,w:w});
              if(w>maxW) maxW=w;
            }
            var seen={}, out=[];
            for(var j=0;j<items.length;j++){
              if(items[j].w>=maxW*0.6 && !seen[items[j].u]){seen[items[j].u]=1;out.push(items[j].u);}
            }
            return out;
          }
          var steps=0, y=window.scrollY||0;
          var timer=setInterval(function(){
            steps++;
            y+=Math.max(window.innerHeight*0.9,300);
            window.scrollTo(0,y);
            var H=Math.max(document.documentElement.scrollHeight, document.body?document.body.scrollHeight:0);
            if(y>=H+200||steps>=60){
              clearInterval(timer);
              setTimeout(function(){
                window.scrollTo(0,0);
                ManglerBridge.onImages(TOKEN, JSON.stringify(collect()));
              },800);
            }
          },250);
        })();
    """.trimIndent()

    // ---------- Menu e favoritos ----------

    private fun showMenu(anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add(0, 1, 0, "Início (Google)")
            menu.add(0, 2, 1, "Recarregar")
            menu.add(0, 3, 2, "Favoritar esta página")
            menu.add(0, 4, 3, "Favoritos")
            menu.add(0, 5, 4, "Sugerir tela cheia ao abrir capítulo").apply {
                isCheckable = true
                isChecked = suggest
            }
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> web.loadUrl(home)
                    2 -> web.reload()
                    3 -> addFavorite()
                    4 -> showFavorites()
                    5 -> {
                        suggest = !suggest
                        prefs.edit().putBoolean("browser_suggest", suggest).apply()
                        if (!suggest) prompt.visibility = View.GONE
                        Toast.makeText(
                            this@BrowserActivity,
                            if (suggest) "Aviso de tela cheia: ligado" else "Aviso de tela cheia: desligado",
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
        importer?.cancel()
        importer = null
        importDialog?.dismiss()
        ui.removeCallbacksAndMessages(null)
        web.removeJavascriptInterface("ManglerBridge")
        web.stopLoading()
        web.destroy()
        super.onDestroy()
    }
}
