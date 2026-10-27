package com.uyatame.ucrt

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import com.uyatame.ucrt.meta.MusicBrainz
import java.io.File

/**
 * MusicBrainz のリリースの登録画面を、曲情報を入れた状態で開く(アプリの中のブラウザ)。
 * MusicBrainz にログインし、内容を確かめてから「Enter edit」で送信するのは利用者。アプリからは送信しない。
 * ジャケット画像があれば、リリースを登録したあとに画像の追加のページへ進み、画像を選ぶところでその画像を渡す
 * (Cover Art Archive には、アプリから画像を直接送る仕組みが無いため)
 */
class MusicBrainzSubmitActivity : Activity() {
    private lateinit var web: WebView
    /** 登録するジャケット画像(アプリのキャッシュに書いたもの)。無ければ null */
    private var cover: File? = null
    /** ファイルの選択を Android の画面に任せているときの、結果の渡し先 */
    private var pending: ValueCallback<Array<Uri>>? = null
    /** 曲情報を入れたページ(読み込むとすぐ登録画面へ送信する) */
    private var seedHtml = ""
    /** 登録画面を開いたか(ログインを確かめてから開く) */
    private var seeded = false
    /** 登録画面で、最後の確認のタブ(Edit note)に切り替えたか */
    private var jumped = false
    private lateinit var bar: android.widget.ProgressBar

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val html = intent.getStringExtra(EXTRA_HTML)
        if (html == null) {
            finish()
            return
        }
        seedHtml = html
        cover = intent.getStringExtra(EXTRA_COVER)?.let { File(it) }?.takeIf { it.exists() }
        web = WebView(this)
        // 登録画面は JavaScript で動く。ログインを保つため Cookie も使う
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        CookieManager.getInstance().setAcceptCookie(true)
        web.webViewClient = object : WebViewClient() {
            @Deprecated("Deprecated in Java")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean = intercept(url)

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                intercept(request.url.toString())

            override fun onPageFinished(view: WebView, url: String) = pageFinished(url)
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean =
                chooseFile(callback, params)

            override fun onProgressChanged(view: WebView, newProgress: Int) {
                bar.progress = newProgress
                bar.visibility = if (newProgress < 100) android.view.View.VISIBLE else android.view.View.INVISIBLE
            }
        }
        // 上の帯(題名と「閉じる」)・読み込みの進み具合・ページ。画面の端(ステータスバーなど)に重ならないように余白を取る
        val dp = resources.displayMetrics.density
        val top = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding((16 * dp).toInt(), (4 * dp).toInt(), (4 * dp).toInt(), (4 * dp).toInt())
            addView(
                android.widget.TextView(context).apply {
                    text = S(R.string.mb_upload)
                    textSize = 18f
                },
                android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
            )
            addView(android.widget.Button(context, null, android.R.attr.borderlessButtonStyle).apply {
                text = S(R.string.close)
                setOnClickListener { finish() }
            })
        }
        bar = android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        val root = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            fitsSystemWindows = true
            addView(top)
            addView(bar, android.widget.LinearLayout.LayoutParams(android.widget.LinearLayout.LayoutParams.MATCH_PARENT, (3 * dp).toInt()))
            addView(web, android.widget.LinearLayout.LayoutParams(android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        setContentView(root)
        // Android 13 からの戻る操作(アプリは新しい戻る操作の受け取り方にしている)。前のページがあればそこへ戻る
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT) {
                back()
            }
        }
        if (savedInstanceState == null) {
            // ログインしていないまま登録画面を開くと、ログインの途中で入れた曲情報が消えてしまうので、先にログインを確かめる
            web.loadUrl("https://musicbrainz.org/")
        } else {
            web.restoreState(savedInstanceState)
        }
    }

    /**
     * 登録画面で送信したあとの移り先(MusicBrainz.DONE_URL)を受け取る。
     * ジャケット画像があれば画像の追加のページへ、無ければ登録したリリースのページへ進む
     */
    private fun intercept(url: String): Boolean {
        // ログインが終わった(ログインのページに、戻り先としてこのアドレスを渡している)
        if (url.contains(READY_PATH)) {
            seed()
            return true
        }
        if (!url.startsWith(MusicBrainz.DONE_URL)) return false
        val id = Uri.parse(url).getQueryParameter("release_mbid")
        if (id.isNullOrBlank()) {
            finish()
            return true
        }
        if (cover != null) {
            Toast.makeText(this, S(R.string.mb_cover_next), Toast.LENGTH_LONG).show()
            web.loadUrl(MusicBrainz.addCoverArtUrl(id))
        } else {
            web.loadUrl(MusicBrainz.releaseUrl(id))
        }
        return true
    }

    /** 曲情報を入れた登録画面を開く */
    private fun seed() {
        if (seeded) return
        seeded = true
        web.loadDataWithBaseURL("https://musicbrainz.org/", seedHtml, "text/html", "utf-8", null)
    }

    private fun pageFinished(url: String) {
        when {
            // 最初に開いたトップのページで、ログインしているかを確かめる
            !seeded && url.trimEnd('/') == "https://musicbrainz.org" -> {
                web.evaluateJavascript(
                    "(function(){return !!document.querySelector('a[href^=\"/logout\"],a[href*=\"musicbrainz.org/logout\"]')})()",
                ) { r ->
                    if (r == "true") {
                        seed()
                    } else {
                        Toast.makeText(this, S(R.string.mb_login_first), Toast.LENGTH_LONG).show()
                        web.loadUrl("https://musicbrainz.org/login?returnto=" + Uri.encode(READY_PATH))
                    }
                }
            }
            // 登録画面が開いたら、送信のボタンがある最後のタブ(Edit note。変更の一覧も見られる)に切り替える
            seeded && !jumped && url.contains("/release/add") -> {
                jumped = true
                web.evaluateJavascript(
                    "setTimeout(function(){var a=document.querySelector('a[href=\"#edit-note\"]');if(a){a.click();window.scrollTo(0,0);}},800)",
                    null,
                )
            }
        }
    }

    /**
     * ページでファイルを選ぶとき。ジャケット画像の追加のページなら用意した画像を渡し、
     * それ以外は Android のファイルの選択の画面を開く
     */
    private fun chooseFile(callback: ValueCallback<Array<Uri>>, params: WebChromeClient.FileChooserParams): Boolean {
        val c = cover
        if (c != null && web.url?.contains("/add-cover-art") == true) {
            val u = runCatching { androidx.core.content.FileProvider.getUriForFile(this, "$packageName.files", c) }.getOrElse { Uri.fromFile(c) }
            callback.onReceiveValue(arrayOf(u))
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
        if (::web.isInitialized) back() else @Suppress("DEPRECATION") super.onBackPressed()
    }

    /**
     * 戻る操作。ログインの途中なら前のページへ。それ以外は閉じる
     * (登録画面から戻ると、曲情報を入れたページがもう一度送信されて、登録画面が開き直してしまうため)
     */
    private fun back() {
        if (!seeded && web.canGoBack()) web.goBack() else finish()
    }

    override fun onDestroy() {
        if (::web.isInitialized) web.destroy()
        if (isFinishing) cover?.delete()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_HTML = "html"
        /** ジャケット画像のファイルの場所(アプリのキャッシュ) */
        const val EXTRA_COVER = "cover"
        /** ログインのあとに戻る先(MusicBrainz に無いページ。アプリが受け取って登録画面を開く) */
        private const val READY_PATH = "/ucrt-login-done"
        private const val REQUEST_FILE = 1

        /** 登録画面を開く。cover はジャケット画像(無ければ null) */
        fun open(ctx: android.content.Context, html: String, cover: ByteArray?) {
            val i = Intent(ctx, MusicBrainzSubmitActivity::class.java)
                .putExtra(EXTRA_HTML, html)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (cover != null && cover.isNotEmpty()) {
                // 形式に合った拡張子にする(MusicBrainz は拡張子と中身で形式を見る)
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
