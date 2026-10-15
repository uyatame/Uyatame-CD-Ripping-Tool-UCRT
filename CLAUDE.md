# UCRT 開発メモ

## 版の付け方

- **リリースする版**: `v0.5.0` のような形。番号はユーザーが指定する(`app/build.gradle.kts` の `versionName`)。
  リリースのときは `versionCode` も 1 上げる。
- **リリースしないテスト用のビルド**: `v<次にリリースする版>.test<テスト版>`(例: `v0.5.0.test0.0.1`)。
  - `<次にリリースする版>` は `versionName` のまま(ユーザーが指定した次のリリースの番号)。
  - `<テスト版>` は `tools/test-version.txt` に書く x.x.x。変更を push するたびに、変更の大きさで自動で上げる:
    - 大きな変更(新しい機能・作りの大きな変更・互換性に関わる変更) → x を上げ、y と z を 0 に
    - 中くらいの変更(機能の追加・画面の変更) → y を上げ、z を 0 に
    - 小さな変更(不具合の修正・表示の調整・ビルドの道具) → z を上げる
  - 次の版をリリースしたら、テスト版は 0.0.0 に戻す。
  - `tools/termux-build.sh`(Termux で `ucrt`)が `-PtestVersion` を渡し、APK の版の名前とファイル名
    (`Download/UCRT_v0.5.0.test0.0.1.apk`)に付ける。普通の `gradle assembleRelease` では付かない。

## ユーザーの環境

- Termux でビルドする。貼り付けると行の先頭の文字が欠けることがあるので、コマンドは行頭に空白を入れて示す。
- ビルドは `ucrt` と打つだけ(`tools/termux-build.sh`)。APK は `/storage/emulated/0/Download/` に置く。
- 実機: Sony ZX707(8 コア)。
