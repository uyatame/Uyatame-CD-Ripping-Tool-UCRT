package com.uyatame.ucrt

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 「アプリ」のタブの番号(UCRT をホーム画面として使うときだけ、下のバーに出る) */
const val TAB_APPS = 5

/** 「アプリ」タブ(UCRT をホーム画面として使うとき)のアプリ一覧。ランチャーに出るアプリを、名前順の格子で出す */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AppsScreen() {
    val ctx = LocalContext.current
    // アプリの入れ替わりの番号(増えると、一覧を作り直す)
    var gen by remember { mutableStateOf(0) }
    DisposableEffect(ctx) {
        val r = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) { gen++ }
        }
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED); addAction(Intent.ACTION_PACKAGE_REMOVED); addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        runCatching { ctx.registerReceiver(r, f) }
        onDispose { runCatching { ctx.unregisterReceiver(r) } }
    }
    val apps by produceState<List<HomeApps.Entry>?>(null, gen) {
        value = withContext(Dispatchers.IO) { HomeApps.list(ctx) }
    }
    var query by remember { mutableStateOf("") }
    val shown = remember(apps, query) {
        val q = query.trim().lowercase()
        apps?.filter { q.isEmpty() || it.label.lowercase().contains(q) || it.packageName.lowercase().contains(q) }
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                query, { query = it }, Modifier.weight(1f), singleLine = true,
                label = { Text(S(R.string.apps_search)) },
            )
            // 端末の設定(ホーム画面にしたあとの、設定への入り口)
            androidx.compose.material3.IconButton({
                if (!HomeApps.openDeviceSettings(ctx)) android.widget.Toast.makeText(ctx, S(R.string.apps_cannot_open_settings), android.widget.Toast.LENGTH_SHORT).show()
            }, Modifier.padding(start = 4.dp)) {
                androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Filled.Settings, S(R.string.apps_device_settings))
            }
        }
        if (shown == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(S(R.string.apps_loading)) }
        } else if (shown.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(S(R.string.apps_none)) }
        } else {
            LazyVerticalGrid(
                GridCells.Adaptive(76.dp), Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 16.dp + barInset()),
                verticalArrangement = Arrangement.spacedBy(8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(shown, key = { it.component.flattenToShortString() }) { e ->
                    AppCell(e) { ok -> if (!ok) android.widget.Toast.makeText(ctx, S(R.string.apps_cannot_launch), android.widget.Toast.LENGTH_SHORT).show() }
                }
            }
        }
    }
}

/** 1 つのアプリ(アイコンと名前)。タップで起動、長押しでアプリの情報 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppCell(e: HomeApps.Entry, onLaunched: (Boolean) -> Unit) {
    val ctx = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    val px = with(androidx.compose.ui.platform.LocalDensity.current) { 52.dp.roundToPx() }
    // アイコンは、画面に出たときに読み込む(全アプリ分を先に読み込まない)
    val icon by produceState<Bitmap?>(null, e.component) {
        value = withContext(Dispatchers.IO) { HomeApps.icon(ctx, e, px) }
    }
    Box {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                .combinedClickable(onClick = { onLaunched(HomeApps.launch(ctx, e)) }, onLongClick = { menu = true })
                .padding(vertical = 6.dp, horizontal = 2.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.size(52.dp)) {
                icon?.let { Image(it.asImageBitmap(), e.label, Modifier.size(52.dp)) }
            }
            Text(
                e.label, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.labelSmall,
                maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
            )
        }
        DropdownMenu(menu, { menu = false }) {
            DropdownMenuItem(text = { Text(S(R.string.apps_info)) }, onClick = { menu = false; HomeApps.openInfo(ctx, e) })
        }
    }
}
