package com.uyatame.ucrt.data

import com.uyatame.ucrt.R
import com.uyatame.ucrt.S

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

data class AppSettings(
    val format: AudioFormat = AudioFormat.FLAC,
    val flacLevel: Int = 5,
    val aacKbps: Int = 256,
    val mp3Kbps: Int = 320,
    val mp3Quality: Int = 2,
    /** FLAC・MP3 の変換に FFmpeg を使う(動かない端末では、アプリ内蔵の変換を使う) */
    val useFfmpeg: Boolean = true,
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
    /** ダイナミックカラーを使わないときのテーマの色(ARGB。0 = 標準の紫) */
    val themeColor: Int = 0,
    /** 文字の色(ARGB。0 = テーマの標準の色) */
    val textColor: Int = 0,
    val ambientPlayer: Boolean = true,
    /** アンビエントモードで背景のジャケットをぼかす(オフならぼかさずに薄く敷く) */
    val ambientBlur: Boolean = true,
    /** 画面下のナビゲーションバーを隠す(画面の下の端からスワイプすると一時的に出る) */
    val hideNavBar: Boolean = false,
    /** 曲ごとに埋め込まれたジャケットがあれば、曲の一覧と再生画面でそちらを出す */
    val trackArt: Boolean = true,
    /** ほかの音楽アプリ(ストリーミングサービスなど)の再生を表示・操作する(「通知へのアクセス」の許可も要る) */
    val externalMedia: Boolean = true,
    /** ストリーミングのタブで Spotify の機能(ログインしてライブラリを探し、公式 Spotify アプリで再生)を使う */
    val spotifyEnabled: Boolean = false,
    /** USB DAC 直接出力の音切れ対策(試験的。端末の省電力で転送が遅れるのを防ぐ) */
    val usbStabilize: Boolean = true,
    /** ほかのアプリに音を取られて止めたあと、戻ってきたら自動で続きを再生する */
    val autoResume: Boolean = true,
    /** リスト(アルバム・アーティスト・プレイリストなど)を最後まで再生したら、次のリストを続けて再生する */
    val autoNextList: Boolean = false,
    /** Android Auto(試験的)で、車の画面にライブラリを出す */
    val androidAuto: Boolean = true,
    /** DAC を常に起こしておく(ほかのアプリの再生にも効く。試験的) */
    val dacKeepAlive: Boolean = false,
    /** 起こしておく機器: 0 = 自動、1 = USB DAC、2 = イヤホン端子 */
    val dacKeepAliveTarget: Int = 0,
    /** 背景のぼかしの強さ(1〜10。1 段階で半径 14dp。5 が以前と同じ 70dp) */
    val ambientBlurLevel: Int = 5,
    /** アップサンプルのタブの設定(DSRE・DSD 変換) */
    val upSpec: com.uyatame.ucrt.convert.ConvertSpec = com.uyatame.ucrt.convert.ConvertSpec(),
    /** アップサンプルの書き出し先のフォルダ(SAF のツリー。null なら未設定) */
    val upOutUri: String? = null,
    /** 保存した DSRE のプリセット(JSON の配列) */
    val dsrePresets: String = "[]",
    /** 見た目: 0 = 標準、1 = リキッドグラス(下のタブとミニプレーヤーを浮かぶガラスに) */
    val appearance: Int = 0,
    /** 再生画面の操作ボタンの並び(R: リピート、P: 前、X: 再生、N: 次、S: シャッフル) */
    val controlOrder: String = "RPXNS",
    /** お気に入りの並び: 0 = 追加した順、1 = 曲名・アルバム名、2 = アーティスト、3 = アルバム(曲)・年(アルバム) */
    val favSort: Int = 0,
    /** 細かい音量(通常の出力で、アプリの中の音量を 0.5 dB ごとに変える) */
    val fineVolume: Boolean = false,
    /** ステータスバー(上の時計など)も隠して全画面にする */
    val hideStatusBar: Boolean = false,
    /** アプリの中の音量の段階(0〜200。200 = 0 dB) */
    val appVolumeStep: Int = 200,
    /** 本体の音量ボタンで USB DAC の音量を変える幅(1・2・4) */
    val usbVolumeStep: Int = 4,
    /** リキッドグラスの濃さ(%。10〜95。小さいほど透ける) */
    val glassOpacity: Int = 55,
    /** リキッドグラスの色(0 = テーマに合わせる) */
    val glassTint: Int = 0,
    /** 再生の画面のスペクトラムアナライザー(初めはオフ) */
    val spectrum: Boolean = false,
    /** 見せ方(SpectrumStyle) */
    val spectrumStyle: Int = 0,
    /** 声の帯域を強めて見せる */
    val spectrumVocal: Boolean = false,
    /** 計算する間隔(ミリ秒。8〜200) */
    val spectrumRefreshMs: Int = 24,
    /** 色(0 = 再生の画面の強調の色に合わせる) */
    val spectrumColor: Int = 0,
    /** 表示を遅らせる時間(ミリ秒。Bluetooth などで音が遅れて聞こえるときの補正。0〜500) */
    val spectrumDelayMs: Int = 0,
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
    /** ReplayGain(曲ごとの音の大きさをそろえる)。rgMode: 0 = 曲ごと、1 = アルバムごと。rgPreamp: 足す量(dB) */
    val rgEnabled: Boolean = false,
    val rgMode: Int = 0,
    val rgPreamp: Int = 0,
    val rgNoClip: Boolean = true,
    /** ソースダイレクト: オンなら、イコライザーと DAR を使わずに音源のまま出す */
    val sourceDirect: Boolean = false,
    /** 起動したときに、新しい版が出ていないか確かめる */
    val updateCheck: Boolean = true,
    /** 「この版は通知しない」にした版 */
    val updateSkip: String = "",
    /** イコライザーの帯域(パラメトリック EQ。空なら以前の 10 バンドの eqGains から作る) */
    val eqBands: String = "",
    /** イコライザーの種類: 0 = シンプル(10 バンド)、1 = PRO(パラメトリック) */
    val eqMode: Int = 0,
    /** シンプルの 10 バンドの値(dB、カンマ区切り) */
    val eqSimpleGains: String = "",
    /** 保存したイコライザー(1 行に 1 つ。EqCustom.encode の形) */
    val eqCustom: String = "",
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

/** シンプルのイコライザーの 10 バンドの値(保存されていなければ、以前の 10 バンドの設定) */
val AppSettings.eqSimpleList: FloatArray
    get() = com.uyatame.ucrt.player.EqBands.decode(eqSimpleGains.ifEmpty { eqGains })

/** 今の種類(シンプル・PRO)で実際に掛けるイコライザーの帯域 */
val AppSettings.eqCurrentBands: List<com.uyatame.ucrt.player.EqBand>
    get() = if (eqMode == 0) com.uyatame.ucrt.player.ParamEq.fromGains(eqSimpleList) else eqBandList

/** 実際にイコライザーを掛けるか(ソースダイレクトのときは掛けない) */
val AppSettings.eqActive: Boolean get() = eqEnabled && !sourceDirect

/** 実際に使う DAR の方式(ソースダイレクトのときはオフ) */
val AppSettings.darEffective: Int get() = if (sourceDirect) 0 else darMode

/** 実際に使う ReplayGain(0 = 使わない、1 = 曲ごと、2 = アルバムごと。ソースダイレクトでは使わない) */
val AppSettings.rgEffective: Int get() = if (sourceDirect || !rgEnabled) 0 else rgMode + 1

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
    AudioFormat.FLAC -> S(R.string.flac_lossless)
    AudioFormat.AAC -> "AAC ${aacKbps}kbps"
    AudioFormat.MP3 -> "MP3 ${mp3Kbps}kbps"
    AudioFormat.WAV -> S(R.string.wav_uncompressed)
}

object Keys {
    val format = intPreferencesKey("format")
    val flacLevel = intPreferencesKey("flacLevel")
    val aacKbps = intPreferencesKey("aacKbps")
    val mp3Kbps = intPreferencesKey("mp3Kbps")
    val mp3Quality = intPreferencesKey("mp3Quality")
    val useFfmpeg = booleanPreferencesKey("useFfmpeg")
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
    val themeColor = intPreferencesKey("themeColor")
    val textColor = intPreferencesKey("textColor")
    val ambientBlur = booleanPreferencesKey("ambientBlur")
    val hideNavBar = booleanPreferencesKey("hideNavBar")
    val trackArt = booleanPreferencesKey("trackArt")
    val externalMedia = booleanPreferencesKey("externalMedia")
    val spotifyEnabled = booleanPreferencesKey("spotifyEnabled")
    val usbStabilize = booleanPreferencesKey("usbStabilize")
    val autoResume = booleanPreferencesKey("autoResume")
    val autoNextList = booleanPreferencesKey("autoNextList")
    val androidAuto = booleanPreferencesKey("androidAuto")
    val dacKeepAlive = booleanPreferencesKey("dacKeepAlive")
    val dacKeepAliveTarget = intPreferencesKey("dacKeepAliveTarget")
    val ambientBlurLevel = intPreferencesKey("ambientBlurLevel")
    val spectrum = booleanPreferencesKey("spectrum")
    val appearance = intPreferencesKey("appearance")
    val controlOrder = stringPreferencesKey("controlOrder")
    val favSort = intPreferencesKey("favSort")
    val fineVolume = booleanPreferencesKey("fineVolume")
    val hideStatusBar = booleanPreferencesKey("hideStatusBar")
    val appVolumeStep = intPreferencesKey("appVolumeStep")
    val usbVolumeStep = intPreferencesKey("usbVolumeStep")
    val glassOpacity = intPreferencesKey("glassOpacity")
    val glassTint = intPreferencesKey("glassTint")
    val upSpec = stringPreferencesKey("upSpec")
    val upOutUri = stringPreferencesKey("upOutUri")
    val dsrePresets = stringPreferencesKey("dsrePresets")
    val spectrumStyle = intPreferencesKey("spectrumStyle")
    val spectrumVocal = booleanPreferencesKey("spectrumVocal")
    val spectrumRefreshMs = intPreferencesKey("spectrumRefreshMs")
    val spectrumColor = intPreferencesKey("spectrumColor")
    val spectrumDelayMs = intPreferencesKey("spectrumDelayMs")
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
    val rgEnabled = booleanPreferencesKey("rgEnabled")
    val rgMode = intPreferencesKey("rgMode")
    val rgPreamp = intPreferencesKey("rgPreamp")
    val rgNoClip = booleanPreferencesKey("rgNoClip")
    val sourceDirect = booleanPreferencesKey("sourceDirect")
    val updateCheck = booleanPreferencesKey("updateCheck")
    val updateSkip = stringPreferencesKey("updateSkip")
    val eqBands = stringPreferencesKey("eqBands")
    val eqMode = intPreferencesKey("eqMode")
    val eqSimpleGains = stringPreferencesKey("eqSimpleGains")
    val eqCustom = stringPreferencesKey("eqCustom")
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
            useFfmpeg = p[Keys.useFfmpeg] ?: d.useFfmpeg,
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
            themeColor = p[Keys.themeColor] ?: d.themeColor,
            textColor = p[Keys.textColor] ?: d.textColor,
            ambientBlur = p[Keys.ambientBlur] ?: d.ambientBlur,
            hideNavBar = p[Keys.hideNavBar] ?: d.hideNavBar,
            trackArt = p[Keys.trackArt] ?: d.trackArt,
            externalMedia = p[Keys.externalMedia] ?: d.externalMedia,
            spotifyEnabled = p[Keys.spotifyEnabled] ?: d.spotifyEnabled,
            usbStabilize = p[Keys.usbStabilize] ?: d.usbStabilize,
            autoResume = p[Keys.autoResume] ?: d.autoResume,
            autoNextList = p[Keys.autoNextList] ?: d.autoNextList,
            androidAuto = p[Keys.androidAuto] ?: d.androidAuto,
            dacKeepAlive = p[Keys.dacKeepAlive] ?: d.dacKeepAlive,
            dacKeepAliveTarget = p[Keys.dacKeepAliveTarget] ?: d.dacKeepAliveTarget,
            ambientBlurLevel = (p[Keys.ambientBlurLevel] ?: d.ambientBlurLevel).coerceIn(1, 10),
            spectrum = p[Keys.spectrum] ?: d.spectrum,
            appearance = (p[Keys.appearance] ?: d.appearance).coerceIn(0, 1),
            controlOrder = p[Keys.controlOrder] ?: d.controlOrder,
            favSort = (p[Keys.favSort] ?: d.favSort).coerceIn(0, 3),
            fineVolume = p[Keys.fineVolume] ?: d.fineVolume,
            hideStatusBar = p[Keys.hideStatusBar] ?: d.hideStatusBar,
            appVolumeStep = (p[Keys.appVolumeStep] ?: d.appVolumeStep).coerceIn(0, 200),
            usbVolumeStep = (p[Keys.usbVolumeStep] ?: d.usbVolumeStep).let { if (it in setOf(1, 2, 4)) it else 4 },
            glassOpacity = (p[Keys.glassOpacity] ?: d.glassOpacity).coerceIn(10, 95),
            glassTint = p[Keys.glassTint] ?: d.glassTint,
            upSpec = com.uyatame.ucrt.convert.decodeConvertSpec(p[Keys.upSpec]),
            upOutUri = p[Keys.upOutUri],
            dsrePresets = p[Keys.dsrePresets] ?: d.dsrePresets,
            spectrumStyle = (p[Keys.spectrumStyle] ?: d.spectrumStyle).coerceIn(0, 4),
            spectrumVocal = p[Keys.spectrumVocal] ?: d.spectrumVocal,
            spectrumRefreshMs = (p[Keys.spectrumRefreshMs] ?: d.spectrumRefreshMs).coerceIn(8, 200),
            spectrumColor = p[Keys.spectrumColor] ?: d.spectrumColor,
            spectrumDelayMs = (p[Keys.spectrumDelayMs] ?: d.spectrumDelayMs).coerceIn(0, 500),
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
            rgEnabled = p[Keys.rgEnabled] ?: d.rgEnabled,
            rgMode = p[Keys.rgMode] ?: d.rgMode,
            rgPreamp = p[Keys.rgPreamp] ?: d.rgPreamp,
            rgNoClip = p[Keys.rgNoClip] ?: d.rgNoClip,
            sourceDirect = p[Keys.sourceDirect] ?: d.sourceDirect,
            updateCheck = p[Keys.updateCheck] ?: d.updateCheck,
            updateSkip = p[Keys.updateSkip] ?: d.updateSkip,
            eqBands = p[Keys.eqBands] ?: d.eqBands,
            // 以前の版で PRO のイコライザーを作っていた人は、そのまま PRO で始める
            eqMode = p[Keys.eqMode] ?: if ((p[Keys.eqBands] ?: "").isNotEmpty()) 1 else 0,
            eqSimpleGains = p[Keys.eqSimpleGains] ?: d.eqSimpleGains,
            eqCustom = p[Keys.eqCustom] ?: d.eqCustom,
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
