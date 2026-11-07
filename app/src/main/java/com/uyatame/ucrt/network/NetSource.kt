package com.uyatame.ucrt.network

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** NAS などのネットワーク上の場所につなぐ方法(プロトコル) */
enum class NetProtocol(val id: String, private val fixedLabel: String, val defaultPort: Int) {
    SMB("smb", "", 445),
    SFTP("sftp", "SFTP (SSH)", 22),
    FTP("ftp", "FTP", 21),
    WEBDAV("webdav", "WebDAV (HTTP)", 80),
    WEBDAVS("webdavs", "WebDAV (HTTPS)", 443);

    /** 画面に出す名前(SMB の説明だけは言語に合わせる) */
    val label: String get() = if (this == SMB) com.uyatame.ucrt.S(com.uyatame.ucrt.R.string.net_proto_smb) else fixedLabel

    companion object {
        fun of(id: String?): NetProtocol = values().firstOrNull { it.id == id } ?: SMB
    }
}

/**
 * ネットワーク上の 1 か所の接続先。
 * [path] は、つないだあとの最初のフォルダ(SMB では先頭が共有名。例: "music/FLAC")。空なら一番上から。
 */
data class NetSource(
    val id: String,
    val name: String,
    val protocol: NetProtocol,
    val host: String,
    /** 0 のときはプロトコルの標準のポート */
    val port: Int,
    val user: String,
    val password: String,
    val path: String,
    /** SFTP: 初めてつないだときに覚えたホスト鍵の指紋(空なら、まだ覚えていない) */
    val hostKey: String = "",
) {
    val effectivePort: Int get() = if (port in 1..65535) port else protocol.defaultPort

    /** 一覧などに出す短い説明(パスワードは含めない) */
    fun summary(): String = "${protocol.id}://$host:$effectivePort/" + path.trim('/')
}

/**
 * 接続先の一覧の保存。アプリ専用の領域に JSON で置く(バックアップはしない設定)。
 * パスワードは、端末の Keystore の鍵で暗号化してから書く(鍵が使えない端末では、平文のまま)。
 */
object NetSourceStore {
    private const val FILE = "network_sources.json"
    private val lock = Any()
    @Volatile private var cache: List<NetSource>? = null

    fun all(ctx: Context): List<NetSource> = synchronized(lock) {
        cache ?: load(ctx).also { cache = it }
    }

    fun get(ctx: Context, id: String): NetSource? = all(ctx).firstOrNull { it.id == id }

    fun save(ctx: Context, src: NetSource) = synchronized(lock) {
        val list = all(ctx).toMutableList()
        val i = list.indexOfFirst { it.id == src.id }
        if (i >= 0) list[i] = src else list.add(src)
        write(ctx, list)
    }

    fun remove(ctx: Context, id: String) = synchronized(lock) {
        write(ctx, all(ctx).filter { it.id != id })
    }

    fun newId(): String = java.lang.Long.toString(System.currentTimeMillis(), 36) + java.lang.Integer.toString((Math.random() * 1296).toInt(), 36)

    private fun write(ctx: Context, list: List<NetSource>) {
        cache = list
        val arr = JSONArray()
        for (s in list) {
            arr.put(
                JSONObject().put("id", s.id).put("name", s.name).put("protocol", s.protocol.id).put("host", s.host)
                    .put("port", s.port).put("user", s.user).put("password", NetSecret.encrypt(s.password)).put("path", s.path).put("hostKey", s.hostKey),
            )
        }
        val f = File(ctx.filesDir, FILE)
        val tmp = File(ctx.filesDir, "$FILE.tmp")
        tmp.writeText(arr.toString())
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }

    private fun load(ctx: Context): List<NetSource> {
        val f = File(ctx.filesDir, FILE)
        if (!f.exists()) return emptyList()
        return runCatching {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                NetSource(
                    o.getString("id"), o.optString("name"), NetProtocol.of(o.optString("protocol")), o.optString("host"),
                    o.optInt("port", 0), o.optString("user"), NetSecret.decrypt(o.optString("password")), o.optString("path"), o.optString("hostKey"),
                )
            }
        }.getOrDefault(emptyList())
    }
}

/** パスワードの暗号化(Android Keystore の AES-GCM)。使えないときは「p:」をつけた平文 */
internal object NetSecret {
    private const val ALIAS = "ucrt_net_secret"

    private fun key(): javax.crypto.SecretKey? = runCatching {
        val ks = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? javax.crypto.SecretKey) ?: run {
            val g = javax.crypto.KeyGenerator.getInstance(android.security.keystore.KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            g.init(
                android.security.keystore.KeyGenParameterSpec.Builder(
                    ALIAS, android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or android.security.keystore.KeyProperties.PURPOSE_DECRYPT,
                ).setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build(),
            )
            g.generateKey()
        }
    }.getOrNull()

    fun encrypt(plain: String): String {
        if (plain.isEmpty()) return ""
        val k = key() ?: return "p:$plain"
        return runCatching {
            val c = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
            c.init(javax.crypto.Cipher.ENCRYPT_MODE, k)
            val ct = c.doFinal(plain.toByteArray(Charsets.UTF_8))
            "e:" + android.util.Base64.encodeToString(c.iv + ct, android.util.Base64.NO_WRAP)
        }.getOrDefault("p:$plain")
    }

    fun decrypt(stored: String): String {
        if (stored.isEmpty()) return ""
        if (stored.startsWith("p:")) return stored.substring(2)
        if (!stored.startsWith("e:")) return stored
        val k = key() ?: return ""
        return runCatching {
            val raw = android.util.Base64.decode(stored.substring(2), android.util.Base64.NO_WRAP)
            val c = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
            c.init(javax.crypto.Cipher.DECRYPT_MODE, k, javax.crypto.spec.GCMParameterSpec(128, raw, 0, 12))
            String(c.doFinal(raw, 12, raw.size - 12), Charsets.UTF_8)
        }.getOrDefault("")
    }
}
