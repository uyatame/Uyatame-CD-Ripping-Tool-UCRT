#!/data/data/com.termux/files/usr/bin/bash
# Termux で、UCRT Legacy をリリースする(main の最新のソースから、正式な鍵で署名したリリース版を作り、GitHub のリリースに置く)。
# 版は legacy/build.gradle.kts の legacyVersion(例: 0.1.0-alpha)。タグは legacy-v<版>、プレリリースとして作る
# (本家のアップデートの確認は「最新のリリース」だけを見るので、プレリリースの Legacy は混ざらない)。
# リリースの説明は CHANGELOG.md の「## UCRT Legacy v<版>」の項。
# 前もって: pkg install gh && gh auth login(GitHub にログイン)
# 使い方: bash ~/UyatameCdRipper/tools/termux-release-legacy.sh
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

    ver=$(sed -n 's/^val legacyVersion = "\(.*\)"/\1/p' legacy/build.gradle.kts)
    [ -n "$ver" ] || { echo "!! legacy/build.gradle.kts の legacyVersion が読めません"; exit 1; }
    tag="legacy-v$ver"
    if gh release view "$tag" >/dev/null 2>&1; then
        echo "!! $tag はもうリリースされています(legacyVersion と legacyVersionCode を上げてください)"
        exit 1
    fi

    # リリースの説明: CHANGELOG の「## UCRT Legacy v<版>」から次の「## 」の前まで
    notes=~/.cache/ucrt-legacy-notes.md
    mkdir -p ~/.cache
    awk -v h="## UCRT Legacy v$ver" 'index($0, h) == 1 { on = 1; next } on && /^## / { exit } on { print }' CHANGELOG.md > "$notes"
    [ -s "$notes" ] || { echo "!! CHANGELOG.md に「## UCRT Legacy v$ver」の項がありません"; exit 1; }

    echo "== ビルド (UCRT Legacy v$ver)"
    # テスト版の番号は付けない(リリースの版)
    gradle clean :legacy:assembleRelease --no-daemon --console=plain
    apk=legacy/build/outputs/apk/release/legacy-release.apk
    out=/storage/emulated/0/Download/UCRT_Legacy_v$ver.apk
    cp "$apk" "$out"
    ls -l "$out"

    echo "== リリース ($tag)"
    gh release create "$tag" "$out" --target main --prerelease --title "UCRT Legacy v$ver" --notes-file "$notes"
    echo "== できました"
}
main "$@"
exit
