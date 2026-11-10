package com.uyatame.ucrt

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.uyatame.ucrt.data.AppSettings
import com.uyatame.ucrt.data.Keys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 表示の設定の「テーマ」: 組み込み・読み込んだテーマから選ぶ。ファイルから読み込む・ひな形として書き出す・削除する */
@Composable
fun ThemeSection(vm: MainViewModel, s: AppSettings) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext
    val scope = rememberCoroutineScope()
    val rev = UiThemes.revision
    val themes = remember(rev) { UiThemes.all(app) }
    val current = themes.firstOrNull { it.id == s.uiTheme } ?: UiThemes.classic
    var deleting by remember { mutableStateOf<UiTheme?>(null) }
    fun toast(id: Int) = android.widget.Toast.makeText(ctx, S(id), android.widget.Toast.LENGTH_SHORT).show()
    fun nameOf(t: UiTheme) = UiThemes.builtinNameRes(t.id)?.takeIf { t.builtin }?.let { S(it) } ?: t.name
    fun descOf(t: UiTheme): String {
        val d = UiThemes.builtinDescRes(t.id)?.takeIf { t.builtin }?.let { S(it) } ?: t.description
        return if (t.author.isNotEmpty()) (if (d.isNotEmpty()) "$d · " else "") + S(R.string.theme_by, t.author) else d
    }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        if (u != null) scope.launch {
            val t = runCatching {
                withContext(Dispatchers.IO) {
                    val text = ctx.contentResolver.openInputStream(u)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: error("open")
                    UiThemes.importTheme(app, text)
                }
            }.getOrNull()
            if (t != null) {
                vm.set(Keys.uiTheme, t.id)
                toast(R.string.theme_imported)
            } else toast(R.string.theme_bad_file)
        }
    }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { u ->
        if (u != null) scope.launch {
            val ok = runCatching {
                val text = UiThemes.template(current, nameOf(current))
                withContext(Dispatchers.IO) {
                    ctx.contentResolver.openOutputStream(u, "wt")?.use { it.write(text.toByteArray()) } ?: error("open")
                }
            }.isSuccess
            toast(if (ok) R.string.theme_exported else R.string.theme_failed)
        }
    }

    deleting?.let { t ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(S(R.string.theme_delete_title, nameOf(t))) },
            confirmButton = {
                TextButton({
                    deleting = null
                    if (s.uiTheme == t.id) vm.set(Keys.uiTheme, UiThemes.DEFAULT_ID)
                    UiThemes.delete(app, t.id)
                }) { Text(S(R.string.delete)) }
            },
            dismissButton = { TextButton({ deleting = null }) { Text(S(R.string.cancel)) } },
        )
    }

    Section(S(R.string.ui_theme)) {
        Text(S(R.string.ui_theme_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        for (t in themes) {
            Row(
                Modifier.fillMaxWidth().clickable { vm.set(Keys.uiTheme, t.id) }.padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = t.id == current.id, onClick = { vm.set(Keys.uiTheme, t.id) })
                Column(Modifier.weight(1f)) {
                    Text(nameOf(t), style = MaterialTheme.typography.bodyLarge)
                    val d = descOf(t)
                    if (d.isNotEmpty()) {
                        Text(d, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (!t.builtin) {
                    IconButton({ deleting = t }) { Icon(Icons.Filled.Delete, S(R.string.delete)) }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton({ importer.launch(arrayOf("application/json", "application/octet-stream", "text/plain", "*/*")) }) {
                Text(S(R.string.theme_import))
            }
            OutlinedButton({ exporter.launch(current.id + ".ucrttheme.json") }) { Text(S(R.string.theme_export)) }
        }
        Text(S(R.string.theme_export_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
