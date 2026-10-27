package com.uyatame.ucrt.legacy.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import com.uyatame.ucrt.legacy.compat.Api21

// =====================================================================
// ダイアログ・下から出す画面・メニュー(本家の AlertDialog・ModalBottomSheet・DropdownMenu)
// =====================================================================

/** 確認のダイアログ(本家の AlertDialog と同じ形) */
class UDialog(ctx: Context) : Dialog(ctx) {
    val body: LinearLayout
    private val titleView: TextView
    private val buttons: LinearLayout
    private val iconBox: FrameLayout
    private val scroll: ScrollView

    init {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        val card = vbox(ctx)
        card.bgd(rounded(Pal.containerHigh, 28f))
        card.setPadding(dp(24), dp(24), dp(24), dp(18))
        iconBox = card.add(FrameLayout(ctx), h = WRAP, b = 0)
        iconBox.visibility = View.GONE
        titleView = card.add(text(ctx, "", Type.HeadlineSmall, Pal.onSurface, lines = 3), b = 16)
        titleView.visibility = View.GONE
        scroll = ScrollView(ctx)
        body = vbox(ctx)
        scroll.addView(body)
        card.add(scroll, h = WRAP, weight = 1f)
        buttons = hbox(ctx, Gravity.END or Gravity.CENTER_VERTICAL)
        card.add(buttons, t = 20)
        setContentView(card)
        val w = ctx.resources.displayMetrics.widthPixels
        window?.setLayout(minOf(w - dp(48), dp(560)), WindowManager.LayoutParams.WRAP_CONTENT)
    }

    fun title(s: String?): UDialog {
        titleView.text = s.orEmpty()
        titleView.visibility = if (s.isNullOrEmpty()) View.GONE else View.VISIBLE
        return this
    }

    fun icon(d: String, color: Int): UDialog {
        iconBox.removeAllViews()
        iconBox.add(icon(context, d, color, 24), w = WRAP, h = 24, b = 16, gravity = Gravity.CENTER_HORIZONTAL)
        iconBox.visibility = View.VISIBLE
        return this
    }

    fun message(s: CharSequence, color: Int = Pal.onSurfaceVariant): UDialog {
        body.add(text(context, s, Type.BodyMedium, color, lines = 0))
        return this
    }

    fun view(v: View): UDialog {
        body.add(v)
        return this
    }

    /** ボタン(右から順に足していく見た目にするため、足した順に左から並べる) */
    fun button(label: String, color: Int = Pal.primary, enabled: Boolean = true, onClick: () -> Unit): TextView {
        val t = text(context, label, Type.LabelLarge, color)
        t.gravity = Gravity.CENTER
        t.minHeight = dp(40)
        t.setPadding(dp(12), 0, dp(12), 0)
        t.tap(20f) { onClick() }
        buttons.add(t, w = WRAP, h = WRAP, l = 8)
        t.enabled(enabled)
        return t
    }

    /** 中身とボタンを消す(作り直すとき) */
    fun clear() {
        body.removeAllViews()
        buttons.removeAllViews()
    }

    /** 閉じるだけのボタン */
    fun close(label: String): UDialog {
        button(label) { dismiss() }
        return this
    }
}

/** 下から出す画面(本家の ModalBottomSheet) */
class Sheet(ctx: Context, full: Boolean = false) : Dialog(ctx) {
    val body: LinearLayout
    private val root: LinearLayout

    init {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        root = vbox(ctx, Gravity.CENTER_HORIZONTAL)
        root.bgd(roundedTop(Pal.containerLow, 28f))
        // つまみ
        val handle = View(ctx)
        handle.bgd(rounded(Pal.alpha(Pal.onSurfaceVariant, 0.4f), 2f))
        root.add(handle, w = 32, h = 4, t = 16, b = 12)
        val sc = ScrollView(ctx)
        body = vbox(ctx)
        sc.addView(body)
        root.add(sc, h = WRAP)
        setContentView(root)
        setCanceledOnTouchOutside(true)
        window?.let { w ->
            w.setGravity(Gravity.BOTTOM)
            val dm = ctx.resources.displayMetrics
            val wide = minOf(dm.widthPixels, dp(640))
            val maxH = (dm.heightPixels * if (full) 0.95f else 0.9f).toInt()
            w.setLayout(wide, WindowManager.LayoutParams.WRAP_CONTENT)
            root.post {
                if (root.height > maxH) w.setLayout(wide, maxH)
            }
        }
    }

    override fun show() {
        super.show()
        // 下から出てくる
        root.translationY = dpf(400)
        root.animate().translationY(0f).setDuration(220).start()
    }

    fun add(v: View, h: Int = WRAP): View = body.add(v, h = h)
}

/** 下から出す画面の操作の 1 行(本家の SheetAction) */
fun sheetAction(ctx: Context, d: String, label: String, color: Int = Pal.onSurface, onClick: () -> Unit): View {
    val row = hbox(ctx)
    row.setPadding(dp(24), dp(14), dp(24), dp(14))
    row.add(icon(ctx, d, if (color == Pal.onSurface) Pal.onSurfaceVariant else color, 24), w = 24, h = 24, r = 20)
    row.add(text(ctx, label, Type.BodyLarge, color), w = 0, weight = 1f)
    row.tap(0f) { onClick() }
    return row
}

/** 本家の DropdownMenu(ボタンの近くに出す小さなメニュー) */
class Menu(private val anchor: View) {
    private val ctx = anchor.context
    private val box = vbox(ctx)
    private val pop: PopupWindow

    init {
        box.bgd(rounded(Pal.container, 4f))
        box.setPadding(0, dp(8), 0, dp(8))
        val sc = ScrollView(ctx)
        sc.addView(box)
        pop = PopupWindow(sc, dp(240), ViewGroup.LayoutParams.WRAP_CONTENT, true)
        pop.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        pop.isOutsideTouchable = true
        if (Build.VERSION.SDK_INT >= 21) Api21.elevation(sc, dpf(3))
    }

    fun item(label: String, d: String? = null, onClick: () -> Unit): Menu {
        val row = hbox(ctx)
        row.minimumHeight = dp(48)
        row.setPadding(dp(12), 0, dp(12), 0)
        if (d != null) row.add(icon(ctx, d, Pal.onSurfaceVariant, 24), w = 24, h = 24, r = 12)
        row.add(text(ctx, label, Type.BodyLarge, Pal.onSurface, lines = 2), w = 0, weight = 1f)
        row.tap(0f) { pop.dismiss(); onClick() }
        box.add(row)
        return this
    }

    fun divider(): Menu {
        box.addDivider()
        return this
    }

    fun show() {
        pop.showAsDropDown(anchor, 0, -anchor.height / 2)
    }
}

/** 文字の入力欄(本家の OutlinedTextField) */
fun field(ctx: Context, value: String, label: String, singleLine: Boolean = true, onDone: (() -> Unit)? = null): EditText =
    EditText(ctx).apply {
        setText(value)
        hint = label
        style(Type.BodyLarge, Pal.onSurface)
        setHintTextColor(Pal.onSurfaceVariant)
        bgd(rounded(0, 4f, Pal.outline, 1f))
        setPadding(dp(16), dp(14), dp(16), dp(14))
        if (singleLine) {
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_DONE
            inputType = InputType.TYPE_CLASS_TEXT
        } else {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 3
            gravity = Gravity.TOP
        }
        if (onDone != null) setOnEditorActionListener { _, _, _ -> onDone(); true }
        setSelection(text.length)
    }

/** 入力欄の上に小さく名前を出したもの */
fun labeled(ctx: Context, label: String, f: View): View {
    val col = vbox(ctx)
    col.add(text(ctx, label, Type.LabelMedium, Pal.primary), l = 4, b = 4)
    col.add(f)
    return col
}
