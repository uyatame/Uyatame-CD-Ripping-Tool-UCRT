package com.uyatame.ucrt.legacy

import android.content.Context
import android.content.SharedPreferences
import com.uyatame.ucrt.data.AudioFormat

/**
 * 設定(本家の AppSettings と同じ名前で保存する)。書き換えたら Bus.SETTINGS で画面に知らせる
 */
class Prefs(ctx: Context) {
    val sp: SharedPreferences = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private fun b(k: String, d: Boolean) = sp.getBoolean(k, d)
    private fun i(k: String, d: Int) = sp.getInt(k, d)
    private fun s(k: String, d: String) = sp.getString(k, d) ?: d

    fun set(k: String, v: Any?) {
        val e = sp.edit()
        when (v) {
            null -> e.remove(k)
            is Boolean -> e.putBoolean(k, v)
            is Int -> e.putInt(k, v)
            is Float -> e.putFloat(k, v)
            is Long -> e.putLong(k, v)
            is String -> e.putString(k, v)
            is Set<*> -> e.putStringSet(k, v.map { it.toString() }.toSet())
        }
        e.apply()
        Bus.emit(Bus.SETTINGS)
    }

    // ---- 取り込み ----
    val format: AudioFormat get() = AudioFormat.entries.getOrElse(i("format", AudioFormat.FLAC.ordinal)) { AudioFormat.FLAC }
    val flacLevel get() = i("flacLevel", 5)
    val aacKbps get() = i("aacKbps", 256)
    val mp3Kbps get() = i("mp3Kbps", 320)
    val mp3Quality get() = i("mp3Quality", 2)
    val speedX get() = i("speedX", 0)
    val sectorsPerRead get() = i("sectorsPerRead", 13)
    val retries get() = i("retries", 5)
    val useC2 get() = b("useC2", false)
    val abortOnError get() = b("abortOnError", false)
    val offsetSamples get() = i("offsetSamples", 0)
    val ejectAfter get() = b("ejectAfter", false)
    /** 取り込んだ曲の保存先(フォルダの場所。未設定なら null) */
    val outputDir: String? get() = sp.getString("outputDir", null)
    /** ライブラリに入れるフォルダ(保存先のほか) */
    val libraryFolders: Set<String> get() = sp.getStringSet("libraryFolders", emptySet()) ?: emptySet()
    val folderMode get() = i("folderMode", 0)
    val fileNameMode get() = i("fileNameMode", 0)
    val autoMeta get() = b("autoMeta", true)
    val contactEmail get() = s("contactEmail", "")
    val autoOpenDisc get() = b("autoOpenDisc", true)
    val saveCover get() = b("saveCover", true)
    val embedCover get() = b("embedCover", true)
    val cdPlaySpeed get() = i("cdPlaySpeed", 4)
    val keepScreenOn get() = b("keepScreenOn", true)

    // ---- 表示 ----
    val themeMode get() = i("themeMode", 0)
    val themeColor get() = i("themeColor", 0)
    val textColor get() = i("textColor", 0)
    val listArt get() = b("listArt", true)
    val trackArt get() = b("trackArt", true)
    val ambientPlayer get() = b("ambientPlayer", true)
    val libraryList get() = b("libraryList", false)
    val libTiles get() = s("libTiles", "")
    val verboseLog get() = b("verboseLog", false)
    val updateCheck get() = b("updateCheck", true)
    val updateSkip get() = s("updateSkip", "")

    // ---- 再生 ----
    val gapless get() = b("gapless", true)
    val rgEnabled get() = b("rgEnabled", false)
    val rgMode get() = i("rgMode", 0)
    val rgPreamp get() = i("rgPreamp", 0)
    val rgNoClip get() = b("rgNoClip", true)
    val sourceDirect get() = b("sourceDirect", false)
    val eqEnabled get() = b("eqEnabled", false)
    val eqMode get() = i("eqMode", 0)
    val eqPreamp get() = sp.getFloat("eqPreamp", 0f)
    val eqAutoPreamp get() = b("eqAutoPreamp", true)
    val eqBands get() = s("eqBands", "")
    val eqSimpleGains get() = s("eqSimpleGains", "")
    val eqCustom get() = s("eqCustom", "")
    val eqPreset get() = i("eqPreset", 0)
    val dsdMode get() = i("dsdMode", 0)
    val usbDirect get() = b("usbDirect", false)
    val usbVolume get() = i("usbVolume", -1)
    val usbStabilize get() = b("usbStabilize", true)

    /** 実際に使う ReplayGain(0 = 使わない、1 = 曲ごと、2 = アルバムごと) */
    val rgEffective get() = if (sourceDirect || !rgEnabled) 0 else rgMode + 1
    val eqActive get() = eqEnabled && !sourceDirect
}
