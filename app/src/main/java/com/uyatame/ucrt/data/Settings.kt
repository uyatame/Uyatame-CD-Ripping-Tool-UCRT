package com.uyatame.ucrt.data

import com.uyatame.ucrt.T

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

// 設定のファイルが壊れていても起動できるよう、壊れていれば初期値に戻す
val Context.dataStore by preferencesDataStore(
    name = "settings",
    corruptionHandler = androidx.datastore.core.handlers.ReplaceFileCorruptionHandler {
        androidx.datastore.preferences.core.emptyPreferences()
    },
)

enum class AudioFormat(val label: String, val ext: String, val mime: String) {
    WAV("WAV", "wav", "audio/x-wav"),
    FLAC("FLAC", "flac", "audio/flac"),
    AAC("AAC", "m4a", "audio/mp4"),
    MP3("MP3", "mp3", "audio/mpeg"),
}

val MP3_RATES = listOf(128, 160, 192, 224, 256, 320)

class QualityPreset(
    val title: String,
    val desc: String,
    val format: AudioFormat,
    val flacLevel: Int,
    val aacKbps: Int,
    val mp3Kbps: Int = 320,
)

val QUALITY_PRESETS: List<QualityPreset> get() = listOf(
    QualityPreset(T("ロスレス(FLAC)", "Lossless (FLAC)"), T("CDと同じ音質のまま容量を約半分に。迷ったらこれ", "Same quality as the CD at about half the size. Recommended"), AudioFormat.FLAC, 5, 256),
    QualityPreset(T("高音質(AAC 320kbps)", "High quality (AAC 320 kbps)"), T("聴き分けが難しいほどの高音質。1曲 約10MB", "Nearly indistinguishable from the CD. About 10 MB per song"), AudioFormat.AAC, 5, 320),
    QualityPreset(T("標準(AAC 256kbps)", "Standard (AAC 256 kbps)"), T("音質と容量のバランス型。1曲 約8MB", "Balanced quality and size. About 8 MB per song"), AudioFormat.AAC, 5, 256),
    QualityPreset(T("互換性重視(MP3 320kbps)", "Compatible (MP3 320 kbps)"), T("古いプレーヤーやカーオーディオでも再生しやすい形式。1曲 約10MB", "Plays on older players and car audio. About 10 MB per song"), AudioFormat.MP3, 5, 256, 320),
    QualityPreset(T("省容量(AAC 128kbps)", "Small (AAC 128 kbps)"), T("容量を優先。1曲 約4MB", "Prioritizes size. About 4 MB per song"), AudioFormat.AAC, 5, 128),
    QualityPreset(T("無圧縮(WAV)", "Uncompressed (WAV)"), T("CD と同じデータのまま保存。容量は FLAC の約 2 倍です(曲情報とジャケットも保存します)", "Saves the CD data as is. About twice the size of FLAC (track info and cover art are saved too)"), AudioFormat.WAV, 5, 256),
)

data class AppSettings(
    val format: AudioFormat = AudioFormat.FLAC,
    val flacLevel: Int = 5,
    val aacKbps: Int = 256,
    val mp3Kbps: Int = 320,
    val mp3Quality: Int = 2,
    val speedX: Int = 0,
    val sectorsPerRead: Int = 13,
    val retries: Int = 5,
    val useC2: Boolean = false,
    val abortOnError: Boolean = false,
    val offsetSamples: Int = 0,
    val ejectAfter: Boolean = false,
    val outputUri: String? = null,
    val libraryFolders: Set<String> = emptySet(),
    val folderMode: Int = 0,
    val fileNameMode: Int = 0,
    val autoMeta: Boolean = true,
    val useGnudb: Boolean = true,
    val contactEmail: String = "",
    val autoOpenDisc: Boolean = true,
    val saveCover: Boolean = true,
    val embedCover: Boolean = true,
    val cdPlaySpeed: Int = 4,
    val keepScreenOn: Boolean = true,
    val themeMode: Int = 0,
    /** タブレットモード: 0 = 自動(画面の短い辺が 600dp 以上)、1 = オン、2 = オフ */
    val tabletMode: Int = 0,
    /** 一覧(ライブラリ・再生リストなど)にジャケットを表示する */
    val listArt: Boolean = true,
    val dynamicColor: Boolean = true,
    val ambientPlayer: Boolean = true,
    val bitPerfect: Boolean = false,
    val usbDirect: Boolean = false,
    /** -1 = 自動(USB から音量を変えられる DAC は 50、変えられない DAC は 100) */
    val usbVolume: Int = -1,
    val dsdMode: Int = 0,
    val dsdSwap: Boolean = false,
    /** 出力先(空なら自動。"種類|アドレス") */
    val outputDevice: String = "",
    val eqEnabled: Boolean = false,
    val eqPreset: Int = 0,
    val eqGains: String = "",
    val eqPreamp: Float = 0f,
    val eqAutoPreamp: Boolean = true,
    val libraryList: Boolean = false,
    val verboseLog: Boolean = false,
    /** ライブラリのトップに並べるカテゴリーの順番(カンマ区切り。先頭の "-" は非表示) */
    val libTiles: String = "",
    /** ギャップレス再生 */
    val gapless: Boolean = true,
    /** ソースダイレクト: オンなら、イコライザーと DAR を使わずに音源のまま出す */
    val sourceDirect: Boolean = false,
    /** 起動したときに、新しい版が出ていないか確かめる */
    val updateCheck: Boolean = true,
    /** 「この版は通知しない」にした版 */
    val updateSkip: String = "",
    /** イコライザーの帯域(パラメトリック EQ。空なら以前の 10 バンドの eqGains から作る) */
    val eqBands: String = "",
    /** DAR: 0 = オフ, 1 = PCM アップサンプリング, 2 = DSD に変換 */
    val darMode: Int = 0,
    /** DAR の PCM アップサンプリングの上限: 0 = DAC の最大, 1 = 2 倍, 2 = 4 倍, 3 = 8 倍, 4 = 16 倍 */
    val darPcm: Int = 0,
    /** DAR の DSD 変換の周波数: 0 = DSD64, 1 = DSD128, 2 = DSD256 */
    val darDsd: Int = 0,
    /** DAR の PCM アップサンプリングを整数倍だけで行う(通常の出力で、整数倍にできない出力先なら仕方なく合わせる) */
    val darInteger: Boolean = true,
)

/** イコライザーの帯域(保存されていなければ、以前の 10 バンドの設定から作る) */
val AppSettings.eqBandList: List<com.uyatame.ucrt.player.EqBand>
    get() = if (eqBands.isNotEmpty()) com.uyatame.ucrt.player.ParamEq.decode(eqBands)
    else com.uyatame.ucrt.player.ParamEq.fromGains(com.uyatame.ucrt.player.EqBands.decode(eqGains))

/** 実際にイコライザーを掛けるか(ソースダイレクトのときは掛けない) */
val AppSettings.eqActive: Boolean get() = eqEnabled && !sourceDirect

/** 実際に使う DAR の方式(ソースダイレクトのときはオフ) */
val AppSettings.darEffective: Int get() = if (sourceDirect) 0 else darMode

/** 出力方式: 0 = 通常, 2 = ビットパーフェクト(USB DAC 直接出力)。以前の版の bitPerfect もこちらに移す */
val AppSettings.outputMode: Int get() = if (usbDirect || bitPerfect) 2 else 0

fun AppSettings.presetIndex(): Int = QUALITY_PRESETS.indexOfFirst { p ->
    p.format == format && when (format) {
        AudioFormat.FLAC -> p.flacLevel == flacLevel
        AudioFormat.AAC -> p.aacKbps == aacKbps
        AudioFormat.MP3 -> p.mp3Kbps == mp3Kbps
        AudioFormat.WAV -> true
    }
}

fun AppSettings.qualityLabel(): String = when (format) {
    AudioFormat.FLAC -> T("FLAC ロスレス", "FLAC lossless")
    AudioFormat.AAC -> "AAC ${aacKbps}kbps"
    AudioFormat.MP3 -> "MP3 ${mp3Kbps}kbps"
    AudioFormat.WAV -> T("WAV 無圧縮", "WAV uncompressed")
}

object Keys {
    val format = intPreferencesKey("format")
    val flacLevel = intPreferencesKey("flacLevel")
    val aacKbps = intPreferencesKey("aacKbps")
    val mp3Kbps = intPreferencesKey("mp3Kbps")
    val mp3Quality = intPreferencesKey("mp3Quality")
    val speedX = intPreferencesKey("speedX")
    val sectorsPerRead = intPreferencesKey("sectorsPerRead")
    val retries = intPreferencesKey("retries")
    val useC2 = booleanPreferencesKey("useC2")
    val abortOnError = booleanPreferencesKey("abortOnError")
    val offsetSamples = intPreferencesKey("offsetSamples")
    val ejectAfter = booleanPreferencesKey("ejectAfter")
    val outputUri = stringPreferencesKey("outputUri")
    val libraryFolders = stringSetPreferencesKey("libraryFolders")
    val folderMode = intPreferencesKey("folderMode")
    val fileNameMode = intPreferencesKey("fileNameMode")
    val autoMeta = booleanPreferencesKey("autoMeta")
    val useGnudb = booleanPreferencesKey("useGnudb")
    val contactEmail = stringPreferencesKey("contactEmail")
    val autoOpenDisc = booleanPreferencesKey("autoOpenDisc")
    val saveCover = booleanPreferencesKey("saveCover")
    val embedCover = booleanPreferencesKey("embedCover")
    val cdPlaySpeed = intPreferencesKey("cdPlaySpeed")
    val keepScreenOn = booleanPreferencesKey("keepScreenOn")
    val themeMode = intPreferencesKey("themeMode")
    val tabletMode = intPreferencesKey("tabletMode")
    val listArt = booleanPreferencesKey("listArt")
    val dynamicColor = booleanPreferencesKey("dynamicColor")
    val ambientPlayer = booleanPreferencesKey("ambientPlayer")
    val bitPerfect = booleanPreferencesKey("bitPerfect")
    val usbDirect = booleanPreferencesKey("usbDirect")
    val usbVolume = intPreferencesKey("usbVolume")
    val dsdMode = intPreferencesKey("dsdMode")
    val dsdSwap = booleanPreferencesKey("dsdSwap")
    val outputDevice = stringPreferencesKey("outputDevice")
    val eqEnabled = booleanPreferencesKey("eqEnabled")
    val eqPreset = intPreferencesKey("eqPreset")
    val eqGains = stringPreferencesKey("eqGains")
    val eqPreamp = androidx.datastore.preferences.core.floatPreferencesKey("eqPreamp")
    val eqAutoPreamp = booleanPreferencesKey("eqAutoPreamp")
    val libraryList = booleanPreferencesKey("libraryList")
    val verboseLog = booleanPreferencesKey("verboseLog")
    val libTiles = stringPreferencesKey("libTiles")
    val gapless = booleanPreferencesKey("gapless")
    val sourceDirect = booleanPreferencesKey("sourceDirect")
    val updateCheck = booleanPreferencesKey("updateCheck")
    val updateSkip = stringPreferencesKey("updateSkip")
    val eqBands = stringPreferencesKey("eqBands")
    val darMode = intPreferencesKey("darMode")
    val darPcm = intPreferencesKey("darPcm")
    val darDsd = intPreferencesKey("darDsd")
    val darInteger = booleanPreferencesKey("darInteger")
}

class SettingsRepository(private val context: Context) {
    val flow: Flow<AppSettings> = context.dataStore.data.catch { e ->
        // 読み込めないときは初期値で動かす(落ちないように)
        if (e is java.io.IOException) emit(androidx.datastore.preferences.core.emptyPreferences()) else throw e
    }.map { p ->
        val d = AppSettings()
        AppSettings(
            format = AudioFormat.entries.getOrElse(p[Keys.format] ?: d.format.ordinal) { d.format },
            flacLevel = p[Keys.flacLevel] ?: d.flacLevel,
            aacKbps = p[Keys.aacKbps] ?: d.aacKbps,
            mp3Kbps = p[Keys.mp3Kbps] ?: d.mp3Kbps,
            mp3Quality = p[Keys.mp3Quality] ?: d.mp3Quality,
            speedX = p[Keys.speedX] ?: d.speedX,
            sectorsPerRead = p[Keys.sectorsPerRead] ?: d.sectorsPerRead,
            retries = p[Keys.retries] ?: d.retries,
            useC2 = p[Keys.useC2] ?: d.useC2,
            abortOnError = p[Keys.abortOnError] ?: d.abortOnError,
            offsetSamples = p[Keys.offsetSamples] ?: d.offsetSamples,
            ejectAfter = p[Keys.ejectAfter] ?: d.ejectAfter,
            outputUri = p[Keys.outputUri],
            libraryFolders = p[Keys.libraryFolders] ?: emptySet(),
            folderMode = p[Keys.folderMode] ?: d.folderMode,
            fileNameMode = p[Keys.fileNameMode] ?: d.fileNameMode,
            autoMeta = p[Keys.autoMeta] ?: d.autoMeta,
            useGnudb = p[Keys.useGnudb] ?: d.useGnudb,
            contactEmail = p[Keys.contactEmail] ?: d.contactEmail,
            autoOpenDisc = p[Keys.autoOpenDisc] ?: d.autoOpenDisc,
            saveCover = p[Keys.saveCover] ?: d.saveCover,
            embedCover = p[Keys.embedCover] ?: d.embedCover,
            cdPlaySpeed = p[Keys.cdPlaySpeed] ?: d.cdPlaySpeed,
            keepScreenOn = p[Keys.keepScreenOn] ?: d.keepScreenOn,
            themeMode = p[Keys.themeMode] ?: d.themeMode,
            tabletMode = p[Keys.tabletMode] ?: d.tabletMode,
            listArt = p[Keys.listArt] ?: d.listArt,
            dynamicColor = p[Keys.dynamicColor] ?: d.dynamicColor,
            ambientPlayer = p[Keys.ambientPlayer] ?: d.ambientPlayer,
            bitPerfect = p[Keys.bitPerfect] ?: d.bitPerfect,
            usbDirect = p[Keys.usbDirect] ?: d.usbDirect,
            usbVolume = p[Keys.usbVolume] ?: d.usbVolume,
            dsdMode = p[Keys.dsdMode] ?: d.dsdMode,
            dsdSwap = p[Keys.dsdSwap] ?: d.dsdSwap,
            outputDevice = p[Keys.outputDevice] ?: d.outputDevice,
            eqEnabled = p[Keys.eqEnabled] ?: d.eqEnabled,
            eqPreset = p[Keys.eqPreset] ?: d.eqPreset,
            eqGains = p[Keys.eqGains] ?: d.eqGains,
            eqPreamp = p[Keys.eqPreamp] ?: d.eqPreamp,
            eqAutoPreamp = p[Keys.eqAutoPreamp] ?: d.eqAutoPreamp,
            libraryList = p[Keys.libraryList] ?: d.libraryList,
            verboseLog = p[Keys.verboseLog] ?: d.verboseLog,
            libTiles = p[Keys.libTiles] ?: d.libTiles,
            gapless = p[Keys.gapless] ?: d.gapless,
            sourceDirect = p[Keys.sourceDirect] ?: d.sourceDirect,
            updateCheck = p[Keys.updateCheck] ?: d.updateCheck,
            updateSkip = p[Keys.updateSkip] ?: d.updateSkip,
            eqBands = p[Keys.eqBands] ?: d.eqBands,
            darMode = p[Keys.darMode] ?: d.darMode,
            darPcm = p[Keys.darPcm] ?: d.darPcm,
            darDsd = p[Keys.darDsd] ?: d.darDsd,
            darInteger = p[Keys.darInteger] ?: d.darInteger,
        )
    }

    suspend fun <T> set(key: Preferences.Key<T>, value: T) {
        context.dataStore.edit { it[key] = value }
    }

    /** 出力先をまとめて切り替える(途中の状態で出力が作り直されないよう、1 回で書き込む) */
    suspend fun setOutput(usbDirect: Boolean, device: String?) {
        context.dataStore.edit {
            it[Keys.usbDirect] = usbDirect
            if (!usbDirect) it[Keys.bitPerfect] = false
            if (device != null) it[Keys.outputDevice] = device
        }
    }

    suspend fun resetDrive() {
        context.dataStore.edit {
            it.remove(Keys.speedX); it.remove(Keys.retries); it.remove(Keys.abortOnError)
            it.remove(Keys.offsetSamples); it.remove(Keys.sectorsPerRead); it.remove(Keys.useC2); it.remove(Keys.cdPlaySpeed)
        }
    }

    suspend fun updateFolders(f: (Set<String>) -> Set<String>): Set<String> {
        var out = emptySet<String>()
        context.dataStore.edit {
            out = f(it[Keys.libraryFolders] ?: emptySet())
            it[Keys.libraryFolders] = out
        }
        return out
    }

    suspend fun setPreset(p: QualityPreset) {
        context.dataStore.edit {
            it[Keys.format] = p.format.ordinal
            it[Keys.flacLevel] = p.flacLevel
            it[Keys.aacKbps] = p.aacKbps
            it[Keys.mp3Kbps] = p.mp3Kbps
        }
    }
}
