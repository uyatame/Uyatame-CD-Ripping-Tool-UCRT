#!/data/data/com.termux/files/usr/bin/bash
# Termux で、最新のソースを取ってきてテスト用にビルドし、ダウンロードフォルダに APK を置く。
# 初回だけ次の 1 行を実行すると、以降は「ucrt」と打つだけで動く:
#   echo "alias ucrt='bash ~/UyatameCdRipper/tools/termux-build.sh'" >> ~/.bashrc && source ~/.bashrc
#
# 版の名前は「次にリリースする版.test<テスト版>」(例: v0.5.0.test0.0.1)。テスト版が 0.0.0 ならリリースの版(例: v0.5.0)。
# 次にリリースする版は app/build.gradle.kts の versionName、テスト版は tools/test-version.txt
# 途中で git pull がこのファイル自体を書き換えても、古い中身の続きを実行しないように、
# 全体を関数にして最初に読み込んでから動かす
main() {
    set -e
    cd "$(dirname "$0")/.."

    # 最新のソースは main にある。ほかのブランチにいたら main に切り替える
    branch=$(git rev-parse --abbrev-ref HEAD)
    if [ "$branch" != "main" ]; then
        echo "== main ブランチに切り替え ($branch → main)"
        git fetch origin main
        git checkout main 2>/dev/null || git checkout -b main origin/main
        branch=main
    fi
    echo "== 最新のソースを取得 ($branch)"
    git pull --ff-only origin "$branch"
    git log --oneline -1

    [ -f keystore.properties ] || echo "!! keystore.properties がありません(デバッグ鍵で署名されるため、いまのアプリに上書きできません)"

    base=$(sed -n 's/.*versionName = "\([0-9.]*\)".*/\1/p' app/build.gradle.kts)
    test=$(tr -d ' \r\n' < tools/test-version.txt)
    # テスト版が 0.0.0 のとき(リリースした直後)は、リリースの版として作る
    if [ -z "$test" ] || [ "$test" = "0.0.0" ]; then
        test=""
        ver="$base"
    else
        ver="$base.test$test"
    fi

    echo "== ビルド (v$ver)"
    # 進み具合: 終わったタスクの数 ÷ 前回のビルドのタスクの数(初回は 50 とみなす)
    cache=~/.cache/ucrt-build-tasks
    mkdir -p ~/.cache
    total=$(cat "$cache" 2>/dev/null || echo 50)
    log=~/.cache/ucrt-build.log
    set +e
    gradle clean assembleRelease --no-daemon --console=plain ${test:+-PtestVersion="$test"} 2>&1 | tee "$log" | awk -v total="$total" '
        /^> Task / { n++; p = int(n * 100 / total); if (p > 99) p = 99; printf "\r  %3d%%  %-40.40s", p, $3; fflush() }
        END { print "" }'
    rc=${PIPESTATUS[0]}
    set -e
    if [ "$rc" -ne 0 ]; then
        echo "!! ビルドに失敗しました。最後の 30 行:"
        tail -30 "$log"
        exit 1
    fi
    grep -c '^> Task ' "$log" > "$cache" || true
    printf "  100%%\n"

    apk=app/build/outputs/apk/release/app-release.apk
    out=/storage/emulated/0/Download/UCRT_v$ver.apk
    cp "$apk" "$out"
    # 古い Android(4.0〜5.1)向けの UCRT Legacy も同時にビルドされる(別のアプリとして入れられる)
    lapk=legacy/build/outputs/apk/release/legacy-release.apk
    # UCRT Legacy の版は本家と別(legacy/build.gradle.kts の legacyVersion。例: 0.1.0-alpha)
    lbase=$(sed -n 's/^val legacyVersion = "\(.*\)"/\1/p' legacy/build.gradle.kts)
    lver="${lbase:-$ver}${test:+.test$test}"
    lout=/storage/emulated/0/Download/UCRT_Legacy_v$lver.apk
    [ -f "$lapk" ] && cp "$lapk" "$lout"

    echo "== できました"
    ls -l "$out"
    [ -f "$lout" ] && ls -l "$lout"
    command -v aapt2 >/dev/null && aapt2 dump badging "$out" 2>/dev/null | head -1 || true
    command -v termux-open >/dev/null && termux-open "$out" || true
}
main "$@"
exit
