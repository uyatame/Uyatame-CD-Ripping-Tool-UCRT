import java.net.URI
import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.uyatame.ucrt"
    compileSdk = 36

    defaultConfig {
        // アプリ ID は公開済みの版から変えない(変えると別のアプリ扱いになり、上書き更新や設定の引き継ぎができなくなる)
        applicationId = "com.uyatame.cdripper"
        minSdk = 31
        targetSdk = 36
        versionCode = 39
        versionName = "0.4.3"
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
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

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
