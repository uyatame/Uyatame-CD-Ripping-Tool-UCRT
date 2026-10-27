#!/data/data/com.termux/files/usr/bin/bash
# Termux で、ダウンロードにあるソースの zip(UCRT_v*_source.zip。まだ GitHub に無いテスト版)からビルドし、
# ダウンロードフォルダに APK を置く(termux-build.sh の zip 版。置く場所・名前・進み具合の出し方は同じ)。
# 初回だけ次の 1 行を実行すると、以降は「ucrtz」と打つだけで動く:
#   echo "alias ucrtz='bash ~/UyatameCdRipper/tools/termux-build-zip.sh'" >> ~/.bashrc && source ~/.bashrc
#
# 使い方:
#   ucrtz            ダウンロードにある、いちばん新しいソースの zip からビルドする
#   ucrtz <zip>      指定した zip からビルドする
#   ucrtz legacy     ビルドのあと、UCRT Legacy のほうのインストール画面を開く(いつもは本家のほう)
# 展開する場所は ~/ucrt-test(毎回作り直す)。署名の鍵は ~/UyatameCdRipper/keystore.properties を使う。
# このファイル自体が書き換えられても古い中身の続きを実行しないように、全体を関数にして最初に読み込んでから動かす
main() {
    set -e
    dl=/storage/emulated/0/Download
    repo=~/UyatameCdRipper
    work=~/ucrt-test

    zip=""
    open=app
    for a in "$@"; do
        case "$a" in
            legacy) open=legacy ;;
            *) zip="$a" ;;
        esac
    done
    if [ -z "$zip" ]; then
        zip=$(ls -t "$dl"/UCRT_v*_source.zip 2>/dev/null | head -1 || true)
    fi
    if [ -z "$zip" ] || [ ! -f "$zip" ]; then
        echo "!! ソースの zip が見つかりません(ダウンロードに UCRT_v…_source.zip を置いてください)"
        exit 1
    fi
    echo "== ソース: $(basename "$zip")"

    echo "== 展開 ($work)"
    rm -rf "$work"
    mkdir -p "$work"
    cd "$work"
    unzip -q "$zip"

    # 署名の鍵(Git にも zip にも含めない)。storeFile が app のフォルダからの場所なら、元の場所を指すように書き換える
    if [ -f "$repo/keystore.properties" ]; then
        sed "s#^storeFile[ ]*=[ ]*\([^/ ].*\)\$#storeFile=$repo/app/\1#" "$repo/keystore.properties" > keystore.properties
    else
        echo "!! keystore.properties がありません(デバッグ鍵で署名されるため、いまのアプリに上書きできません)"
    fi

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
    out=$dl/UCRT_v$ver.apk
    cp "$apk" "$out"
    # 古い Android(4.0〜5.1)向けの UCRT Legacy も同時にビルドされる(別のアプリとして入れられる)
    lapk=legacy/build/outputs/apk/release/legacy-release.apk
    # UCRT Legacy の版は本家と別(legacy/build.gradle.kts の legacyVersion。例: 0.1.0-alpha)
    lbase=$(sed -n 's/^val legacyVersion = "\(.*\)"/\1/p' legacy/build.gradle.kts)
    lver="${lbase:-$ver}${test:+.test$test}"
    lout=$dl/UCRT_Legacy_v$lver.apk
    [ -f "$lapk" ] && cp "$lapk" "$lout"

    echo "== できました"
    ls -l "$out"
    [ -f "$lout" ] && ls -l "$lout"
    command -v aapt2 >/dev/null && aapt2 dump badging "$out" 2>/dev/null | head -1 || true
    target=$out
    [ "$open" = legacy ] && [ -f "$lout" ] && target=$lout
    command -v termux-open >/dev/null && termux-open "$target" || true
}
main "$@"
exit
