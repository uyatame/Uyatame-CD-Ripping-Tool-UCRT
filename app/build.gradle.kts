import java.net.URI
import java.security.MessageDigest
import java.util.Properties
import java.util.zip.ZipInputStream
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.uyatame.ucrt"
    // Android 17(API 37)の SDK でビルドする(今後 Android 17 の新しい機能を使えるように。ユーザーの指示)。
    // Android Gradle Plugin 8.9 は正式には 36 までなので、gradle.properties で警告を止めている
    compileSdk = 37

    defaultConfig {
        // アプリ ID は公開済みの版から変えない(変えると別のアプリ扱いになり、上書き更新や設定の引き継ぎができなくなる)
        applicationId = "com.uyatame.cdripper"
        // Android 6.0 から(Android 6.0・7.x は試験的な対応)。Android 4.0〜5.1 は別のアプリ(legacy)で対応する
        minSdk = 23
        targetSdk = 37
        versionCode = 44
        // 次にリリースする版。テスト用のビルド(tools/termux-build.sh)では、後ろに ".test<テスト版>" が付く
        versionName = "0.6.0" + (findProperty("testVersion")?.let { ".test$it" } ?: "")
    }

    // 公開用の署名鍵。keystore.properties(Git には含めない)があればそれで署名し、無ければデバッグ鍵で署名する
    val keyProps = Properties().apply {
        val f = rootProject.file("keystore.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }
    signingConfigs {
        if (keyProps.getProperty("storeFile") != null) {
            create("release") {
                storeFile = file(keyProps.getProperty("storeFile"))
                storePassword = keyProps.getProperty("storePassword")
                keyAlias = keyProps.getProperty("keyAlias")
                keyPassword = keyProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
    lint { checkReleaseBuilds = false }
    // app と legacy で共通のソースと文言(core。どちらのアプリにもそのまま入る)
    sourceSets["main"].java.srcDir("../core/src/main/java")
    sourceSets["main"].res.srcDir("../core/src/main/res")
    // 取り込みの変換に使う FFmpeg(fetchFfmpeg が取ってくる lib/<ABI>/libffmpeg.so)
    sourceSets["main"].jniLibs.srcDir(layout.buildDirectory.dir("ffmpeg/jniLibs").get().asFile)
    // FFmpeg はプログラムとして実行するので、インストール時に lib を展開させる(展開しないと実行できない)
    packaging { jniLibs { useLegacyPackaging = true } }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

// Spotify App Remote SDK(https://github.com/spotify/android-sdk のリリース v0.8.0)
val spotifyAarName = "spotify-app-remote-release-0.8.0.aar"
val spotifyAarUrl = "https://github.com/spotify/android-sdk/releases/download/v0.8.0-appremote_v2.1.0-auth/$spotifyAarName"
val spotifyAarSha256 = "b5a6dd880eaf01f63a871cba9ef7af77c341f8a94ffc8fdf2e9021f9a9d4c198"
val spotifyAar = layout.buildDirectory.file("spotify/$spotifyAarName")

fun sha256Hex(b: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

val fetchSpotifySdk = tasks.register("fetchSpotifySdk") {
    val out = spotifyAar
    outputs.file(out)
    doLast {
        val f = out.get().asFile
        if (f.exists() && sha256Hex(f.readBytes()) == spotifyAarSha256) return@doLast
        logger.lifecycle("Spotify App Remote SDK を取得: $spotifyAarUrl")
        val bytes = downloadText(spotifyAarUrl)
        val got = sha256Hex(bytes)
        check(got == spotifyAarSha256) { "Spotify App Remote SDK の SHA-256 が一致しません: $got" }
        f.parentFile.mkdirs()
        f.writeBytes(bytes)
    }
}

// CD の取り込みの変換(FLAC・MP3)に使う FFmpeg。tools/ffmpeg/build.sh を GitHub Actions(.github/workflows/ffmpeg.yml)で
// ビルドしてこのリポジトリのリリースに置いたもの。ビルド時に取得し、SHA-256 を確かめてから APK に入れる(リポジトリには入れない)
val ffmpegRelease = "ffmpeg-8.1.3-2"
val ffmpegUrl = "https://github.com/uyatame/Uyatame-CD-Ripping-Tool-UCRT/releases/download/$ffmpegRelease/ucrt-ffmpeg.zip"
val ffmpegSha256 = "7f3aa514273aaadc051caad86f6255a8d55c9be57260fbe6070a3fc341043dd6"
val ffmpegDir = layout.buildDirectory.dir("ffmpeg")

val fetchFfmpeg = tasks.register("fetchFfmpeg") {
    val out = ffmpegDir
    outputs.dir(out)
    doLast {
        val dir = out.get().asFile
        val zip = File(dir, "$ffmpegRelease.zip")
        if (!zip.exists() || sha256Hex(zip.readBytes()) != ffmpegSha256) {
            logger.lifecycle("FFmpeg を取得: $ffmpegUrl")
            val bytes = downloadText(ffmpegUrl)
            val got = sha256Hex(bytes)
            check(got == ffmpegSha256) { "FFmpeg の SHA-256 が一致しません: $got" }
            dir.mkdirs()
            zip.writeBytes(bytes)
        }
        val libs = File(dir, "jniLibs")
        libs.deleteRecursively()
        ZipInputStream(zip.inputStream()).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                // lib/<ABI>/libffmpeg.so だけを取り出す
                val m = Regex("^lib/([A-Za-z0-9_-]+)/libffmpeg\\.so$").find(e.name) ?: continue
                val f = File(libs, "${m.groupValues[1]}/libffmpeg.so")
                f.parentFile.mkdirs()
                f.outputStream().use { z.copyTo(it) }
            }
        }
        check(File(libs, "arm64-v8a/libffmpeg.so").exists()) { "FFmpeg の中身が足りません" }
    }
}
tasks.named("preBuild") { dependsOn(fetchFfmpeg) }

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation(platform("androidx.compose:compose-bom:2025.04.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.datastore:datastore-preferences:1.1.4")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    // MP3エンコーダ: jump3r(LAME 3.98.4 Java移植版) LGPL v2.1+
    implementation("de.sciss:jump3r:1.0.5")
    // USB DAC 直接出力: usbfs の ioctl を呼ぶために使う JNA(Apache-2.0 / LGPL-2.1 のデュアルライセンス。Apache-2.0 を選択)
    implementation("net.java.dev.jna:jna:5.14.0@aar")
    // Spotify: 公式 Spotify アプリでの再生を操作する App Remote SDK(Apache-2.0)。Maven で配られていないので、
    // ビルド時に GitHub のリリースから取得して、ファイルの SHA-256 を確かめてから使う(リポジトリには入れない)
    implementation(files(spotifyAar).builtBy(fetchSpotifySdk))
    // Spotify へのログイン(Apache-2.0)。Spotify アプリが入っていれば Spotify アプリで許可するので、App Remote も使えるようになる
    implementation("com.spotify.android:auth:5.0.0")
    // App Remote SDK が Spotify アプリとのやり取りに使う JSON ライブラリ(Apache-2.0)
    implementation("com.google.code.gson:gson:2.10.1")

    // 単体テスト(PC 上で動かす。内蔵デコーダーや文言のリソースなどを確かめる)
    testImplementation("junit:junit:4.13.2")
}

// ---------------------------------------------------------------
// ライセンス全文の同梱: 公式サイトから原文をそのまま取得して assets に入れる
// (初回ビルド時のみダウンロード。取得済みなら再取得しない)
// ---------------------------------------------------------------
val licenseDir = layout.projectDirectory.dir("src/main/assets/licenses")
val licenseSources = mapOf(
    "LGPL-2.1.txt" to "https://www.gnu.org/licenses/old-licenses/lgpl-2.1.txt",
    "Apache-2.0.txt" to "https://www.apache.org/licenses/LICENSE-2.0.txt",
)
val optionalLicenseSources = mapOf(
    "CC0-1.0.txt" to "https://creativecommons.org/publicdomain/zero/1.0/legalcode.txt",
)

fun downloadText(url: String): ByteArray {
    val conn = URI(url).toURL().openConnection()
    conn.connectTimeout = 20000
    conn.readTimeout = 60000
    conn.setRequestProperty("User-Agent", "UCRT-build")
    return conn.getInputStream().use { it.readBytes() }
}

val fetchLicenses = tasks.register("fetchLicenses") {
    outputs.dir(licenseDir)
    doLast {
        val dir = licenseDir.asFile
        dir.mkdirs()
        licenseSources.forEach { (name, url) ->
            val f = File(dir, name)
            if (!f.exists() || f.length() < 1000) {
                logger.lifecycle("ライセンス全文を取得: $url")
                // 通常はプロジェクトに同梱済み。無い場合だけ取得し、失敗してもビルドは止めない
                runCatching { downloadText(url) }
                    .onSuccess { if (it.size >= 1000) f.writeBytes(it) }
                    .onFailure { logger.warn("ライセンス全文を取得できませんでした: $url (${it.message})") }
            }
        }
        optionalLicenseSources.forEach { (name, url) ->
            val f = File(dir, name)
            if (!f.exists() || f.length() < 500) {
                runCatching { downloadText(url) }
                    .onSuccess { if (it.size >= 500) f.writeBytes(it) }
                    .onFailure { logger.warn("任意のライセンス文を取得できませんでした: $url") }
            }
        }
    }
}

tasks.named("preBuild") { dependsOn(fetchLicenses) }
