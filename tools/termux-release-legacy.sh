#!/data/data/com.termux/files/usr/bin/bash
# v0.9.0 から、UCRT Legacy は本家と同じリリースにまとめた(版の番号もそろえた)。リリースは tools/termux-release.sh で両方を作る。
echo "UCRT Legacy は本家と一緒にリリースします: bash $(dirname "$0")/termux-release.sh"
exit 1
