package com.uyatame.ucrt

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * 画面右端の高速スクロールバー。つまみをドラッグすると、指の位置の項目の頭文字を大きな吹き出しで表示する。
 * total: 項目数 / first: 先頭に見えている項目 / visible: 見えている項目数
 */
@Composable
fun BoxScope.FastScroller(
    total: Int,
    first: Int,
    visible: Int,
    scrolling: Boolean,
    label: (Int) -> String,
    scrollTo: suspend (Int) -> Unit,
) {
    // 下の方まで進んだら、いちばん上へ戻るボタンを出す
    ScrollTopButton(first > maxOf(visible, 6)) { scrollTo(0) }
    if (total <= visible || total < 2) return
    val scope = rememberCoroutineScope()
    val curLabel by rememberUpdatedState(label)
    val curScrollTo by rememberUpdatedState(scrollTo)
    val curTotal by rememberUpdatedState(total)
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    var height by remember { mutableIntStateOf(1) }
    var dragging by remember { mutableStateOf(false) }
    var dragFrac by remember { mutableFloatStateOf(0f) }
    var shown by remember { mutableStateOf(false) }
    var lastLabel by remember { mutableStateOf("") }

    LaunchedEffect(scrolling, dragging) {
        if (scrolling || dragging) shown = true else { delay(1500); shown = false }
    }
    val alpha by animateFloatAsState(if (shown) 1f else 0f, label = "scrollbar")

    val frac = if (dragging) dragFrac else (first.toFloat() / (total - visible).coerceAtLeast(1)).coerceIn(0f, 1f)
    val thumbH = with(density) { 52.dp.toPx() }
    val thumbY = (frac * (height - thumbH)).coerceAtLeast(0f)
    val index = (frac * (total - 1)).roundToInt().coerceIn(0, total - 1)

    Box(
        // (リキッドグラスでは、下のタブ・ミニプレーヤーに隠れる部分を除いた高さで動かす)
        Modifier.align(Alignment.TopEnd).padding(bottom = barInset()).fillMaxHeight().width(40.dp)
            .onSizeChanged { height = it.height.coerceAtLeast(1) }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    fun jump(y: Float) {
                        dragFrac = ((y - thumbH / 2) / (height - thumbH).coerceAtLeast(1f)).coerceIn(0f, 1f)
                        val i = (dragFrac * (curTotal - 1)).roundToInt().coerceAtLeast(0)
                        scope.launch { curScrollTo(i) }
                        val l = curLabel(i)
                        if (l != lastLabel) {
                            lastLabel = l
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        }
                    }
                    dragging = true
                    jump(down.position.y)
                    down.consume()
                    while (true) {
                        val ev = awaitPointerEvent()
                        val c = ev.changes.firstOrNull() ?: break
                        if (!c.pressed) break
                        jump(c.position.y)
                        c.consume()
                    }
                    dragging = false
                }
            },
    ) {
        Box(
            Modifier.align(Alignment.TopEnd)
                .offset { IntOffset(0, thumbY.roundToInt()) }
                .padding(end = 4.dp)
                .alpha(alpha)
                .size(width = if (dragging) 8.dp else 6.dp, height = 52.dp)
                .background(
                    if (dragging) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    RoundedCornerShape(4.dp),
                ),
        )
    }
    // 頭文字の吹き出し(右下がとがったしずく型)
    AnimatedVisibility(
        visible = dragging,
        enter = fadeIn() + scaleIn(),
        exit = fadeOut() + scaleOut(),
        modifier = Modifier.align(Alignment.TopEnd)
            .offset { IntOffset(-with(density) { 52.dp.roundToPx() }, (thumbY + thumbH / 2 - with(density) { 72.dp.toPx() }).roundToInt().coerceAtLeast(0)) },
    ) {
        Box(
            Modifier.size(72.dp).background(
                MaterialTheme.colorScheme.secondary,
                RoundedCornerShape(topStartPercent = 50, topEndPercent = 50, bottomEndPercent = 8, bottomStartPercent = 50),
            ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                label(index), color = MaterialTheme.colorScheme.onSecondary,
                fontSize = 30.sp, fontWeight = FontWeight.Bold, maxLines = 1,
            )
        }
    }
}

/**
 * LazyColumn 用。スクロールの位置はこの中で読む(呼んだ画面の全体が、スクロールの 1 コマごとに描き直されないように)
 */
@Composable
fun BoxScope.FastScroller(state: LazyListState, label: (Int) -> String) {
    val total by remember(state) { derivedStateOf { state.layoutInfo.totalItemsCount } }
    val visible by remember(state) { derivedStateOf { state.layoutInfo.visibleItemsInfo.size } }
    FastScroller(total, state.firstVisibleItemIndex, visible, state.isScrollInProgress, label) { state.scrollToItem(it) }
}

/** LazyVerticalGrid 用(同上) */
@Composable
fun BoxScope.FastScroller(state: LazyGridState, label: (Int) -> String) {
    val total by remember(state) { derivedStateOf { state.layoutInfo.totalItemsCount } }
    val visible by remember(state) { derivedStateOf { state.layoutInfo.visibleItemsInfo.size } }
    FastScroller(total, state.firstVisibleItemIndex, visible, state.isScrollInProgress, label) { state.scrollToItem(it) }
}

/** 並べ替えの基準となる文字列の頭文字(英字は大文字に、数字・記号は #) */
fun initialOf(s: String): String {
    val c = s.trim().firstOrNull() ?: return "#"
    return when {
        c.isLetter() -> c.uppercaseChar().toString()
        c.isDigit() -> "#"
        else -> c.toString()
    }
}

/** いちばん上へ戻るボタン(右下)。show のときだけ出る */
@Composable
fun BoxScope.ScrollTopButton(show: Boolean, scrollTop: suspend () -> Unit) {
    val scope = rememberCoroutineScope()
    androidx.compose.animation.AnimatedVisibility(
        visible = show,
        enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.scaleIn(),
        exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.scaleOut(),
        // リキッドグラスでは中身が下のタブ・ミニプレーヤーの後ろまで続くので、その分だけ上に出す(隠れて押せなくならないように)
        modifier = Modifier.align(Alignment.BottomEnd).padding(end = 48.dp, bottom = 16.dp + barInset()),
    ) {
        androidx.compose.material3.SmallFloatingActionButton(
            onClick = { scope.launch { scrollTop() } },
            containerColor = androidx.compose.material3.MaterialTheme.colorScheme.secondaryContainer,
        ) {
            androidx.compose.material3.Icon(
                androidx.compose.material.icons.Icons.Filled.KeyboardArrowUp,
                com.uyatame.ucrt.S(com.uyatame.ucrt.R.string.back_to_top),
            )
        }
    }
}
