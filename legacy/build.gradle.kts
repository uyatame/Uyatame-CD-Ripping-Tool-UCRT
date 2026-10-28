import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// 古い Android(4.0〜5.1)向けのアプリ「UCRT Legacy」。画面は View で作り、AndroidX は使わない。
// 曲の読み込み・デコードなどの共通の処理は、app と同じ core/ のソースを使う
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// UCRT Legacy の版(本家とは別の番号。α版。2026-10-27 にユーザーの指示)。
// リリースのときは legacyVersion を書き換え、legacyVersionCode を 1 上げる。リリースのタグは legacy-v<legacyVersion>(プレリリース)
val legacyVersion = "0.1.1-alpha"
// これまでのテスト版(本家の版の番号 44 で作っていた)の上に入れられるように、45 から始める
val legacyVersionCode = 46

android {
    // R(文言など)とソースのパッケージは app と同じ(core のソースが com.uyatame.ucrt.R を使うため)。アプリ ID は別
    namespace = "com.uyatame.ucrt"
    compileSdk = 37

    defaultConfig {
        // app とは別のアプリとして入れられるように、アプリ ID を変える(2026-10-27 にユーザーの希望)
        applicationId = "com.uyatame.cdripper.legacy"
        minSdk = 14
        targetSdk = 37
        versionCode = legacyVersionCode
        versionName = legacyVersion + (findProperty("testVersion")?.let { ".test$it" } ?: "")
        // ベクター画像(アイコン)は、Android 5.0 より前では描けないので、ビルドのときに PNG にする
        vectorDrawables.generatedDensities("mdpi", "hdpi", "xhdpi", "xxhdpi")
    }

    // 公開用の署名鍵(app と同じ)。keystore.properties(Git には含めない)があればそれで署名し、無ければデバッグ鍵で署名する
    val keyProps = Properties().apply {
        val f = rootProject.file("keystore.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }
    signingConfigs {
        if (keyProps.getProperty("storeFile") != null) {
            create("release") {
                // keystore.properties の storeFile は app のフォルダからの場所で書かれているので、app のフォルダを基準にする
                storeFile = File(keyProps.getProperty("storeFile")).let { if (it.isAbsolute) it else rootProject.file("app").resolve(it.path) }
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
    lint { checkReleaseBuilds = false }
    // app と共通のソースと文言
    sourceSets["main"].java.srcDir("../core/src/main/java")
    sourceSets["main"].res.srcDir("../core/src/main/res")
    // ライセンスの文(「このアプリについて」で出す)は app と同じものを使う
    sourceSets["main"].assets.srcDir("../app/src/main/assets")
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

// ライセンスの文(app/src/main/assets)は app の fetchLicenses が用意するので、先に動かす
// (書かないと、新しい Gradle が「暗黙の依存」としてビルドを止める)
tasks.named("preBuild") { dependsOn(":app:fetchLicenses") }
tasks.matching { it.name.contains("Assets") || it.name.contains("Lint", ignoreCase = true) }.configureEach {
    dependsOn(":app:fetchLicenses")
}

dependencies {
    // MP3 で取り込む(LAME の Java 版。app と同じ)
    implementation("de.sciss:jump3r:1.0.5")
    // USB DAC への直接出力(usbfs を直接呼ぶ。app と同じ)
    implementation("net.java.dev.jna:jna:5.14.0@aar")
    // 単体テスト(PC 上で動かす)
    testImplementation("junit:junit:4.13.2")
}
