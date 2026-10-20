# Uyatame CD Ripping Tool (UCRT)

外付けUSB光学ドライブ（CD/DVD/BDドライブ）をAndroid端末につないで、音楽CDの取り込み（リッピング）と再生ができるアプリです。root化は不要です。

*An Android app for ripping and playing audio CDs with an external USB optical drive. No root required. (English summary below.)*

## 主な機能

- **CDの取り込み**: FLAC（ロスレス）/ AAC / MP3 / WAVに対応（曲情報・ジャケット付き）。音質はプリセットから選べるほか、細かく設定することもできます。取り込み前の確認、失敗しても元のファイルが消えない上書き
- **CDの書き込み**: 音楽CDのコピー（曲順・曲間もそのまま）、ライブラリの曲からの音楽CD作成（CD-R / CD-RW、SAO / TAO、テスト書き込み）
- **曲情報の自動取得**: CDを入れるとMusicBrainzから曲名・アーティスト・ジャケットを取得。候補からの選択や名前での検索にも対応
- **正確な読み取り**: リトライ、C2エラー検出、読み取りオフセット補正、読み取り速度の指定
- **ライブラリ**: 取り込んだ曲と端末内の音楽フォルダの曲を、トップ画面のカテゴリー（アルバム・アーティスト・全曲・お気に入り・プレイリスト・最近追加・最近再生・よく聴く曲・音質別・ジャンル・発売年・フォルダ）から探せます。横断検索、形式と音質の表示（ハイレゾは金色）、長押しメニュー（次に再生・再生リストに追加・プレイリストに追加など）、高速スクロール
- **再生できる形式**: FLAC / ALAC / WAV / AIFF / WavPack / APE（アプリ内蔵のデコーダーで再生。端末やAndroidの版に左右されず、元の音とビット単位で一致）、DSD（DSF / DFF）、MP3 / AAC / Ogg Vorbis / Opus / MPEG-H（端末のデコーダー）
- **プレイヤー**: CDの直接再生、全画面プレイヤー（左右のページでお気に入り・再生リスト、ジャケットのスワイプで曲送り、アンビエントモード、形式・音質表示）、再生リストの並べ替え、歌詞表示（LRC・埋め込み歌詞）、スリープタイマー、前回の続きから再生、ホーム画面のウィジェット（縦長・横長）、シャッフル・リピート、通知とロック画面からの操作
- **音響**: ビットパーフェクト再生（独自のUSBオーディオドライバーでUSB DACを直接駆動、UAC1/UAC2、PCM 768 kHz / 32bit）、DSD再生（DSF / DFF、ネイティブDSD512・DoP・PCM変換）、DAR（PCMアップサンプリング・DSDへの変換）、曲線で自由に調整できるパラメトリックイコライザー（最大16バンド）、ソースダイレクト
- **曲情報の編集**: 曲名・アーティスト・アルバム・年・ジャケットの変更（FLAC / MP3 / M4A / WAV / DSF）、取り込み済みの曲への曲情報の後付け
- **CUEシート**: 1つのWAV / FLACをCUEシートで曲ごとに分けて表示・再生（Shift_JIS / UTF-8）
- Material Design 3、ダイナミックカラー、日本語 / English

## 動作環境

- Android 8.0以上（ダイナミックカラーはAndroid 12以上、すべてのファイルへのアクセスはAndroid 11以上、アプリ別の表示言語はAndroid 13以上。Android 11以前の通常の出力は192 kHzまで）
- USB OTG（USBホスト）に対応した端末
- USB接続の光学ドライブ（Bulk-Only Transport対応のもの。動作確認: Pioneer BDR-XS07JL）
- スマートフォンからの給電で動かないドライブには、セルフパワーのUSBハブを使ってください

## インストール

[Releases](../../releases) から最新の `UCRT_vX.Y.Z.apk` をダウンロードしてインストールしてください。
Google Play以外からインストールするため、Playプロテクトの警告が表示されることがあります。

## ビルド方法

### Termux（Android端末上）

```bash
pkg install git openjdk-17 gradle aapt2
git clone https://github.com/uyatame/Uyatame-CD-Ripping-Tool-UCRT.git ~/UyatameCdRipper
cd ~/UyatameCdRipper
echo "android.aapt2FromMavenOverride=$PREFIX/bin/aapt2" >> ~/.gradle/gradle.properties
echo "sdk.dir=$HOME/android-sdk" > local.properties   # Android SDK(platform 36)の場所
gradle assembleRelease --no-daemon
```

2回目以降は、`tools/termux-build.sh` で「最新のソースの取得 → ビルド → ダウンロードフォルダへのAPKの配置」をまとめて実行できます。次の1行を一度実行しておけば、あとは `ucrt` と入力するだけです。

```bash
echo "alias ucrt='bash ~/UyatameCdRipper/tools/termux-build.sh'" >> ~/.bashrc && source ~/.bashrc
```

### PC（Android Studio / コマンドライン）

Android Studioでプロジェクトを開いてビルドするか、Gradle 8.9以上で `gradle assembleRelease` を実行してください。

### 署名について

プロジェクト直下に `keystore.properties` を置くと、その鍵でリリース版に署名します（このファイルと鍵はGitに含めないでください）。ファイルがない場合は、デバッグ鍵で署名します。

```properties
storeFile=/path/to/ucrt-release.jks
storePassword=********
keyAlias=ucrt
keyPassword=********
```

## 通信とプライバシー

インターネットに接続するのは、曲情報とジャケット画像を取得するときだけです（MusicBrainzとCover Art Archive）。送信するのは、CDの目次から計算したディスクIDと、検索欄に入力した文字だけです。利用状況の収集や広告はありません。

## ライセンス

このアプリは [GNU General Public License v3.0](LICENSE) で公開しています。
使用しているライブラリとデータの提供元は、[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) とアプリ内の「設定 → このアプリについて」で確認できます。

取り込んだ音源は、私的使用の範囲でご利用ください。

---

## English summary

UCRT turns an Android phone and a USB optical drive into a CD ripper and player.

- Rip audio CDs to FLAC, AAC, MP3 or WAV (with tags and cover art), with retries, C2 error detection and read offset correction
- Burn audio CDs: copy a CD with the same track layout and gaps, or write library tracks to a CD-R / CD-RW (SAO / TAO, test write)
- Automatic track info and cover art from MusicBrainz, with a choice of matches and search by name
- A library of ripped albums and any music folders you add, with search, sorting, grid/list views and fast scrolling
- Bit-perfect output to USB DACs through the app's own USB audio driver, DSD playback, DAR (PCM upsampling / conversion to DSD), Source Direct and a parametric equalizer with a curve editor
- Plays FLAC, ALAC, WAV, AIFF, WavPack and APE with built-in decoders (bit-exact, independent of the device's codecs), DSD (DSF / DFF), and MP3 / AAC / Vorbis / Opus / MPEG-H using the system decoders
- CUE sheet support (one WAV / FLAC file split into tracks)
- Full-screen player with ambient mode and audio format details, direct CD playback and lock screen controls
- Tag editing for ripped and existing files

Requires Android 8.0 or later and a device with USB OTG support. Licensed under GPL-3.0.
