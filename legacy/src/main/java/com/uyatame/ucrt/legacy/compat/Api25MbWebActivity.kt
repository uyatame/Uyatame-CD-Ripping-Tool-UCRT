package com.uyatame.ucrt.legacy.compat

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import com.uyatame.ucrt.R
import com.uyatame.ucrt.S
import com.uyatame.ucrt.meta.MusicBrainz
import java.io.File

/**
 * MusicBrainz のリリースの登録画面を、曲情報を入れた状態で開く(アプリの中のブラウザ。本家の MusicBrainzSubmitActivity)。
 * Android 7.1 から(それより前は Let's Encrypt の証明書を信頼していないので、端末のブラウザで開く)。
 * ログインと送信は利用者。ジャケット画像があれば、登録のあとに画像の追加のページへ進み、その画像を渡す
 */
class Api25MbWebActivity : Activity() {
    private lateinit var web: WebView
    private var cover: File? = null
    private var pending: ValueCallback<Array<Uri>>? = null
    private var seedHtml = ""
    private var seeded = false
    private var jumped = false
    private lateinit var bar: ProgressBar

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val html = intent.getStringExtra(EXTRA_HTML)
        if (html == null) { finish(); return }
        seedHtml = html
        cover = intent.getStringExtra(EXTRA_COVER)?.let { File(it) }?.takeIf { it.exists() }
        web = WebView(this)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        CookieManager.getInstance().setAcceptCookie(true)
        web.webViewClient = object : WebViewClient() {
            @Deprecated("Deprecated in Java")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean = intercept(url)
            override fun onPageFinished(view: WebView, url: String) = pageFinished(url)
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean = chooseFile(callback, params)
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                bar.progress = newProgress
                bar.visibility = if (newProgress < 100) android.view.View.VISIBLE else android.view.View.INVISIBLE
            }
        }
        val dp = resources.displayMetrics.density
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding((16 * dp).toInt(), (4 * dp).toInt(), (4 * dp).toInt(), (4 * dp).toInt())
            addView(TextView(context).apply { text = S(R.string.mb_upload); textSize = 18f }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(android.widget.Button(context, null, android.R.attr.borderlessButtonStyle).apply {
                text = S(R.string.close)
                setOnClickListener { finish() }
            })
        }
        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            fitsSystemWindows = true
            addView(top)
            addView(bar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (3 * dp).toInt()))
            addView(web, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        setContentView(root)
        if (savedInstanceState == null) web.loadUrl("https://musicbrainz.org/") else web.restoreState(savedInstanceState)
    }

    private fun intercept(url: String): Boolean {
        if (url.contains(READY_PATH)) { seed(); return true }
        if (!url.startsWith(MusicBrainz.DONE_URL)) return false
        val id = Uri.parse(url).getQueryParameter("release_mbid")
        if (id.isNullOrBlank()) { finish(); return true }
        if (cover != null) {
            Toast.makeText(this, S(R.string.mb_cover_next), Toast.LENGTH_LONG).show()
            web.loadUrl(MusicBrainz.addCoverArtUrl(id))
        } else web.loadUrl(MusicBrainz.releaseUrl(id))
        return true
    }

    private fun seed() {
        if (seeded) return
        seeded = true
        web.loadDataWithBaseURL("https://musicbrainz.org/", seedHtml, "text/html", "utf-8", null)
    }

    private fun pageFinished(url: String) {
        when {
            !seeded && url.trimEnd('/') == "https://musicbrainz.org" -> {
                web.evaluateJavascript("(function(){return !!document.querySelector('a[href^=\"/logout\"],a[href*=\"musicbrainz.org/logout\"]')})()") { r ->
                    if (r == "true") seed() else {
                        Toast.makeText(this, S(R.string.mb_login_first), Toast.LENGTH_LONG).show()
                        web.loadUrl("https://musicbrainz.org/login?returnto=" + Uri.encode(READY_PATH))
                    }
                }
            }
            seeded && !jumped && url.contains("/release/add") -> {
                jumped = true
                web.evaluateJavascript("setTimeout(function(){var a=document.querySelector('a[href=\"#edit-note\"]');if(a){a.click();window.scrollTo(0,0);}},800)", null)
            }
        }
    }

    private fun chooseFile(callback: ValueCallback<Array<Uri>>, params: WebChromeClient.FileChooserParams): Boolean {
        val c = cover
        if (c != null && web.url?.contains("/add-cover-art") == true) {
            callback.onReceiveValue(arrayOf(Uri.fromFile(c)))
            return true
        }
        pending?.onReceiveValue(null)
        pending = callback
        return try {
            @Suppress("DEPRECATION")
            startActivityForResult(params.createIntent(), REQUEST_FILE)
            true
        } catch (e: Exception) {
            pending = null
            false
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_FILE) return
        pending?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data))
        pending = null
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::web.isInitialized) web.saveState(outState)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (::web.isInitialized && !seeded && web.canGoBack()) web.goBack() else finish()
    }

    override fun onDestroy() {
        if (::web.isInitialized) web.destroy()
        if (isFinishing) cover?.delete()
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_HTML = "html"
        private const val EXTRA_COVER = "cover"
        private const val READY_PATH = "/ucrt-login-done"
        private const val REQUEST_FILE = 1

        fun open(ctx: android.content.Context, html: String, cover: ByteArray?) {
            val i = Intent(ctx, Api25MbWebActivity::class.java).putExtra(EXTRA_HTML, html)
            if (cover != null && cover.isNotEmpty()) {
                val ext = when {
                    cover.size > 3 && cover[0] == 0x89.toByte() && cover[1] == 'P'.code.toByte() -> "png"
                    cover.size > 3 && cover[0] == 'G'.code.toByte() && cover[1] == 'I'.code.toByte() -> "gif"
                    else -> "jpg"
                }
                val dir = File(ctx.cacheDir, "mb-cover").apply { mkdirs() }
                dir.listFiles()?.forEach { it.delete() }
                val f = File(dir, "cover.$ext")
                runCatching { f.writeBytes(cover) }.onSuccess { i.putExtra(EXTRA_COVER, f.path) }
            }
            ctx.startActivity(i)
        }
    }
}
