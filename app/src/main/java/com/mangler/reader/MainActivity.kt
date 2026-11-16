package com.mangler.reader

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class OpenDoc : ActivityResultContracts.OpenDocument() {
    override fun createIntent(context: Context, input: Array<String>): Intent =
        super.createIntent(context, input).addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        )
}

class OpenTree : ActivityResultContracts.OpenDocumentTree() {
    override fun createIntent(context: Context, input: Uri?): Intent =
        super.createIntent(context, input).addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        )
}

class MainActivity : AppCompatActivity() {

    private lateinit var continueBtn: Button

    private val pickFile = registerForActivityResult(OpenDoc()) { uri ->
        if (uri != null) begin(uri, false)
    }
    private val pickFolder = registerForActivityResult(OpenTree()) { uri ->
        if (uri != null) begin(uri, true)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#14101F"))
            setPadding(dp(32), dp(32), dp(32), dp(32))
        }

        root.addView(ImageView(this).apply {
            setImageResource(R.mipmap.ic_launcher)
            layoutParams = LinearLayout.LayoutParams(dp(110), dp(110)).apply { bottomMargin = dp(16) }
        })
        root.addView(TextView(this).apply {
            text = "Mang-ler"
            setTextColor(Color.WHITE)
            textSize = 30f
            gravity = Gravity.CENTER
        })
        root.addView(TextView(this).apply {
            text = "Leitor de mangá em tela cheia"
            setTextColor(Color.parseColor("#B8B0D0"))
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(32))
        })

        continueBtn = button("Continuar lendo", "#E94F8B") {
            val uri = getSharedPreferences("mangler", MODE_PRIVATE).getString("last_uri", null)
            if (uri != null) start(Uri.parse(uri), lastIsFolder(), true)
        }
        root.addView(continueBtn)
        root.addView(button("Navegar e ler online", "#1FA37A") {
            startActivity(Intent(this, BrowserActivity::class.java))
        })
        root.addView(button("Abrir arquivo (CBZ / ZIP)", "#6C3CE9") { pickFile.launch(arrayOf("*/*")) })
        root.addView(button("Abrir pasta de imagens", "#3B2F6B") { pickFolder.launch(null) })

        root.addView(TextView(this).apply {
            text = "Toque no meio: menu · bordas: virar página\nPinça ou toque duplo: zoom\nNo navegador, o capítulo abre em tela cheia sozinho"
            setTextColor(Color.parseColor("#8A82A8"))
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(0, dp(28), 0, 0)
        })

        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        val has = getSharedPreferences("mangler", MODE_PRIVATE).getString("last_uri", null) != null
        continueBtn.visibility = if (has) View.VISIBLE else View.GONE
    }

    private fun lastIsFolder() =
        getSharedPreferences("mangler", MODE_PRIVATE).getBoolean("last_folder", false)

    private fun begin(uri: Uri, folder: Boolean) {
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Throwable) {}
        getSharedPreferences("mangler", MODE_PRIVATE).edit()
            .putString("last_uri", uri.toString())
            .putBoolean("last_folder", folder)
            .putInt("page", 0)
            .apply()
        start(uri, folder, false)
    }

    private fun start(uri: Uri, folder: Boolean, resume: Boolean) {
        startActivity(
            Intent(this, ReaderActivity::class.java)
                .putExtra("uri", uri.toString())
                .putExtra("folder", folder)
                .putExtra("resume", resume)
        )
    }

    private fun button(label: String, color: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setTextColor(Color.WHITE)
        textSize = 16f
        background = GradientDrawable().apply {
            setColor(Color.parseColor(color))
            cornerRadius = dp(14).toFloat()
        }
        layoutParams = LinearLayout.LayoutParams(-1, dp(52)).apply { bottomMargin = dp(12) }
        setOnClickListener { onClick() }
    }
}
