#!/usr/bin/env bash
# CD の取り込みの変換(FLAC・MP3)に使う、このアプリ専用の小さな FFmpeg を作る。
# GitHub Actions(.github/workflows/ffmpeg.yml)で動かし、できたものをこのリポジトリのリリースに置く。
# アプリのビルドは、そのリリースから取ってきて SHA-256 を確かめ、APK の lib/<ABI>/libffmpeg.so に入れる。
#
# - 対応: armeabi-v7a・arm64-v8a・x86_64、Android 5.0(API 21)から。16 KB のページにも対応
# - 入れるもの: PCM(s16le)の読み込み、FLAC のエンコーダー、LAME(MP3)、FLAC・MP3 の書き出しだけ
# - ライセンス: FFmpeg・LAME とも LGPL(GPL の部品は使わない)
#
# 使い方: ANDROID_NDK_HOME=<NDK> tools/ffmpeg/build.sh <出力先>
set -euo pipefail

FFMPEG_TAG=n8.1.3
FFMPEG_COMMIT=1041abdc962f4cc4f394aa8de9dc5236c0c3b9e7
LAME_VER=3.100
LAME_SHA256=ddfe36cab873794038ae2c1210557ad34857a4b6bdc515785d1da9e175b1da1e
API=21

OUT=$(realpath -m "${1:?出力先を指定してください}")
NDK=${ANDROID_NDK_HOME:?ANDROID_NDK_HOME を指定してください}
TC=$NDK/toolchains/llvm/prebuilt/linux-x86_64
WORK=$(mktemp -d)
JOBS=$(nproc)
mkdir -p "$OUT"

echo "== ソースの取得"
git clone -q --depth 1 -b "$FFMPEG_TAG" https://github.com/FFmpeg/FFmpeg "$WORK/ffmpeg"
got=$(git -C "$WORK/ffmpeg" rev-parse HEAD)
[ "$got" = "$FFMPEG_COMMIT" ] || { echo "FFmpeg のコミットが違います: $got"; exit 1; }
curl -fsSL -o "$WORK/lame.tar.gz" "https://downloads.sourceforge.net/project/lame/lame/$LAME_VER/lame-$LAME_VER.tar.gz"
echo "$LAME_SHA256  $WORK/lame.tar.gz" | sha256sum -c -
tar -xzf "$WORK/lame.tar.gz" -C "$WORK"

build() {
    local abi=$1 triple=$2 host=$3 arch=$4 cpu=$5
    local cc=$TC/bin/$triple$API-clang
    local pre=$WORK/prefix-$abi
    local ldf="-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384"
    echo "== LAME ($abi)"
    rm -rf "$WORK/lame-build" && cp -r "$WORK/lame-$LAME_VER" "$WORK/lame-build"
    # 3.100 の書き出す記号の一覧に、無い関数が残っている(共有ライブラリを作るときだけ問題になるが、念のため消す)
    sed -i '/lame_init_old/d' "$WORK/lame-build/include/libmp3lame.sym"
    (cd "$WORK/lame-build" && ./configure -q --host="$host" --prefix="$pre" \
        --disable-shared --enable-static --disable-frontend --disable-decoder --disable-gtktest \
        CC="$cc" AR="$TC/bin/llvm-ar" RANLIB="$TC/bin/llvm-ranlib" CFLAGS="-O2 -fPIC" LDFLAGS="$ldf" \
        && make -s -j"$JOBS" && make -s install)

    echo "== FFmpeg ($abi)"
    rm -rf "$WORK/ff-build" && mkdir "$WORK/ff-build"
    local extra=()
    [ "$abi" = x86_64 ] && extra+=(--disable-x86asm)
    [ "$abi" = armeabi-v7a ] && extra+=(--enable-neon)
    (cd "$WORK/ff-build" && "$WORK/ffmpeg/configure" \
        --target-os=android --arch="$arch" --cpu="$cpu" --enable-cross-compile \
        --cc="$cc" --cxx="$cc++" --nm="$TC/bin/llvm-nm" --ar="$TC/bin/llvm-ar" \
        --ranlib="$TC/bin/llvm-ranlib" --strip="$TC/bin/llvm-strip" \
        --enable-pic --enable-static --disable-shared \
        --disable-everything --disable-autodetect --disable-doc --disable-debug --disable-network \
        --disable-ffplay --disable-ffprobe --disable-avdevice --disable-swscale \
        --enable-protocol=pipe,file --enable-demuxer=pcm_s16le --enable-decoder=pcm_s16le \
        --enable-encoder=flac,libmp3lame --enable-muxer=flac,mp3 \
        --enable-filter=abuffer,abuffersink,aformat,anull,aresample,format,null \
        --enable-libmp3lame \
        --extra-cflags="-O3 -fPIC -I$pre/include" --extra-ldflags="-L$pre/lib -pie $ldf" \
        "${extra[@]}" > configure.log 2>&1 || { tail -40 configure.log; tail -40 ffbuild/config.log; exit 1; } \
        && make -s -j"$JOBS" ffmpeg)
    mkdir -p "$OUT/lib/$abi"
    "$TC/bin/llvm-strip" -o "$OUT/lib/$abi/libffmpeg.so" "$WORK/ff-build/ffmpeg"
    echo "-- $abi: $(stat -c %s "$OUT/lib/$abi/libffmpeg.so") bytes"
    "$TC/bin/llvm-readelf" -d "$OUT/lib/$abi/libffmpeg.so" | grep NEEDED
    "$TC/bin/llvm-readelf" -l "$OUT/lib/$abi/libffmpeg.so" | grep -A1 LOAD | head -4
}

build armeabi-v7a armv7a-linux-androideabi arm-linux arm armv7-a
build arm64-v8a aarch64-linux-android aarch64-linux aarch64 armv8-a
build x86_64 x86_64-linux-android x86_64-linux x86_64 x86-64

# ライセンスと、作り方の記録(LGPL の求める「元のソースの入手先」)
cp "$WORK/ffmpeg/COPYING.LGPLv2.1" "$OUT/COPYING.LGPLv2.1"
cp "$0" "$OUT/build.sh"
cat > "$OUT/README.txt" <<TXT
UCRT 用の FFmpeg(CD の取り込みの FLAC・MP3 の変換に使用)
FFmpeg $FFMPEG_TAG ($FFMPEG_COMMIT) https://github.com/FFmpeg/FFmpeg
LAME $LAME_VER https://lame.sourceforge.io/
Android NDK $(sed -n 's/^Pkg.Revision *= *//p' "$NDK/source.properties")、API $API
どちらも GNU LGPL で、改変せずに build.sh の設定でビルドしています。
TXT
echo "== 完了"
