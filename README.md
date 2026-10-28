# Uyatame CD Ripping Tool (UCRT)

[![Build](https://github.com/uyatame/Uyatame-CD-Ripping-Tool-UCRT/actions/workflows/build.yml/badge.svg?branch=main)](https://github.com/uyatame/Uyatame-CD-Ripping-Tool-UCRT/actions/workflows/build.yml)

外付けUSB光学ドライブ（CD/DVD/BDドライブ）をAndroid端末につないで、音楽CDの取り込み（リッピング）と再生ができるアプリです。root化は不要です。

*An Android app for ripping and playing audio CDs with an external USB optical drive. No root required. (English summary below.)*

## 主な機能

- **CDの取り込み**: FLAC（ロスレス）/ AAC / MP3 / WAVに対応（曲情報・ジャケット付き）。FLACとMP3はFFmpegで、CDの読み取りと並行して変換。音質はプリセットから選べるほか、細かく設定することもできます。取り込み前の確認、失敗しても元のファイルが消えない上書き
- **CDの書き込み**: 音楽CDのコピー（曲順・曲間もそのまま）、ライブラリの曲からの音楽CD作成（CD-R / CD-RW、SAO / TAO、テスト書き込み）
- **曲情報の自動取得**: CDを入れるとMusicBrainzから曲名・アーティスト・ジャケットを取得。候補からの選択や名前での検索にも対応。MusicBrainzにないCDは、入力した曲情報をMusicBrainzに登録できます（ライブラリのアルバムからも。登録済みなら登録しません）
- **正確な読み取り**: リトライ、C2エラー検出、読み取りオフセット補正、読み取り速度の指定
- **ライブラリ**: 取り込んだ曲と端末内の音楽フォルダの曲を、トップ画面のカテゴリー（アルバム・アーティスト・全曲・お気に入り・プレイリスト・最近追加・最近再生・よく聴く曲・音質別・ジャンル・発売年・フォルダ）から探せます。横断検索、形式と音質の表示（ハイレゾは金色）、長押しメニュー（次に再生・再生リストに追加・プレイリストに追加など）、曲を複数選んでまとめてプレイリストへ、曲ごとのジャケットの表示、高速スクロール
- **再生できる形式**: FLAC / ALAC / WAV / AIFF / WavPack / APE（アプリ内蔵のデコーダーで再生。端末やAndroidの版に左右されず、元の音とビット単位で一致）、DSD（DSF / DFF）、MP3 / AAC / Ogg Vorbis / Opus / MPEG-H（端末のデコーダー）
- **プレイヤー**: CDの直接再生、全画面プレイヤー（左右のページでお気に入り・再生リスト、ジャケットのスワイプで曲送り、アンビエントモード、形式・音質表示）、再生リストの並べ替え、歌詞表示（LRC・埋め込み歌詞）、スリープタイマー、前回の続きから再生、ホーム画面のウィジェット（縦長・横長）、シャッフル・リピート、通知とロック画面からの操作
- **音響**: ビットパーフェクト再生（独自のUSBオーディオドライバーでUSB DACを直接駆動、UAC1/UAC2、PCM 768 kHz / 32bit）、DSD再生（DSF / DFF、ネイティブDSD512・DoP・PCM変換）、DAR（PCMアップサンプリング・DSDへの変換）、イコライザー（WALKMANのような10バンドのシンプルと、曲線で自由に調整できる最大16バンドのPRO。設定に名前を付けて保存）、ReplayGain（曲ごと・アルバムごと。タグのない曲はライブラリをまとめて解析）、ソースダイレクト
- **音質チェック**: 聴き比べに使う決まった曲を、お気に入りと同じ手軽さで集められます（再生画面のヘッドホンのボタン・曲の長押しメニューから追加）
- **DACを常に起こしておく（試験的）**: USB DACやイヤホン端子（端末の内蔵DAC）に無音を流し続けて、DACが省電力で眠らないようにします。ほかのアプリの再生でも、音切れ・再生の始まりの欠け・切り替えの雑音が減ることがあります
- **アップサンプル**: 曲をまとめて変換して書き出します。DSD変換（PCM↔DSD・DSD→DSD・PCM→PCM。DSF / DFF のDSD64〜512、FLAC / WAVの44.1〜384 kHz・16 / 24 / 32 bit）と、DSRE（圧縮音源などで欠けた高い音を倍音で補ってアップサンプリング）。ノーマライズ・フェード・前後の無音の削除も選べます。曲の情報とジャケットは引き継ぎます
- **見た目**: リキッドグラス（下のタブとミニプレーヤーを浮かぶガラスに）を選べます。ホーム画面のアイコン（17種類）、再生画面のスペクトラムアナライザー（初めはオフ）も
- **キャスト（試験的）**: 同じWi-FiネットワークのChromecastなど、Google Cast対応の機器で再生できます（ALAC・AIFF・WavPack・APEはWAVにして送ります。DSDとCDの直接再生は対象外）
- **ほかの音楽アプリの操作**: Spotify・Qobuz・Amazon Musicなどのストリーミングアプリで再生中の曲をライブラリの上部に表示し、このアプリと同じ再生画面（アンビエントモードを含む）で再生・一時停止・曲送り・シークを操作できます（「通知へのアクセス」の許可が必要。音はそのアプリからAndroid経由で再生されます）
- **Spotify**: Spotifyの曲・アルバム・プレイリストを検索やライブラリから選んで、公式Spotifyアプリで再生。次に再生される曲、キュー、お気に入り、プレイリストの編集、再生する機器（Spotify Connect）の切り替えにも対応（下の「Spotifyとの連携」を参照）
- **曲情報の編集**: 曲名・アーティスト・アルバム・年・ジャケットの変更（FLAC / MP3 / M4A / WAV / DSF）、取り込み済みの曲への曲情報の後付け
- **CUEシート**: 1つのWAV / FLACをCUEシートで曲ごとに分けて表示・再生（Shift_JIS / UTF-8）
- Material Design 3、ダイナミックカラー、日本語 / English

## 動作環境

- Android 6.0以上（Android 6.0〜7.1は試験的な対応。Android 4.0〜5.1向けには、別のアプリ「UCRT Legacy」（α版。CDの取り込み・書き込み・再生などストリーミング以外の機能。[Releases](../../releases) の `legacy-v` で始まるもの）があります。ダイナミックカラーはAndroid 12以上、すべてのファイルへのアクセスはAndroid 11以上、アプリ別の表示言語はAndroid 13以上。Android 11以前の通常の出力は192 kHzまで）
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
echo "sdk.dir=$HOME/android-sdk" > local.properties   # Android SDK(platform 37)の場所
gradle assembleRelease --no-daemon
```

2回目以降は、`tools/termux-build.sh` で「最新のソースの取得 → ビルド → ダウンロードフォルダへのAPKの配置」をまとめて実行できます。次の1行を一度実行しておけば、あとは `ucrt` と入力するだけです。

```bash
echo "alias ucrt='bash ~/UyatameCdRipper/tools/termux-build.sh'" >> ~/.bashrc && source ~/.bashrc
```

### PC（Android Studio / コマンドライン）

Android Studioでプロジェクトを開いてビルドするか、プロジェクト直下で次のコマンドを実行してください。Gradle Wrapperが、必要な版のGradleを自動でダウンロードします（JDK 17とAndroid SDKが必要です）。

```bash
./gradlew assembleRelease        # Windowsでは gradlew.bat assembleRelease
```

### テスト

内蔵デコーダー（FLAC・ALAC・WavPack・APE・AIFF）が元の音とビット単位で一致するか、文言のリソースが英語と日本語でそろっているかなどを、PC上の単体テストで確認できます。

```bash
./gradlew testDebugUnitTest
```

GitHubへのプッシュとプルリクエストのたびに、GitHub Actionsが単体テストとビルドを自動で実行します。

### 開発の流れ

最新のソースは `main` ブランチにあります。リリースは `main` に版のタグ（`v0.6.0` など）を付けて作成し、APKを [Releases](../../releases) に添付します。UCRT Legacyのリリースは別の番号で、タグは `legacy-v0.1.0-alpha` のような形（プレリリース）です。

### 署名について

プロジェクト直下に `keystore.properties` を置くと、その鍵でリリース版に署名します（このファイルと鍵はGitに含めないでください）。ファイルがない場合は、デバッグ鍵で署名します。

```properties
storeFile=/path/to/ucrt-release.jks
storePassword=********
keyAlias=ucrt
keyPassword=********
```

## キャスト（試験的）について

- キャスト先のテレビなどに表示される画面は、Googleが用意している標準の受信画面（Default Media Receiver）です。ジャケット・曲名・アーティスト・アルバム名・再生位置が表示されますが、並びや色はUCRTからは変えられず、機器やGoogle側の更新によって変わります。
- UCRT専用の受信画面を作れば見た目を自由に決められますが、そのためにはGoogle Castの開発者登録（登録料5米ドル、一度だけ）が必要です。キャストはまだ試験的な機能のため、専用の受信画面は作っていません。

## Spotifyとの連携

ストリーミングのタブで「Spotify」の機能をオンにすると、Spotifyのライブラリを探して、端末の公式Spotifyアプリで再生します。音はSpotifyアプリから出るため、このアプリのUSB DAC直接出力・イコライザー・DARはSpotifyの曲には使われません（Spotifyは、ほかのアプリが音声データを受け取ることを認めていません）。

使うには、Spotify Premiumと、[Spotify Developer Dashboard](https://developer.spotify.com/dashboard) での自分用のアプリの登録が必要です。

1. Developer Dashboardでアプリを作成し、使うAPIとして「Web API」と「Android」を選びます
2. Redirect URIに `ucrt-spotify://callback` を、Androidのパッケージ名に `com.uyatame.cdripper` を、SHA-1フィンガープリントにアプリのSpotifyの画面（ストリーミングのタブ →「Spotify」）に表示される値を登録します（フィンガープリントは、APKに署名した鍵によって変わります）
3. アプリのSpotifyの画面にクライアントIDを入力して、ログインします

### Spotify Premiumの有無で使える機能

Spotifyの開発モードでは、Developer Dashboardでアプリを登録した本人がSpotify Premiumに加入していないと、そのアプリ（クライアントID）でのログインもWeb APIも使えません（2026年2月からのSpotifyの規則）。このアプリは使う人がそれぞれ自分で登録する作りなので、**Premiumに加入していない場合は、Spotifyの機能にログインできません**。

Premiumの人が登録したアプリのUser Managementに追加してもらえば、無料アカウントでもそのクライアントIDでログインできます（登録した本人を含めて5人まで）。その場合、使える機能は次のとおりです。

| 機能 | Premium | 無料（Premiumの人の登録アプリに追加された場合） | 無料（自分で登録した場合） |
|---|---|---|---|
| ログイン | ○ | ○ | ×（登録した本人のPremiumが必要） |
| 検索・ライブラリ・プレイリストの閲覧 | ○ | ○ | × |
| お気に入り・保存・フォロー、プレイリストの編集 | ○ | ○ | × |
| 公式Spotifyアプリでの再生・一時停止・曲送り | ○ | △ Spotify無料プランの制限（シャッフル再生・スキップ回数など）がかかります | × |
| 曲を選んで再生・アルバムやプレイリストの途中の曲から再生 | ○ | △ 無料プランでは選んだ曲から始まらないことがあります | × |
| キューへの追加・再生する機器（Spotify Connect）の切り替え・音量 | ○ | × Web APIの再生の操作はPremium限定です | × |
| 次に再生される曲の表示 | ○ | 未確認（Spotifyの資料に記載がありません） | × |
| 「ほかの音楽アプリの操作」でSpotifyの再生を表示・操作 | ○ | ○ | ○（Spotifyの登録・ログインは不要） |

無料アカウントでの動作（△と未確認の項目）は、Spotifyの資料をもとにしたもので、実機では確かめていません。

クライアントIDとログイン情報（トークン）は端末の中だけに保存し、アプリやこのリポジトリには含めていません。ログインはPKCE方式のため、クライアントシークレットは使いません。開発モードのSpotifyアプリは、登録したアカウントを含めて最大5人まで使えます（ほかのアカウントはUser Managementに追加します）。

## 通信とプライバシー

インターネットに接続するのは、曲情報とジャケット画像を取得するとき（MusicBrainzとCover Art Archive）と、Spotifyの機能を使うとき（SpotifyのWeb APIとログイン）だけです。MusicBrainzに送信するのは、CDの目次から計算したディスクIDと、検索欄に入力した文字だけです（「MusicBrainzに登録」を使ったときは、MusicBrainzの登録画面に曲情報とCDの目次を入れて開きます。送信するかどうかは、MusicBrainzの画面でご自身が決めます。歌詞の登録を選んだときは、曲名・アーティスト・アルバム名・長さ・歌詞をLRCLIBに送ります）。Spotifyには、ログインと、Spotifyの画面で行った検索・一覧の取得のための通信だけを行います。利用状況の収集や広告はありません。

「通知へのアクセス」を許可した場合も、使うのはほかの音楽アプリの再生状態（曲名・ジャケット・再生位置）だけで、通知の内容は読み取りません。この情報が端末の外に送られることもありません。ジャケットを高画質で表示するために、そのアプリが示した画像のURL（各サービスの画像サーバー）から画像を読み込むことがあります。

キャストを使うときは、Chromecastが曲を読み込めるよう、再生する曲とジャケットだけを、同じネットワークに推測できないURLで一時的に公開します（端末の中の小さなHTTPサーバー）。キャストをやめると公開も終わります。

### 「通知へのアクセス」を許可できないとき

Android 13以降では、Google Play以外（GitHubなど）からインストールしたアプリは、最初は「通知へのアクセス」をオンにできません。次の手順で許可してください。

1. 端末の設定で「アプリ」→「UCRT」を開く
2. 右上の「︙」をタップし、「制限付き設定を許可」を選ぶ
3. UCRTの「設定」→「再生画面」→「ほかの音楽アプリ」から、もう一度「許可する」を押す

### 商標について

Spotify、Qobuz、Amazon Music、YouTube Musicなど、本書とアプリに記載しているサービス名は各社の商標です。UCRTはこれらのサービスとは関係がなく、提携や承認を受けたものではありません。

## ライセンス

このアプリは [GNU General Public License v3.0](LICENSE) で公開しています。
使用しているライブラリとデータの提供元は、[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) とアプリ内の「設定 → このアプリについて」で確認できます。

取り込んだ音源は、私的使用の範囲でご利用ください。

---

## English summary

UCRT turns an Android phone and a USB optical drive into a CD ripper and player.

- Rip audio CDs to FLAC, AAC, MP3 or WAV (with tags and cover art; FLAC and MP3 are encoded with FFmpeg while the CD is being read), with retries, C2 error detection and read offset correction
- Burn audio CDs: copy a CD with the same track layout and gaps, or write library tracks to a CD-R / CD-RW (SAO / TAO, test write)
- Automatic track info and cover art from MusicBrainz, with a choice of matches and search by name; track info you entered for a CD that isn't on MusicBrainz can be submitted to it (skipped if it's already there)
- A library of ripped albums and any music folders you add, with search, sorting, grid/list views and fast scrolling
- Bit-perfect output to USB DACs through the app's own USB audio driver, DSD playback, DAR (PCM upsampling / conversion to DSD), Source Direct and a parametric equalizer with a curve editor
- Plays FLAC, ALAC, WAV, AIFF, WavPack and APE with built-in decoders (bit-exact, independent of the device's codecs), DSD (DSF / DFF), and MP3 / AAC / Vorbis / Opus / MPEG-H using the system decoders
- CUE sheet support (one WAV / FLAC file split into tracks)
- A Streaming tab: shows and controls what other music apps are playing; optional Spotify features let you search your Spotify library and play it in the official Spotify app (requires Spotify Premium and your own app in the Spotify Developer Dashboard; the client ID is entered in the app and stored only on the device). Spotify audio does not go through this app's USB DAC output or equalizer. Logging in requires Spotify Premium for the account that registered the app in the Developer Dashboard; free accounts can only join an app registered by a Premium user, with free-tier playback limits.
- Full-screen player with ambient mode and audio format details, direct CD playback and lock screen controls
- Tag editing for ripped and existing files
- An Upsample tab: batch conversion between PCM and DSD (DSF / DFF, DSD64–512; FLAC / WAV up to 384 kHz) and DSRE high-frequency restoration, with normalize, fades and silence trimming; tags and cover art are carried over
- A spectrum analyzer on the player screen (off by default), selectable home-screen icons and an optional Liquid Glass look
- Sound check: collect reference tracks for comparing gear and settings, as easily as adding favorites
- Cast (experimental): play on a Chromecast or other Google Cast device on the same Wi-Fi network (ALAC, AIFF, WavPack and APE are sent as WAV; DSD and direct CD playback can't be cast). The TV shows Google's standard receiver screen; a custom UCRT receiver would require a Google Cast developer registration (a one-time US$5 fee), so it hasn't been made while casting is experimental
- Shows and controls what's playing in other music apps such as Spotify, Qobuz and Amazon Music (requires notification access; the audio still plays from that app through Android). On Android 13 or later, apps installed from outside Google Play need "Allow restricted settings" in App info before notification access can be turned on

To build it yourself, run `./gradlew assembleRelease` (JDK 17 and the Android SDK are required). Unit tests run with `./gradlew testDebugUnitTest`, and GitHub Actions runs them on every push and pull request. The latest source is on the `main` branch.

Requires Android 6.0 or later (Android 6.0–7.1 support is experimental; for Android 4.0–5.1 there is a separate app, "UCRT Legacy" (alpha; all features except streaming, released under `legacy-v*` tags)) and a device with USB OTG support. Licensed under GPL-3.0.

Spotify, Qobuz, Amazon Music, YouTube Music and other service names mentioned here are trademarks of their respective owners. UCRT is not affiliated with or endorsed by these services.
