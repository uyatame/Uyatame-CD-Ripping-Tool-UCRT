# Uyatame CD Ripping Tool (UCRT)

外付けUSB光学ドライブ(CD/DVD/BDドライブ)をAndroidにつないで、音楽CDの取り込み(リッピング)と再生ができるアプリです。root化は不要です。

*An Android app that rips and plays audio CDs with an external USB optical drive. No root required. (English summary below.)*

## 主な機能

- **CDの取り込み**: FLAC(ロスレス)/ AAC / MP3 / WAV。音質はプリセットから選ぶだけ、細かい設定も可能
- **曲情報の自動取得**: CDを入れると MusicBrainz から曲名・アーティスト・ジャケットを取得。候補からの選択、名前での検索にも対応
- **正確な読み取り**: リトライ、C2エラー検出、読み取りオフセット補正、読み取り速度の指定
- **ライブラリ**: 取り込んだ曲と端末内の音楽フォルダの曲を、カテゴリー(アルバム・アーティスト・全曲・お気に入り・プレイリスト・最近追加・最近再生・よく聴く曲・音質別・ジャンル・発売年・フォルダ)から探せるトップ画面。横断検索、形式と音質の表示(ハイレゾは金色)、長押しメニュー(次に再生・再生リストに追加・プレイリストに追加など)、高速スクロール
- **プレイヤー**: CDの直接再生、全画面プレイヤー(左右のページでお気に入り・再生リスト、ジャケットのスワイプで曲送り、アンビエントモード、形式・音質表示)、再生リストの並べ替え、歌詞表示(LRC・埋め込み歌詞)、スリープタイマー、前回の続きから再生、シャッフル・リピート、通知とロック画面からの操作
- **音響**: ビットパーフェクト再生(独自の USB オーディオドライバーで USB DAC を直接駆動、UAC1/UAC2、Android 12 以降、PCM 768 kHz / 32bit)、DSD 再生(DSF / DFF、ネイティブ DSD512・DoP・PCM 変換)、DAR(PCM アップサンプリング・DSD への変換)、10バンドイコライザー
- **曲情報の編集**: 曲名・アーティスト・アルバム・年・ジャケットの変更、取り込み済みの曲への曲情報の後付け
- Material Design 3、ダイナミックカラー、日本語 / English

## 動作環境

- Android 12 以上
- USB OTG(USBホスト)に対応した端末
- USB接続の光学ドライブ(Bulk-Only Transport 対応のもの。動作確認: Pioneer BDR-XS07JL)
- スマホからの給電で動かないドライブは、セルフパワーのUSBハブを使ってください

## インストール

[Releases](../../releases) から最新の `UCRT_vX.Y.Z.apk` をダウンロードしてインストールしてください。
Google Play 以外からのインストールのため、Playプロテクトの警告が表示されることがあります。

## ビルド方法

### Termux(Android 端末上)

```bash
pkg install openjdk-17 gradle aapt2
echo "android.aapt2FromMavenOverride=$PREFIX/bin/aapt2" >> ~/.gradle/gradle.properties
echo "sdk.dir=$HOME/android-sdk" > local.properties   # Android SDK(platform 36)の場所
gradle assembleRelease --no-daemon
```

### PC(Android Studio / コマンドライン)

Android Studio でプロジェクトを開いてビルドするか、Gradle 8.9 以上で `gradle assembleRelease` を実行してください。

### 署名について

プロジェクト直下に `keystore.properties` を置くと、その鍵でリリース版に署名します(このファイルと鍵は Git に含めないでください)。無い場合はデバッグ鍵で署名します。

```properties
storeFile=/path/to/ucrt-release.jks
storePassword=********
keyAlias=ucrt
keyPassword=********
```

## 通信とプライバシー

インターネットに接続するのは、曲情報とジャケット画像を取得するときだけです(MusicBrainz と Cover Art Archive)。送るのはCDの目次から計算したディスクIDや、検索に入力した文字だけで、利用状況の収集や広告はありません。

## ライセンス

このアプリは [GNU General Public License v3.0](LICENSE) で公開しています。
使用しているライブラリとデータの提供元は [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) と、アプリ内の「設定 → このアプリについて」で確認できます。

取り込んだ音源は、私的使用の範囲でご利用ください。

---

## English summary

UCRT turns an Android phone plus a USB optical drive into a CD ripper and player.

- Rip audio CDs to FLAC, AAC, MP3 or WAV, with retries, C2 error detection and read offset correction
- Automatic track info and cover art from MusicBrainz, with a match chooser and name search
- Library of ripped albums and any music folders you add, with search, sorting, grid/list views and fast scroll
- Bit-perfect output to USB DACs through the app's own USB audio driver (Android 12+), DSD playback, DAR (PCM upsampling / conversion to DSD) and a 10-band equalizer
- Full-screen player with ambient mode and audio format details, direct CD playback, lock-screen controls
- Tag editing for ripped and existing files

Requires Android 12+ and a USB OTG capable device. Licensed under the GPL-3.0.
