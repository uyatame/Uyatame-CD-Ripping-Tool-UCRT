#!/data/data/com.termux/files/usr/bin/bash
# Termux で、最新のソースを取ってきてビルドし、ダウンロードフォルダに APK を置く。
# 初回だけ次の 1 行を実行すると、以降は「ucrt」と打つだけで動く:
#   echo "alias ucrt='bash ~/UyatameCdRipper/tools/termux-build.sh'" >> ~/.bashrc && source ~/.bashrc
set -e
cd "$(dirname "$0")/.."

branch=$(git rev-parse --abbrev-ref HEAD)
echo "== 最新のソースを取得 ($branch)"
git pull --ff-only origin "$branch"
git log --oneline -1

[ -f keystore.properties ] || echo "!! keystore.properties がありません(デバッグ鍵で署名されるため、いまのアプリに上書きできません)"

echo "== ビルド"
gradle clean assembleRelease --no-daemon -q

apk=app/build/outputs/apk/release/app-release.apk
ver=$(sed -n 's/.*versionName = "\(.*\)".*/\1/p' app/build.gradle.kts)
out=/storage/emulated/0/Download/UCRT_v$ver.apk
cp "$apk" "$out"

echo "== できました"
ls -l "$out"
command -v aapt2 >/dev/null && aapt2 dump badging "$out" 2>/dev/null | head -1 || true
command -v termux-open >/dev/null && termux-open "$out" || true
