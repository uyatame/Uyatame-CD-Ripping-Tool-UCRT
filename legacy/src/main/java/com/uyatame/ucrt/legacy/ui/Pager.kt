package com.uyatame.ucrt.legacy.ui

import android.content.Context
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import kotlin.math.abs

/**
 * 左右にめくるページ(本家の HorizontalPager)。ページは同じ大きさで横に並べ、指で引っ張って切り替える
 */
class Pager(ctx: Context) : FrameLayout(ctx) {
    var page = 0
        private set
    var onPage: ((Int) -> Unit)? = null
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private var offset = 0f
    private var vt: VelocityTracker? = null

    fun pages(vararg v: View) {
        removeAllViews()
        v.forEach { addView(it, LayoutParams(MATCH, MATCH)) }
        layoutPages()
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        layoutPages()
    }

    private fun layoutPages() {
        val w = width.toFloat()
        for (i in 0 until childCount) getChildAt(i).translationX = (i - page) * w + offset
    }

    fun go(p: Int, animate: Boolean = true) {
        val n = p.coerceIn(0, (childCount - 1).coerceAtLeast(0))
        val changed = n != page
        page = n
        val w = width.toFloat()
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            val to = (i - page) * w
            if (animate) c.animate().translationX(to).setDuration(220).start() else c.translationX = to
        }
        offset = 0f
        if (changed) onPage?.invoke(page)
    }

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y; dragging = false; vt?.recycle(); vt = VelocityTracker.obtain(); vt?.addMovement(e) }
            MotionEvent.ACTION_MOVE -> {
                vt?.addMovement(e)
                val dx = e.x - downX; val dy = e.y - downY
                if (!dragging && abs(dx) > slop * 2 && abs(dx) > abs(dy) * 1.5f) { dragging = true; downX = e.x }
            }
        }
        return dragging
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        vt?.addMovement(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                if (!dragging) return true
                var dx = e.x - downX
                if ((page == 0 && dx > 0) || (page == childCount - 1 && dx < 0)) dx *= 0.3f
                offset = dx
                layoutPages()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                vt?.computeCurrentVelocity(1000)
                val v = vt?.xVelocity ?: 0f
                vt?.recycle(); vt = null
                val w = width
                val target = when {
                    offset < -w / 3 || v < -1200 -> page + 1
                    offset > w / 3 || v > 1200 -> page - 1
                    else -> page
                }
                dragging = false
                offset = 0f
                go(target)
            }
        }
        return true
    }
}
