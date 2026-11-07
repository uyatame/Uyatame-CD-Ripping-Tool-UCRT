package com.uyatame.ucrt

import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.uyatame.ucrt.data.AppSettings
import com.uyatame.ucrt.network.NetHub
import com.uyatame.ucrt.network.NetProtocol
import com.uyatame.ucrt.network.NetSource
import com.uyatame.ucrt.network.NetSourceStore
import com.uyatame.ucrt.network.NetworkProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** ライブラリの「ネットワーク」: NAS などの接続先の一覧と、追加・編集・ライブラリへの登録 */
@Composable
internal fun NetworkPage(vm: MainViewModel, s: AppSettings) {
    val ctx = LocalContext.current
    // 一覧を描き直す合図(保存・削除のたびに増える)
    var gen by remember { mutableStateOf(0) }
    val sources = remember(gen) { NetSourceStore.all(ctx) }
    var editing by remember { mutableStateOf<NetSource?>(null) }
    var deleting by remember { mutableStateOf<NetSource?>(null) }
    editing?.let { cur ->
        NetEditDialog(cur, { editing = null }) { saved ->
            NetSourceStore.save(ctx, saved)
            NetHub.invalidate(saved.id)
            editing = null; gen++
        }
    }
    deleting?.let { d ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            text = { Text(S(R.string.net_delete_confirm, d.name.ifEmpty { d.host })) },
            confirmButton = {
                TextButton({
                    // この接続先から登録したライブラリのフォルダも外す
                    s.libraryFolders.filter { belongsTo(ctx, it, d.id) }.forEach { vm.removeLibraryFolder(it) }
                    NetSourceStore.remove(ctx, d.id); NetHub.invalidate(d.id)
                    deleting = null; gen++
                }) { Text(S(R.string.delete)) }
            },
            dismissButton = { TextButton({ deleting = null }) { Text(S(R.string.cancel)) } },
        )
    }
    Column(Modifier.fillMaxSize()) {
        PageTop(vm, S(R.string.network)) {
            IconButton({ editing = NetSource(NetSourceStore.newId(), "", NetProtocol.SMB, "", 0, "", "", "") }) {
                Icon(Icons.Filled.Add, S(R.string.net_add))
            }
        }
        if (sources.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(S(R.string.net_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp + barInset())) {
                item {
                    Text(
                        S(R.string.net_section_hint), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                items(sources, key = { it.id }) { src ->
                    val root = NetworkProvider.treeUri(ctx, src.id).toString()
                    val inLib = root in s.libraryFolders
                    Column(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(16.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(12.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RoundIcon(AppIcons.Network, 40)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(src.name.ifEmpty { src.host }, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    src.summary(), style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                            }
                            IconButton({ editing = src }) { Icon(Icons.Filled.Edit, S(R.string.net_edit)) }
                            IconButton({ deleting = src }) { Icon(Icons.Filled.Delete, S(R.string.delete)) }
                        }
                        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton({ vm.libPush("netdir:" + NetworkProvider.docId(src.id, "/")) }) { Text(S(R.string.net_browse)) }
                            if (inLib) {
                                OutlinedButton({ vm.removeLibraryFolder(root) }) { Text(S(R.string.net_remove_library)) }
                            }
                        }
                        if (inLib) {
                            Text(
                                S(R.string.net_in_library), style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun belongsTo(ctx: android.content.Context, folder: String, sourceId: String): Boolean {
    val u = Uri.parse(folder)
    if (u.authority != NetworkProvider.authority(ctx)) return false
    val id = runCatching { DocumentsContract.getTreeDocumentId(u) }.getOrNull() ?: return false
    return NetworkProvider.parse(id).first == sourceId
}

/** 接続先の追加・編集 */
@Composable
private fun NetEditDialog(init: NetSource, onDismiss: () -> Unit, onSave: (NetSource) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(init.name) }
    var proto by remember { mutableStateOf(init.protocol) }
    var host by remember { mutableStateOf(init.host) }
    var port by remember { mutableStateOf(if (init.port > 0) init.port.toString() else "") }
    var user by remember { mutableStateOf(init.user) }
    var pass by remember { mutableStateOf(init.password) }
    var path by remember { mutableStateOf(init.path) }
    var menu by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }

    fun build() = init.copy(
        name = name.trim(), protocol = proto, host = host.trim(), port = port.trim().toIntOrNull() ?: 0,
        user = user.trim(), password = pass, path = path.trim(),
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(S(if (init.host.isEmpty()) R.string.net_add else R.string.net_edit)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(S(R.string.net_name)) })
                Box {
                    OutlinedButton({ menu = true }, Modifier.fillMaxWidth()) { Text(proto.label, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    DropdownMenu(menu, { menu = false }) {
                        NetProtocol.values().forEach { p ->
                            DropdownMenuItem(text = { Text(p.label) }, onClick = { proto = p; menu = false })
                        }
                    }
                }
                if (!NetHub.supported(proto)) {
                    Text(S(R.string.net_needs_android8), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                OutlinedTextField(
                    host, { host = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(S(R.string.net_host)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                OutlinedTextField(
                    port, { port = it.filter(Char::isDigit).take(5) }, Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text(S(R.string.net_port)) }, placeholder = { Text(S(R.string.net_port_hint, proto.defaultPort)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                OutlinedTextField(user, { user = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(S(R.string.net_user)) })
                OutlinedTextField(
                    pass, { pass = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(S(R.string.net_password)) },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                OutlinedTextField(
                    path, { path = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(S(R.string.net_path)) },
                    supportingText = { Text(S(if (proto == NetProtocol.SMB) R.string.net_path_hint_smb else R.string.net_path_hint)) },
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(
                        {
                            testing = true; result = S(R.string.net_testing)
                            val src = build()
                            scope.launch {
                                val err = withContext(Dispatchers.IO) { NetHub.test(ctx, src) }
                                result = if (err == null) S(R.string.net_test_ok) else S(R.string.net_test_fail, err)
                                testing = false
                            }
                        },
                        enabled = !testing && host.isNotBlank() && NetHub.supported(proto),
                    ) { Text(S(R.string.net_test)) }
                    result?.let { Text(it, Modifier.padding(start = 8.dp).weight(1f), style = MaterialTheme.typography.bodySmall) }
                }
                Text(S(R.string.net_notice), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton({ onSave(build()) }, enabled = host.isNotBlank()) { Text(S(R.string.save)) } },
        dismissButton = { TextButton(onDismiss) { Text(S(R.string.cancel)) } },
    )
}

/** ブラウズ画面の 1 件(フォルダか、音楽ファイル) */
private class NetItem(val docId: String, val name: String, val isDir: Boolean)

/** ネットワークのフォルダを見て回る。音楽ファイルはタップでそのまま再生できる(ライブラリに入れなくてよい)。好きなフォルダをライブラリに追加もできる */
@Composable
internal fun NetworkBrowsePage(vm: MainViewModel, docId: String) {
    val ctx = LocalContext.current
    val (sid, path) = NetworkProvider.parse(docId)
    val s by vm.settings.collectAsState()
    val src = NetSourceStore.get(ctx, sid)
    val tree = NetworkProvider.treeUri(ctx, sid)
    val here = DocumentsContract.buildTreeDocumentUri(NetworkProvider.authority(ctx), docId).toString()
    var browseError by remember(docId) { mutableStateOf<String?>(null) }
    val items by produceState<List<NetItem>?>(null, docId) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val u = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
                val out = ArrayList<NetItem>()
                ctx.contentResolver.query(
                    u, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE),
                    null, null, null,
                )?.use { c ->
                    browseError = c.extras?.getString(DocumentsContract.EXTRA_ERROR)
                    while (c.moveToNext()) {
                        val name = c.getString(1) ?: continue
                        val dir = c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR
                        if (dir || name.substringAfterLast('.', "").lowercase() in com.uyatame.ucrt.library.LIB_AUDIO_EXT) out.add(NetItem(c.getString(0), name, dir))
                    }
                }
                // フォルダを先に、あとは名前順(曲は、名前の数字の順)
                out.sortedWith(compareBy({ !it.isDir }, { it.name.lowercase() })) as List<NetItem>
            }.getOrElse { browseError = it.message ?: it.javaClass.simpleName; emptyList() }
        }
    }
    val files = items?.filter { !it.isDir }.orEmpty()
    /** このフォルダの音楽ファイルを、from 番目から再生する(ライブラリには入れない) */
    fun play(from: Int) {
        val album = path.substringAfterLast('/').ifEmpty { src?.name ?: "" }
        val tracks = files.mapIndexed { i, f ->
            com.uyatame.ucrt.library.LibTrack(
                DocumentsContract.buildDocumentUriUsingTree(tree, f.docId).toString(), f.name.substringBeforeLast('.'), "", album, "",
                i + 1, 1, 0L, docId, null, 0L,
            )
        }
        // 曲は端末に取り込んでから鳴るので、待っている間に止まって見えないよう知らせる
        android.widget.Toast.makeText(ctx, S(R.string.net_fetching), android.widget.Toast.LENGTH_SHORT).show()
        vm.playTracks(tracks, from)
    }
    Column(Modifier.fillMaxSize()) {
        PageTop(vm, if (path == "/") (src?.name?.ifEmpty { src.host } ?: S(R.string.network)) else path.substringAfterLast('/'), path)
        Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (files.isNotEmpty()) FilledTonalButton({ play(0) }) { Text(S(R.string.net_play_folder)) }
            val inLib = here in s.libraryFolders
            if (inLib) OutlinedButton({ vm.removeLibraryFolder(here) }) { Text(S(R.string.net_remove_library)) }

        }
        val list = items
        when {
            list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(S(R.string.net_loading)) }
            list.isEmpty() -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(
                    browseError?.let { S(R.string.net_test_fail, it) } ?: S(R.string.net_folder_empty),
                    color = if (browseError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            else -> LazyColumn(contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = 24.dp + barInset())) {
                items(list, key = { it.docId }) { it ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                            .clickable { if (it.isDir) vm.libPush("netdir:${it.docId}") else play(files.indexOf(it)) }
                            .padding(vertical = 8.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RoundIcon(if (it.isDir) AppIcons.Folder else AppIcons.MusicNote, 40)
                        Spacer(Modifier.width(12.dp))
                        Text(it.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}
