package com.uyatame.ucrt.legacy.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import com.uyatame.ucrt.legacy.Art
import com.uyatame.ucrt.legacy.ArtRef

/**
 * ジャケット(本家の CoverBox・ArtImage)。角を丸くして描き、画像が無ければ目印を出す。
 * 正方形に近い画像は枠いっぱいに切り抜き、縦長・横長の画像(8cm CD の短冊など)は全体が収まるように縮める
 * (一覧の正方形の枠では余白を塗り、再生画面ではジャケットの形のまま出す)
 */
class ArtView(ctx: Context, var cornerDp: Float = 8f) : View(ctx) {
    private var bmp: Bitmap? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shade = Paint(Paint.ANTI_ALIAS_FLAG)
    private val m = Matrix()
    private val r = RectF()
    private var want: ArtRef? = null
    private var wantGen = -1
    /** 再生中の印(暗くして棒のアイコンを出す) */
    var playing = false
        set(v) { if (field != v) { field = v; invalidate() } }
    /** 正方形の枠(高さを幅に合わせる) */
    var square = true
    /** 一覧にジャケットを出さない設定のときは、画像を読まない */
    var enabledArt = true
    private val placeholder = IconDrawable(Icons.Album, Pal.onSurfaceVariant, dp(24))
    private val bars = IconDrawable(Icons.Bars, 0xFFFFFFFF.toInt(), dp(20))

    init {
        // Android 4.3 より前の画面の描画(ハードウェア)は、画像の拡大・縮小(シェーダーの行列)を途中で変えると反映されず、
        // ジャケットが枠に合わずに伸びて見えるので、この部品だけソフトウェアで描く
        if (android.os.Build.VERSION.SDK_INT < 18) setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    override fun onMeasure(w: Int, h: Int) {
        if (square && MeasureSpec.getMode(w) == MeasureSpec.EXACTLY && MeasureSpec.getMode(h) != MeasureSpec.EXACTLY) {
            val s = MeasureSpec.getSize(w)
            setMeasuredDimension(s, s)
        } else super.onMeasure(w, h)
    }

    /** 画像を直接置く */
    fun setBitmap(b: Bitmap?) {
        want = null
        bmp = b
        updateShader()
        invalidate()
    }

    /** 読み込んで出す(px は読み込む大きさ。0 なら表示する大きさ) */
    fun bind(ref: ArtRef?, px: Int = 0) {
        if (ref == null || !enabledArt) { want = null; bmp = null; updateShader(); invalidate(); return }
        if (want?.key == ref.key && want?.trackUri == ref.trackUri && wantGen == Art.generation && bmp != null) return
        want = ref
        wantGen = Art.generation
        val size = if (px > 0) px else maxOf(width, height, dp(48))
        val cached = Art.peek(ref.key, size)
        bmp = cached
        updateShader()
        invalidate()
        Art.load(context, ref, size) { b ->
            if (want !== ref) return@load
            if (b != null) { bmp = b; updateShader(); invalidate() }
        }
    }

    private fun updateShader() {
        val b = bmp
        paint.shader = if (b == null) null else BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        requestLayoutIfFit()
    }

    private fun requestLayoutIfFit() {}

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val rad = dpf(cornerDp)
        val b = bmp
        if (b == null) {
            bg.color = Pal.surfaceVariant
            // ジャケットが無いときは正方形(再生画面の縦長・横長の枠でも、真ん中の正方形に出す)
            val side = minOf(w, h)
            r.set((w - side) / 2, (h - side) / 2, (w + side) / 2, (h + side) / 2)
            c.drawRoundRect(r, rad, rad, bg)
            val s = (minOf(w, h) * 0.45f).toInt()
            placeholder.color = Pal.onSurfaceVariant
            placeholder.setBounds(((w - s) / 2).toInt(), ((h - s) / 2).toInt(), ((w + s) / 2).toInt(), ((h + s) / 2).toInt())
            placeholder.draw(c)
        } else {
            val ratio = b.width.toFloat() / b.height.coerceAtLeast(1)
            val crop = ratio in 0.8f..1.25f
            val sc: Float
            if (crop) {
                // 正方形に近い画像は、枠の中の正方形に描く(縦長・横長の枠でも、ジャケットの比率を崩さない)
                val side = minOf(w, h)
                r.set((w - side) / 2, (h - side) / 2, (w + side) / 2, (h + side) / 2)
                sc = maxOf(side / b.width, side / b.height)
            } else {
                sc = minOf(w / b.width, h / b.height)
                val dw = b.width * sc; val dh = b.height * sc
                r.set((w - dw) / 2, (h - dh) / 2, (w + dw) / 2, (h + dh) / 2)
                // 一覧の正方形の枠は、余白を塗って枠の形をそろえる。再生画面(square = false)は本家と同じく、
                // 枠を塗らずにジャケットの形のまま(角を丸めて)出す
                if (square) {
                    bg.color = Pal.surfaceVariant
                    c.drawRoundRect(RectF(0f, 0f, w, h), rad, rad, bg)
                }
            }
            m.setScale(sc, sc)
            m.postTranslate(r.centerX() - b.width * sc / 2, r.centerY() - b.height * sc / 2)
            paint.shader?.setLocalMatrix(m)
            c.drawRoundRect(r, rad, rad, paint)
        }
        if (playing) {
            shade.color = 0x73000000
            r.set(0f, 0f, w, h)
            c.drawRoundRect(r, rad, rad, shade)
            val s = (minOf(w, h) * 0.42f).toInt()
            bars.setBounds(((w - s) / 2).toInt(), ((h - s) / 2).toInt(), ((w + s) / 2).toInt(), ((h + s) / 2).toInt())
            bars.draw(c)
        }
    }

    val bitmap: Bitmap? get() = bmp
}
