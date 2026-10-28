package com.uyatame.ucrt.legacy.ui

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * 画面のアイコン(本家と同じ Material の形。24×24 の SVG のパス)。
 * 画像ファイルを使わず、どの大きさでもくっきり描け、色も自由に変えられる
 */
object Icons {
    // ---- 本家の AppIcons と同じもの ----
    const val Pause = "M6,5h4v14h-4zM14,5h4v14h-4z"
    const val SkipNext = "M6,6l8.5,6l-8.5,6zM16,6h2v12h-2z"
    const val SkipPrevious = "M6,6h2v12h-2zM18,6v12l-8.5,-6z"
    const val Eject = "M5,17h14v2h-14zM12,5l6.67,10h-13.34z"
    const val Album = "M12,2a10,10 0 1,0 0.001,0zM12,7.5a4.5,4.5 0 1,1 -0.001,0zM12,11a1,1 0 1,0 0.001,0z"
    const val MusicNote = "M12,3v10.55c-0.59,-0.34 -1.27,-0.55 -2,-0.55c-2.21,0 -4,1.79 -4,4s1.79,4 4,4s4,-1.79 4,-4V7h4V3z"
    const val Download = "M19,9h-4V3H9v6H5l7,7l7,-7zM5,18v2h14v-2z"
    const val Upload = "M5,20h14v-2H5V20zM5,10h4v6h6v-6h4l-7,-7L5,10z"
    const val Library = "M4,6H2v14c0,1.1 0.9,2 2,2h14v-2H4zM20,2H8C6.9,2 6,2.9 6,4v12c0,1.1 0.9,2 2,2h12c1.1,0 2,-0.9 2,-2V4C22,2.9 21.1,2 20,2zM18,7h-3v5.5c0,1.38 -1.12,2.5 -2.5,2.5S10,13.88 10,12.5s1.12,-2.5 2.5,-2.5c0.57,0 1.08,0.19 1.5,0.51V5h4z"
    const val Shuffle = "M10.59,9.17L5.41,4L4,5.41l5.17,5.17zM14.5,4l2.04,2.04L4,18.59L5.41,20L17.96,7.46L20,9.5V4zM14.83,13.41l-1.41,1.41l3.13,3.13L14.5,20H20v-5.5l-2.04,2.04z"
    const val Repeat = "M7,7h10v3l4,-4l-4,-4v3H5v6h2V7zM17,17H7v-3l-4,4l4,4v-3h12v-6h-2v4z"
    const val RepeatOne = "M7,7h10v3l4,-4l-4,-4v3H5v6h2V7zM17,17H7v-3l-4,4l4,4v-3h12v-6h-2v4zM13,15V9h-1l-2,1v1h1.5v4H13z"
    const val Grid = "M3,3h8v8H3zM13,3h8v8h-8zM3,13h8v8H3zM13,13h8v8h-8z"
    const val ListView = "M3,5h4v4H3zM9,6h12v2H9zM3,10h4v4H3zM9,11h12v2H9zM3,15h4v4H3zM9,16h12v2H9z"
    const val Tune = "M3,17v2h6v-2H3zM3,5v2h10V5H3zM13,21v-2h8v-2h-8v-2h-2v6h2zM7,9v2H3v2h4v2h2V9H7zM21,13v-2H11v2h10zM15,9h2V7h4V5h-4V3h-2v6z"
    const val Smartphone = "M17,1.01L7,1C5.9,1 5,1.9 5,3v18c0,1.1 0.9,2 2,2h10c1.1,0 2,-0.9 2,-2V3C19,1.9 18.1,1.01 17,1.01zM17,19H7V5h10V19z"
    const val Headphones = "M12,1c-4.97,0 -9,4.03 -9,9v7c0,1.66 1.34,3 3,3h3v-8H5v-2c0,-3.87 3.13,-7 7,-7s7,3.13 7,7v2h-4v8h3c1.66,0 3,-1.34 3,-3v-7C21,5.03 16.97,1 12,1z"
    const val Usb = "M15,7v4h1v2h-3V5h2l-3,-4l-3,4h2v8H8v-2.07c0.7,-0.37 1.2,-1.08 1.2,-1.93c0,-1.21 -0.99,-2.2 -2.2,-2.2S4.8,7.79 4.8,9c0,0.85 0.5,1.56 1.2,1.93V13c0,1.11 0.89,2 2,2h3v3.05c-0.71,0.37 -1.2,1.1 -1.2,1.95c0,1.22 0.99,2.2 2.2,2.2s2.2,-0.98 2.2,-2.2c0,-0.85 -0.49,-1.58 -1.2,-1.95V15h3c1.11,0 2,-0.89 2,-2v-2h1V7H15z"
    const val Bluetooth = "M17.71,7.71L12,2h-1v7.59L6.41,5L5,6.41L10.59,12L5,17.59L6.41,19L11,14.41V22h1l5.71,-5.71L13.41,12L17.71,7.71zM13,5.83l1.88,1.88L13,9.59V5.83zM14.88,16.29L13,18.17v-3.76L14.88,16.29z"
    const val Speaker = "M3,9v6h4l5,5V4L7,9H3zM16.5,12c0,-1.77 -1.02,-3.29 -2.5,-4.03v8.05c1.48,-0.73 2.5,-2.25 2.5,-4.02z"
    const val Queue = "M15,6H3v2h12V6zM15,10H3v2h12v-2zM3,16h8v-2H3V16zM17,6v8.18C16.69,14.07 16.35,14 16,14c-1.66,0 -3,1.34 -3,3s1.34,3 3,3s3,-1.34 3,-3V8h3V6H17z"
    const val PlaylistAdd = "M14,10H3v2h11V10zM14,6H3v2h11V6zM18,14v-4h-2v4h-4v2h4v4h2v-4h4v-2H18zM3,16h7v-2H3V16z"
    const val PlayNext = "M3,10h11v2H3V10zM3,6h11v2H3V6zM3,14h7v2H3V14zM16,13v8l6,-4L16,13z"
    const val Lyrics = "M14,17H4v2h10v-2zM20,9H4v2h16V9zM4,15h16v-2H4v2zM4,5v2h16V5H4z"
    const val Timer = "M15,1H9v2h6V1zM11,14h2V8h-2V14zM19.03,7.39l1.42,-1.42c-0.43,-0.51 -0.9,-0.99 -1.41,-1.41l-1.42,1.42C16.07,4.74 14.12,4 12,4c-4.97,0 -9,4.03 -9,9s4.02,9 9,9s9,-4.03 9,-9C21,10.88 20.26,8.93 19.03,7.39zM12,20c-3.87,0 -7,-3.13 -7,-7s3.13,-7 7,-7s7,3.13 7,7S15.87,20 12,20z"
    const val Folder = "M10,4H4c-1.1,0 -1.99,0.9 -1.99,2L2,18c0,1.1 0.9,2 2,2h16c1.1,0 2,-0.9 2,-2V8c0,-1.1 -0.9,-2 -2,-2h-8l-2,-2z"
    const val Genre = "M21.41,11.58l-9,-9C12.05,2.22 11.55,2 11,2H4c-1.1,0 -2,0.9 -2,2v7c0,0.55 0.22,1.05 0.59,1.42l9,9c0.36,0.36 0.86,0.58 1.41,0.58c0.55,0 1.05,-0.22 1.41,-0.59l7,-7c0.37,-0.36 0.59,-0.86 0.59,-1.41C22,12.44 21.77,11.93 21.41,11.58zM5.5,7C4.67,7 4,6.33 4,5.5S4.67,4 5.5,4S7,4.67 7,5.5S6.33,7 5.5,7z"
    const val History = "M13,3c-4.97,0 -9,4.03 -9,9H1l3.89,3.89l0.07,0.14L9,12H6c0,-3.87 3.13,-7 7,-7s7,3.13 7,7s-3.13,7 -7,7c-1.93,0 -3.68,-0.79 -4.94,-2.06l-1.42,1.42C8.27,19.99 10.51,21 13,21c4.97,0 9,-4.03 9,-9S17.97,3 13,3zM12,8v5l4.28,2.54l0.72,-1.21l-3.5,-2.08V8H12z"
    const val NewAdded = "M4,6H2v14c0,1.1 0.9,2 2,2h14v-2H4V6zM20,2H8C6.9,2 6,2.9 6,4v12c0,1.1 0.9,2 2,2h12c1.1,0 2,-0.9 2,-2V4C22,2.9 21.1,2 20,2zM19,11h-4v4h-2v-4H9V9h4V5h2v4h4V11z"
    const val Trending = "M16,6l2.29,2.29l-4.88,4.88l-4,-4L2,16.59L3.41,18l6,-6l4,4l6.3,-6.29L22,12V6z"
    const val HiRes = "M19,4H5c-1.11,0 -2,0.9 -2,2v12c0,1.1 0.89,2 2,2h14c1.1,0 2,-0.9 2,-2V6c0,-1.1 -0.9,-2 -2,-2zM11,15H9.5v-2h-2v2H6V9h1.5v2.5h2V9H11v6zM18,14c0,0.55 -0.45,1 -1,1h-0.75v1.5h-1.5V15H14c-0.55,0 -1,-0.45 -1,-1v-4c0,-0.55 0.45,-1 1,-1h3c0.55,0 1,0.45 1,1v4zM14.5,13.5h2v-3h-2v3z"
    const val Volume = "M3,9v6h4l5,5V4L7,9H3zM16.5,12c0,-1.77 -1.02,-3.29 -2.5,-4.03v8.05c1.48,-0.73 2.5,-2.25 2.5,-4.02zM14,3.23v2.06c2.89,0.86 5,3.54 5,6.71s-2.11,5.85 -5,6.71v2.06c4.01,-0.91 7,-4.49 7,-8.77s-2.99,-7.86 -7,-8.77z"
    const val DragHandle = "M20,9H4v2h16V9zM4,15h16v-2H4V15z"
    const val Bars = "M10,20h4V4h-4v16zM4,20h4v-8H4v8zM16,9v11h4V9h-4z"
    const val CastConnected = "M1,18v3h3C4,19.34 2.66,18 1,18zM1,14v2c2.76,0 5,2.24 5,5h2C8,17.13 4.87,14 1,14zM19,7H5v1.63c3.96,1.28 7.09,4.41 8.37,8.37H19V7zM1,10v2c4.97,0 9,4.03 9,9h2C12,14.92 7.07,10 1,10zM21,3H3C1.9,3 1,3.9 1,5v3h2V5h18v14h-7v2h7c1.1,0 2,-0.9 2,-2V5C23,3.9 22.1,3 21,3z"
    const val Cast = "M21,3H3C1.9,3 1,3.9 1,5v3h2V5h18v14h-7v2h7c1.1,0 2,-0.9 2,-2V5C23,3.9 22.1,3 21,3zM1,18v3h3C4,19.34 2.66,18 1,18zM1,14v2c2.76,0 5,2.24 5,5h2C8,17.13 4.87,14 1,14zM1,10v2c4.97,0 9,4.03 9,9h2C12,14.92 7.07,10 1,10z"

    // ---- Material の標準のアイコン(本家が使っているもの) ----
    const val PlayArrow = "M8,5v14l11,-7z"
    const val Settings = "M19.14,12.94c0.04,-0.3 0.06,-0.61 0.06,-0.94c0,-0.32 -0.02,-0.64 -0.07,-0.94l2.03,-1.58c0.18,-0.14 0.23,-0.41 0.12,-0.61l-1.92,-3.32c-0.12,-0.22 -0.37,-0.29 -0.59,-0.22l-2.39,0.96c-0.5,-0.38 -1.03,-0.7 -1.62,-0.94L14.4,2.81c-0.04,-0.24 -0.24,-0.41 -0.48,-0.41h-3.84c-0.24,0 -0.43,0.17 -0.47,0.41L9.25,5.35C8.66,5.59 8.12,5.92 7.63,6.29L5.24,5.33c-0.22,-0.08 -0.47,0 -0.59,0.22L2.74,8.87C2.62,9.08 2.66,9.34 2.86,9.48l2.03,1.58C4.84,11.36 4.8,11.69 4.8,12s0.02,0.64 0.07,0.94l-2.03,1.58c-0.18,0.14 -0.23,0.41 -0.12,0.61l1.92,3.32c0.12,0.22 0.37,0.29 0.59,0.22l2.39,-0.96c0.5,0.38 1.03,0.7 1.62,0.94l0.36,2.54c0.05,0.24 0.24,0.41 0.48,0.41h3.84c0.24,0 0.44,-0.17 0.47,-0.41l0.36,-2.54c0.59,-0.24 1.13,-0.56 1.62,-0.94l2.39,0.96c0.22,0.08 0.47,0 0.59,-0.22l1.92,-3.32c0.12,-0.22 0.07,-0.47 -0.12,-0.61L19.14,12.94zM12,15.6c-1.98,0 -3.6,-1.62 -3.6,-3.6s1.62,-3.6 3.6,-3.6s3.6,1.62 3.6,3.6S13.98,15.6 12,15.6z"
    const val ArrowBack = "M20,11H7.83l5.59,-5.59L12,4l-8,8l8,8l1.41,-1.41L7.83,13H20v-2z"
    const val Search = "M15.5,14h-0.79l-0.28,-0.27C15.41,12.59 16,11.11 16,9.5 16,5.91 13.09,3 9.5,3S3,5.91 3,9.5 5.91,16 9.5,16c1.61,0 3.09,-0.59 4.23,-1.57l0.27,0.28v0.79l5,4.99L20.49,19l-4.99,-5zM9.5,14C7.01,14 5,11.99 5,9.5S7.01,5 9.5,5 14,7.01 14,9.5 11.99,14 9.5,14z"
    const val Edit = "M3,17.25V21h3.75L17.81,9.94l-3.75,-3.75L3,17.25zM20.71,7.04c0.39,-0.39 0.39,-1.02 0,-1.41l-2.34,-2.34c-0.39,-0.39 -1.02,-0.39 -1.41,0l-1.83,1.83 3.75,3.75 1.83,-1.83z"
    const val Refresh = "M17.65,6.35C16.2,4.9 14.21,4 12,4c-4.42,0 -7.99,3.58 -7.99,8s3.57,8 7.99,8c3.73,0 6.84,-2.55 7.73,-6h-2.08c-0.82,2.33 -3.04,4 -5.65,4 -3.31,0 -6,-2.69 -6,-6s2.69,-6 6,-6c1.66,0 3.14,0.69 4.22,1.78L13,11h7V4l-2.35,2.35z"
    const val MoreVert = "M12,8c1.1,0 2,-0.9 2,-2s-0.9,-2 -2,-2 -2,0.9 -2,2 0.9,2 2,2zM12,10c-1.1,0 -2,0.9 -2,2s0.9,2 2,2 2,-0.9 2,-2 -0.9,-2 -2,-2zM12,16c-1.1,0 -2,0.9 -2,2s0.9,2 2,2 2,-0.9 2,-2 -0.9,-2 -2,-2z"
    const val Favorite = "M12,21.35l-1.45,-1.32C5.4,15.36 2,12.28 2,8.5 2,5.42 4.42,3 7.5,3c1.74,0 3.41,0.81 4.5,2.09C13.09,3.81 14.76,3 16.5,3 19.58,3 22,5.42 22,8.5c0,3.78 -3.4,6.86 -8.55,11.54L12,21.35z"
    const val FavoriteBorder = "M16.5,3c-1.74,0 -3.41,0.81 -4.5,2.09C10.91,3.81 9.24,3 7.5,3 4.42,3 2,5.42 2,8.5c0,3.78 3.4,6.86 8.55,11.54L12,21.35l1.45,-1.32C18.6,15.36 22,12.28 22,8.5 22,5.42 19.58,3 16.5,3zM12.1,18.55l-0.1,0.1 -0.1,-0.1C7.14,14.24 4,11.39 4,8.5 4,6.5 5.5,5 7.5,5c1.54,0 3.04,0.99 3.57,2.36h1.87C13.46,5.99 14.96,5 16.5,5c2,0 3.5,1.5 3.5,3.5 0,2.89 -3.14,5.74 -7.9,10.05z"
    const val Person = "M12,12c2.21,0 4,-1.79 4,-4s-1.79,-4 -4,-4 -4,1.79 -4,4 1.79,4 4,4zM12,14c-2.67,0 -8,1.34 -8,4v2h16v-2c0,-2.66 -5.33,-4 -8,-4z"
    const val DateRange = "M9,11H7v2h2v-2zM13,11h-2v2h2v-2zM17,11h-2v2h2v-2zM19,4h-1V2h-2v2H8V2H6v2H5c-1.11,0 -1.99,0.9 -1.99,2L3,20c0,1.1 0.89,2 2,2h14c1.1,0 2,-0.9 2,-2V6c0,-1.1 -0.9,-2 -2,-2zM19,20H5V9h14v11z"
    const val CheckCircle = "M12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2zM10,17l-5,-5 1.41,-1.41L10,14.17l7.59,-7.59L19,8l-9,9z"
    const val Close = "M19,6.41L17.59,5 12,10.59 6.41,5 5,6.41 10.59,12 5,17.59 6.41,19 12,13.41 17.59,19 19,17.59 13.41,12z"
    const val Delete = "M6,19c0,1.1 0.9,2 2,2h8c1.1,0 2,-0.9 2,-2V7H6v12zM19,4h-3.5l-1,-1h-5l-1,1H5v2h14V4z"
    const val Add = "M19,13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z"
    const val Info = "M12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2zM13,17h-2v-6h2v6zM13,9h-2V7h2v2z"
    const val ArrowDown = "M7.41,8.59L12,13.17l4.59,-4.58L18,10l-6,6 -6,-6 1.41,-1.41z"
    const val ArrowUp = "M7.41,15.41L12,10.83l4.59,4.58L18,14l-6,-6 -6,6z"
    const val ArrowRight = "M8.59,16.59L13.17,12 8.59,7.41 10,6l6,6 -6,6 -1.41,-1.41z"
    const val Build = "M22.7,19l-9.1,-9.1c0.9,-2.3 0.4,-5 -1.5,-6.9 -2,-2 -5,-2.4 -7.4,-1.3L9,6 6,9 1.6,4.7C0.4,7.1 0.9,10.1 2.9,12.1c1.9,1.9 4.6,2.4 6.9,1.5l9.1,9.1c0.4,0.4 1,0.4 1.4,0l2.3,-2.3c0.5,-0.4 0.5,-1.1 0.1,-1.4z"
    const val ExitToApp = "M10.09,15.59L11.5,17l5,-5 -5,-5 -1.41,1.41L12.67,11H3v2h9.67l-2.58,2.59zM19,3H5c-1.11,0 -2,0.9 -2,2v4h2V5h14v14H5v-4H3v4c0,1.1 0.89,2 2,2h14c1.1,0 2,-0.9 2,-2V5c0,-1.1 -0.9,-2 -2,-2z"
    const val Check = "M9,16.17L4.83,12l-1.42,1.41L9,19 21,7l-1.41,-1.41z"

    /** 内側をくり抜く(EvenOdd)アイコン */
    private val evenOdd = setOf(Album, Library, Genre, NewAdded, HiRes)

    private val cache = HashMap<String, Path>()

    /** パスを作る(同じアイコンは使い回す) */
    fun path(d: String): Path = synchronized(cache) {
        cache.getOrPut(d) { PathParser.parse(d).apply { fillType = if (d in evenOdd) Path.FillType.EVEN_ODD else Path.FillType.WINDING } }
    }
}

/** アイコンを描く Drawable(24×24 のパスを、受け取った大きさに合わせて描く) */
class IconDrawable(private val d: String, color: Int, private val sizePx: Int) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.FILL }
    private val m = Matrix()
    private val p = Path()

    var color: Int
        get() = paint.color
        set(v) { paint.color = v; invalidateSelf() }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.width() <= 0 || b.height() <= 0) return
        // Android 4.3 より前は、画面の描画(ハードウェア)でパスの塗りが出ないことがあるので、
        // いったん画像に描いてから貼る(大きさと色ごとに覚えておく)
        if (android.os.Build.VERSION.SDK_INT < 18 && canvas.isHardwareAccelerated) {
            val key = d.hashCode().toString() + "/" + paint.color + "/" + paint.alpha + "/" + b.width() + "x" + b.height()
            val bmp = synchronized(bitmaps) {
                bitmaps.get(key) ?: android.graphics.Bitmap.createBitmap(b.width(), b.height(), android.graphics.Bitmap.Config.ARGB_8888).also { bm ->
                    val c = Canvas(bm)
                    val s = minOf(b.width(), b.height()) / 24f
                    m.setScale(s, s)
                    m.postTranslate((b.width() - 24 * s) / 2, (b.height() - 24 * s) / 2)
                    p.reset()
                    Icons.path(d).transform(m, p)
                    c.drawPath(p, paint)
                    bitmaps.put(key, bm)
                }
            }
            canvas.drawBitmap(bmp, b.left.toFloat(), b.top.toFloat(), null)
            return
        }
        val s = minOf(b.width(), b.height()) / 24f
        m.setScale(s, s)
        m.postTranslate(b.left + (b.width() - 24 * s) / 2, b.top + (b.height() - 24 * s) / 2)
        p.reset()
        Icons.path(d).transform(m, p)
        canvas.drawPath(p, paint)
    }

    private companion object {
        /** Android 4.3 より前のための、描いたアイコンの画像(使った順に 300 個まで) */
        val bitmaps = object : android.util.LruCache<String, android.graphics.Bitmap>(300) {}
    }

    override fun getIntrinsicWidth() = sizePx
    override fun getIntrinsicHeight() = sizePx
    override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(cf: ColorFilter?) { paint.colorFilter = cf; invalidateSelf() }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/** SVG のパスの文字列を Path にする(M L H V C S Q T A Z。小文字は相対) */
object PathParser {
    fun parse(d: String): Path {
        val p = Path()
        val t = Tok(d)
        var cx = 0f; var cy = 0f
        var sx = 0f; var sy = 0f
        var lcx = 0f; var lcy = 0f // 前の曲線の制御点(S・T のため)
        var prev = ' '
        var cmd = ' '
        while (true) {
            t.skipSep()
            if (t.end()) break
            val c = t.peek()
            if (c.isLetter()) { cmd = c; t.next() } else if (cmd == ' ') break
            val rel = cmd.isLowerCase()
            when (cmd.uppercaseChar()) {
                'M' -> {
                    var x = t.num(); var y = t.num()
                    if (rel) { x += cx; y += cy }
                    p.moveTo(x, y); cx = x; cy = y; sx = x; sy = y
                    // M の後に続く座標は L として読む
                    cmd = if (rel) 'l' else 'L'
                }
                'L' -> { var x = t.num(); var y = t.num(); if (rel) { x += cx; y += cy }; p.lineTo(x, y); cx = x; cy = y }
                'H' -> { var x = t.num(); if (rel) x += cx; p.lineTo(x, cy); cx = x }
                'V' -> { var y = t.num(); if (rel) y += cy; p.lineTo(cx, y); cy = y }
                'C' -> {
                    var x1 = t.num(); var y1 = t.num(); var x2 = t.num(); var y2 = t.num(); var x = t.num(); var y = t.num()
                    if (rel) { x1 += cx; y1 += cy; x2 += cx; y2 += cy; x += cx; y += cy }
                    p.cubicTo(x1, y1, x2, y2, x, y); lcx = x2; lcy = y2; cx = x; cy = y
                }
                'S' -> {
                    var x2 = t.num(); var y2 = t.num(); var x = t.num(); var y = t.num()
                    if (rel) { x2 += cx; y2 += cy; x += cx; y += cy }
                    val pu = prev.uppercaseChar()
                    val x1 = if (pu == 'C' || pu == 'S') 2 * cx - lcx else cx
                    val y1 = if (pu == 'C' || pu == 'S') 2 * cy - lcy else cy
                    p.cubicTo(x1, y1, x2, y2, x, y); lcx = x2; lcy = y2; cx = x; cy = y
                }
                'Q' -> {
                    var x1 = t.num(); var y1 = t.num(); var x = t.num(); var y = t.num()
                    if (rel) { x1 += cx; y1 += cy; x += cx; y += cy }
                    p.quadTo(x1, y1, x, y); lcx = x1; lcy = y1; cx = x; cy = y
                }
                'T' -> {
                    var x = t.num(); var y = t.num()
                    if (rel) { x += cx; y += cy }
                    val pu = prev.uppercaseChar()
                    val x1 = if (pu == 'Q' || pu == 'T') 2 * cx - lcx else cx
                    val y1 = if (pu == 'Q' || pu == 'T') 2 * cy - lcy else cy
                    p.quadTo(x1, y1, x, y); lcx = x1; lcy = y1; cx = x; cy = y
                }
                'A' -> {
                    val rx = t.num(); val ry = t.num(); val rot = t.num()
                    val large = t.flag(); val sweep = t.flag()
                    var x = t.num(); var y = t.num()
                    if (rel) { x += cx; y += cy }
                    arc(p, cx, cy, x, y, rx, ry, rot, large, sweep)
                    cx = x; cy = y
                }
                'Z' -> { p.close(); cx = sx; cy = sy }
                else -> break
            }
            prev = cmd
            if (cmd.uppercaseChar() == 'Z') cmd = ' '
        }
        return p
    }

    /** 楕円の弧を 3 次ベジェ曲線で描く(SVG の仕様の F.6 の方法) */
    private fun arc(p: Path, x0: Float, y0: Float, x1: Float, y1: Float, rx0: Float, ry0: Float, rotDeg: Float, large: Boolean, sweep: Boolean) {
        if (x0 == x1 && y0 == y1) return
        var rx = abs(rx0).toDouble(); var ry = abs(ry0).toDouble()
        if (rx == 0.0 || ry == 0.0) { p.lineTo(x1, y1); return }
        val phi = Math.toRadians(rotDeg.toDouble())
        val cosP = cos(phi); val sinP = sin(phi)
        val dx = (x0 - x1) / 2.0; val dy = (y0 - y1) / 2.0
        val x1p = cosP * dx + sinP * dy
        val y1p = -sinP * dx + cosP * dy
        val lam = (x1p * x1p) / (rx * rx) + (y1p * y1p) / (ry * ry)
        if (lam > 1) { val s = sqrt(lam); rx *= s; ry *= s }
        val num = rx * rx * ry * ry - rx * rx * y1p * y1p - ry * ry * x1p * x1p
        val den = rx * rx * y1p * y1p + ry * ry * x1p * x1p
        var co = if (den == 0.0) 0.0 else sqrt((num / den).coerceAtLeast(0.0))
        if (large == sweep) co = -co
        val cxp = co * rx * y1p / ry
        val cyp = -co * ry * x1p / rx
        val cx = cosP * cxp - sinP * cyp + (x0 + x1) / 2.0
        val cy = sinP * cxp + cosP * cyp + (y0 + y1) / 2.0
        fun ang(ux: Double, uy: Double, vx: Double, vy: Double): Double {
            val d = (ux * vx + uy * vy) / (sqrt(ux * ux + uy * uy) * sqrt(vx * vx + vy * vy))
            val a = acos(d.coerceIn(-1.0, 1.0))
            return if (ux * vy - uy * vx < 0) -a else a
        }
        val th1 = ang(1.0, 0.0, (x1p - cxp) / rx, (y1p - cyp) / ry)
        var dth = ang((x1p - cxp) / rx, (y1p - cyp) / ry, (-x1p - cxp) / rx, (-y1p - cyp) / ry)
        if (!sweep && dth > 0) dth -= 2 * Math.PI
        if (sweep && dth < 0) dth += 2 * Math.PI
        val segs = ceil(abs(dth) / (Math.PI / 2)).toInt().coerceAtLeast(1)
        val d = dth / segs
        val k = 4.0 / 3.0 * tan(d / 4)
        var a = th1
        for (i in 0 until segs) {
            val c1 = cos(a); val s1 = sin(a)
            val c2 = cos(a + d); val s2 = sin(a + d)
            fun px(ex: Double, ey: Double) = (cx + rx * ex * cosP - ry * ey * sinP).toFloat()
            fun py(ex: Double, ey: Double) = (cy + rx * ex * sinP + ry * ey * cosP).toFloat()
            p.cubicTo(
                px(c1 - k * s1, s1 + k * c1), py(c1 - k * s1, s1 + k * c1),
                px(c2 + k * s2, s2 - k * c2), py(c2 + k * s2, s2 - k * c2),
                px(c2, s2), py(c2, s2),
            )
            a += d
        }
    }

    private class Tok(val s: String) {
        var i = 0
        fun end() = i >= s.length
        fun peek() = s[i]
        fun next() { i++ }
        fun skipSep() { while (i < s.length && (s[i] == ' ' || s[i] == ',' || s[i] == '\n' || s[i] == '\t')) i++ }
        fun flag(): Boolean {
            skipSep()
            val c = s[i]
            i++
            return c == '1'
        }
        fun num(): Float {
            skipSep()
            val st = i
            if (i < s.length && (s[i] == '-' || s[i] == '+')) i++
            var dot = false
            while (i < s.length) {
                val c = s[i]
                if (c.isDigit()) i++
                else if (c == '.' && !dot) { dot = true; i++ }
                else if ((c == 'e' || c == 'E') && i + 1 < s.length) { i++; if (s[i] == '-' || s[i] == '+') i++ }
                else break
            }
            return s.substring(st, i).toFloat()
        }
    }
}
