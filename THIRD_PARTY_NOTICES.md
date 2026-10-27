# Third-party notices

このアプリは、以下のソフトウェアとデータを利用しています。ライセンスの全文は `app/src/main/assets/licenses/` にあり、アプリ内でも確認できます。

| 名前 | 用途 | ライセンス |
|---|---|---|
| [jump3r](https://codeberg.org/sciss/jump3r) 1.0.5（LAME 3.98.4のJava移植） | MP3エンコード | LGPL v2.1以降 |
| [FFmpeg](https://github.com/FFmpeg/FFmpeg) 8.1.3（LAME 3.100を組み込み） | CDの取り込みのFLAC・MP3の変換（改変せず、独立したプログラムとして同梱）。内蔵のAACデコーダーの符号表と、HE-AAC（SBR・パラメトリックステレオ）の処理（libavcodecのaacsbr・aacpsをKotlinに移植） | LGPL v2.1以降 |
| [minimp3](https://github.com/lieff/minimp3) | 内蔵のMP3デコーダー（Kotlinに移植） | CC0 1.0 |
| [JNA](https://github.com/java-native-access/jna) 5.14.0 | USB DAC直接出力（usbfsの呼び出し） | Apache License 2.0（Apache-2.0 / LGPL-2.1のデュアルライセンスから選択） |
| [Spotify App Remote SDK](https://github.com/spotify/android-sdk) 0.8.0 | 公式Spotifyアプリでの再生の操作（ビルド時にGitHubのリリースから取得し、SHA-256を確認して使用） | Apache License 2.0 |
| [Gson](https://github.com/google/gson) 2.10.1 | Spotify App Remote SDKが使うJSONの処理 | Apache License 2.0 |
| Kotlin / kotlinx.coroutines | 言語・非同期処理 | Apache License 2.0 |
| AndroidX（Core, Activity, Lifecycle, DataStore, DocumentFile） | Androidの基盤 | Apache License 2.0 |
| Jetpack Compose / Material 3 / Material Icons | 画面 | Apache License 2.0 |
| [ISRG Root X1 / X2](https://letsencrypt.org/certificates/)（Let's Encryptのルート証明書） | UCRT Legacyだけ: Android 7.0以前でMusicBrainzなどにつなぐときの証明書の確認（公開されている証明書をそのまま同梱） | 公開の証明書（ライセンスの定めなし） |
| [MusicBrainz](https://musicbrainz.org) | 曲情報 | CC0 1.0（コアデータ） |
| [Cover Art Archive](https://coverartarchive.org) | ジャケット画像 | 各画像の権利者による |

## jump3r（LGPL）について

jump3rは改変せず、独立したライブラリ（Maven: `de.sciss:jump3r:1.0.5`）として利用しています。ソースコードは上記のリポジトリとMaven Centralから入手できます。LGPLに基づき、このプロジェクトのソースコードから、ライブラリを差し替えたアプリをビルドできます。

## 本アプリ独自の実装

FLACエンコーダー、MP4・ID3タグの処理、USB光学ドライブの制御（SCSI/MMC）、USBオーディオ（UAC1/UAC2）ドライバー、DSD（DSF/DSDIFF）の読み込み・DoP・PCM変換、ID3v2の読み取り、`javax.sound.sampled` 互換クラスは本アプリ独自の実装で、本アプリと同じGPL v3で提供しています。AAC-LCのデコーダー（MP4・ADTSの読み込みを含む）と、Ogg Vorbisのデコーダーも本アプリ独自の実装です（AACの規格の符号表と、HE-AACのSBR・パラメトリックステレオはFFmpegのものを使用・移植。Vorbisは仕様のとおりに作り、細かい計算は[stb_vorbis](https://github.com/nothings/stb)（パブリックドメイン）と同じにしています）。AACのエンコードと、多くの形式の曲の再生には、Android OS内蔵のコーデックを使用しています。
