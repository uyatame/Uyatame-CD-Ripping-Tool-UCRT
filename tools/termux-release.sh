#!/data/data/com.termux/files/usr/bin/bash
# Termux で、UCRT をリリースする(main の最新のソースから、正式な鍵で署名したリリース版を作り、GitHub のリリースに置く)。
# v0.9.0 から、本家と UCRT Legacy を 1 つのリリースにまとめる(版の番号もそろえる)。
# 版は app/build.gradle.kts の versionName と legacy/build.gradle.kts の legacyVersion(同じにしておく)。
# タグは v<版>、通常のリリースで「最新のリリース」にする(本家の更新の確認は「最新のリリース」を見る)。
# 置くもの: UCRT_v<版>.apk(本家)・UCRT_Legacy_v<版>.apk(Legacy)。
# 以前の Legacy(v0.3.0 まで)は legacy-v のタグしか見ないので、移行のため legacy-v<版> の案内のリリースにも Legacy の APK を置く
# (「最新のリリース」にはしない)。
# リリースの説明は CHANGELOG.md の「## v<版>」の項。
# 前もって: pkg install gh && gh auth login(GitHub にログイン)
# 使い方: bash ~/UyatameCdRipper/tools/termux-release.sh
main() {
    set -e
    cd "$(dirname "$0")/.."
    command -v gh >/dev/null || { echo "!! gh がありません: pkg install gh && gh auth login"; exit 1; }
    gh auth status >/dev/null 2>&1 || { echo "!! GitHub にログインしていません: gh auth login"; exit 1; }
    [ -f keystore.properties ] || { echo "!! keystore.properties がありません(リリースは正式な鍵で署名します)"; exit 1; }

    branch=$(git rev-parse --abbrev-ref HEAD)
    if [ "$branch" != "main" ]; then
        git fetch origin main
        git checkout main 2>/dev/null || git checkout -b main origin/main
    fi
    git pull --ff-only origin main
    git log --oneline -1

    ver=$(sed -n 's/^ *versionName = "\([^"]*\)".*/\1/p' app/build.gradle.kts)
    [ -n "$ver" ] || { echo "!! app/build.gradle.kts の versionName が読めません"; exit 1; }
    lver=$(sed -n 's/^val legacyVersion = "\(.*\)"/\1/p' legacy/build.gradle.kts)
    [ "$ver" = "$lver" ] || { echo "!! 本家の版 ($ver) と Legacy の版 ($lver) が違います。同じにしてください"; exit 1; }
    tag="v$ver"
    ltag="legacy-v$ver"
    # 前に途中で止まった(APK を上げる途中で通信が切れたなど)ときは、下書きのリリースが残っている。それを使って続きからやる
    resume=""
    done_main=""
    if gh release view "$tag" >/dev/null 2>&1; then
        if [ "$(gh release view "$tag" --json isDraft -q .isDraft)" = "true" ]; then
            resume=1
            echo "== 途中で止まった下書きのリリースがあるので、続きからやります ($tag)"
        elif gh release view "$ltag" >/dev/null 2>&1 && [ "$(gh release view "$ltag" --json isDraft -q .isDraft)" = "false" ]; then
            echo "!! $tag はもうリリースされています(versionName・legacyVersion と versionCode を上げてください)"
            exit 1
        else
            # 本家のリリースは済んで、以前の Legacy への案内の途中で止まった: 案内だけ続きからやる
            done_main=1
            resume=1
            echo "== $tag は公開済み。以前の UCRT Legacy への案内 ($ltag) の続きからやります"
        fi
    fi

    # リリースの説明: CHANGELOG の「## v<版>」から次の「## 」の前まで
    notes=~/.cache/ucrt-notes.md
    mkdir -p ~/.cache
    awk -v h="## v$ver " 'index($0, h) == 1 { on = 1; next } on && /^## / { exit } on { print }' CHANGELOG.md > "$notes"
    [ -s "$notes" ] || { echo "!! CHANGELOG.md に「## v$ver」の項がありません"; exit 1; }

    out=/storage/emulated/0/Download/UCRT_v$ver.apk
    lout=/storage/emulated/0/Download/UCRT_Legacy_v$ver.apk
    # 続きからのときに、前に作った APK があればそれを使う(同じソースなので作り直さない)
    if [ -z "$resume" ] || [ ! -f "$out" ] || [ ! -f "$lout" ]; then
        echo "== ビルド (UCRT v$ver と UCRT Legacy v$ver)"
        # テスト版の番号は付けない(リリースの版)
        gradle clean :app:assembleRelease :legacy:assembleRelease --no-daemon --console=plain
        cp app/build/outputs/apk/release/app-release.apk "$out"
        cp legacy/build/outputs/apk/release/legacy-release.apk "$lout"
    fi
    ls -l "$out" "$lout"

    upload() { # $1 = タグ、あとは置くファイル
        local t=$1; shift
        for i in 1 2 3; do
            if gh release upload "$t" "$@" --clobber; then return 0; fi
            echo "!! APK を上げられませんでした。$((i * 10)) 秒後にやり直します ($i/3)"
            sleep $((i * 10))
        done
        echo "!! APK を上げられませんでした。通信の良いところで、もう一度このスクリプトを実行してください(続きからやります)"
        exit 1
    }

    if [ -z "$done_main" ]; then
        echo "== リリース ($tag)"
        # まず下書きを作り、APK を上げ終わってから公開する(途中で通信が切れても、もう一度実行すれば続きからやる)
        [ -n "$resume" ] || gh release create "$tag" --target main --draft --title "$tag" --notes-file "$notes"
        upload "$tag" "$out" "$lout"
        gh release edit "$tag" --draft=false --prerelease=false --latest --notes-file "$notes"
    fi
    url=$(gh release view "$tag" --json url -q .url)

    # 以前の Legacy(legacy-v のタグしか見ない)への案内のリリース。「最新のリリース」にはしない
    echo "== 以前の UCRT Legacy への案内 ($ltag)"
    lnotes=~/.cache/ucrt-legacy-notes.md
    printf '%s\n\n%s\n' "UCRT Legacy は v$ver から、本家と同じリリースにまとめました(版の番号も本家とそろえました)。変更点はこちら: $url" \
        "Since v$ver, UCRT Legacy is released together with UCRT (same version number). Release notes: $url" > "$lnotes"
    if ! gh release view "$ltag" >/dev/null 2>&1; then
        gh release create "$ltag" --target main --draft --title "UCRT Legacy v$ver" --notes-file "$lnotes"
    fi
    upload "$ltag" "$lout"
    gh release edit "$ltag" --draft=false --prerelease=false --latest=false --notes-file "$lnotes"
    # 案内のリリースを作ったあとも、本家のリリースが「最新のリリース」のままにする
    gh release edit "$tag" --latest

    echo "== できました"
    echo "$url"
}
main "$@"
exit
