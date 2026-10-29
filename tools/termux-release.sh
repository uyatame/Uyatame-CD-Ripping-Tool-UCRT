#!/data/data/com.termux/files/usr/bin/bash
# Termux で、UCRT(いつものアプリ)をリリースする(main の最新のソースから、正式な鍵で署名したリリース版を作り、GitHub のリリースに置く)。
# 版は app/build.gradle.kts の versionName(例: 0.7.0)。タグは v<版>、通常のリリースで「最新のリリース」にする
# (アプリのアップデートの確認は「最新のリリース」を見る)。UCRT Legacy は tools/termux-release-legacy.sh。
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
    tag="v$ver"
    # 前に途中で止まった(APK を上げる途中で通信が切れたなど)ときは、下書きのリリースが残っている。それを使って続きからやる
    resume=""
    if gh release view "$tag" >/dev/null 2>&1; then
        if [ "$(gh release view "$tag" --json isDraft -q .isDraft)" = "true" ]; then
            resume=1
            echo "== 途中で止まった下書きのリリースがあるので、続きからやります ($tag)"
        else
            echo "!! $tag はもうリリースされています(versionName と versionCode を上げてください)"
            exit 1
        fi
    fi

    # リリースの説明: CHANGELOG の「## v<版>」から次の「## 」の前まで
    notes=~/.cache/ucrt-notes.md
    mkdir -p ~/.cache
    awk -v h="## v$ver " 'index($0, h) == 1 { on = 1; next } on && /^## / { exit } on { print }' CHANGELOG.md > "$notes"
    [ -s "$notes" ] || { echo "!! CHANGELOG.md に「## v$ver」の項がありません"; exit 1; }

    out=/storage/emulated/0/Download/UCRT_v$ver.apk
    # 続きからのときに、前に作った APK があればそれを使う(同じソースなので作り直さない)
    if [ -z "$resume" ] || [ ! -f "$out" ]; then
        echo "== ビルド (UCRT v$ver)"
        # テスト版の番号は付けない(リリースの版)
        gradle clean :app:assembleRelease --no-daemon --console=plain
        cp app/build/outputs/apk/release/app-release.apk "$out"
    fi
    ls -l "$out"

    echo "== リリース ($tag)"
    # まず下書きを作り、APK を上げ終わってから公開する(途中で通信が切れても、もう一度実行すれば続きからやる)
    [ -n "$resume" ] || gh release create "$tag" --target main --draft --title "$tag" --notes-file "$notes"
    ok=""
    for i in 1 2 3; do
        if gh release upload "$tag" "$out" --clobber; then ok=1; break; fi
        echo "!! APK を上げられませんでした。$((i * 10)) 秒後にやり直します ($i/3)"
        sleep $((i * 10))
    done
    [ -n "$ok" ] || { echo "!! APK を上げられませんでした。通信の良いところで、もう一度このスクリプトを実行してください(続きからやります)"; exit 1; }
    gh release edit "$tag" --draft=false --prerelease=false --latest --notes-file "$notes"
    echo "== できました"
    gh release view "$tag" --json url -q .url
}
main "$@"
exit
