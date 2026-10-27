package com.uyatame.ucrt.library

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.provider.Settings
import com.uyatame.ucrt.R
import com.uyatame.ucrt.S
import java.io.File
import java.io.InputStream

/**
 * 保存場所の名前(内部ストレージ / SDカードなど)と、「すべてのファイルへのアクセス」の扱い。
 * 許可があるときは、フォルダの一覧と曲情報の読み込みをファイルから直接行う(SAF を通すより速い)。
 */
object StorageAccess {
    private const val EXTERNAL = "com.android.externalstorage.documents"
    @Volatile private var app: Context? = null

    fun init(ctx: Context) {
        app = ctx.applicationContext
    }

    /** 「すべてのファイルへのアクセス」が許可されているか */
    val allFiles: Boolean get() = android.os.Build.VERSION.SDK_INT >= 30 && runCatching { Environment.isExternalStorageManager() }.getOrDefault(false)

    /** 「すべてのファイルへのアクセス」の許可を出せる Android か(Android 11 から) */
    val allFilesSupported: Boolean get() = android.os.Build.VERSION.SDK_INT >= 30

    /** 許可を出す設定画面を開く */
    fun requestIntent(ctx: Context): Intent =
        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + ctx.packageName))

    fun fallbackIntent(): Intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)

    /** ドキュメント ID("primary:Music" や "1234-ABCD:Music")の保存場所の名前 */
    fun volumeName(volId: String): String {
        if (volId == "primary") return S(R.string.internal_storage)
        if (volId == "home") return S(R.string.documents)
        val ctx = app
        if (ctx != null) {
            val sm = androidx.core.content.ContextCompat.getSystemService(ctx, StorageManager::class.java)!!
            // 保存場所の一覧は Android 7.0 から
            val v = if (android.os.Build.VERSION.SDK_INT >= 24) {
                runCatching { sm.storageVolumes }.getOrNull()?.firstOrNull { it.uuid.equals(volId, ignoreCase = true) }
            } else null
            if (v != null) {
                val desc = runCatching { v.getDescription(ctx) }.getOrNull()?.trim().orEmpty()
                if (desc.isNotEmpty()) return desc
                return if (v.isRemovable) S(R.string.sd_card) else S(R.string.external_storage)
            }
        }
        return S(R.string.sd_card) + " ($volId)"
    }

    /** フォルダの URI を「SDカード / Music」のような表示にする */
    fun label(uri: String): String {
        val u = Uri.parse(uri)
        val id = runCatching {
            if (DocumentsContract.isTreeUri(u)) DocumentsContract.getTreeDocumentId(u) else DocumentsContract.getDocumentId(u)
        }.getOrNull() ?: u.lastPathSegment ?: return S(R.string.selected)
        val vol = id.substringBefore(':')
        val path = id.substringAfter(':', "")
        val name = volumeName(vol)
        return if (path.isEmpty()) S(R.string.root, name) else "$name / $path"
    }

    /** ドキュメント ID から実際のファイル(許可が無い・分からなければ null) */
    fun fileOfDocId(docId: String): File? {
        if (!allFiles) return null
        val vol = docId.substringBefore(':', "")
        if (vol.isEmpty()) return null
        val rel = docId.substringAfter(':', "")
        val root = if (vol == "primary") Environment.getExternalStorageDirectory() else File("/storage/$vol")
        val f = if (rel.isEmpty()) root else File(root, rel)
        return f.takeIf { it.exists() }
    }

    /** 曲の URI から実際のファイル(外部ストレージの URI で、許可があるときだけ) */
    fun fileOf(uri: Uri): File? {
        if (uri.authority != EXTERNAL) return null
        val id = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull() ?: return null
        return fileOfDocId(id)
    }

    fun isExternalTree(tree: Uri): Boolean = tree.authority == EXTERNAL

    /** 読み込み用に開く(ファイルから直接読めればそちらを使う) */
    fun open(ctx: Context, uri: Uri): InputStream? {
        fileOf(uri)?.let { f -> runCatching { return f.inputStream() } }
        return ctx.contentResolver.openInputStream(uri)
    }
}
