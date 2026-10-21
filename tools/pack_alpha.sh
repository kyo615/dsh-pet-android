#!/bin/bash
set -e
SRC=${SRC:-/tmp/dshpet3/dsh-pet/assets/webm}
OUT=${OUT:-/root/dsh-pet-android/app/src/main/assets/pet/webm}
one() {
  f="$1"; b=$(basename "$f" .webm)
  ffmpeg -v error -y -c:v libvpx-vp9 -i "$f" \
    -filter_complex "[0:v]format=rgba,split=2[c][a];[c]scale=480:270,format=yuv420p[c2];[a]alphaextract,scale=240:135,format=gray,pad=240:270:0:0:black,format=yuv420p[a2];[c2][a2]hstack=inputs=2,format=yuv420p[out]" \
    -map "[out]" -c:v libx264 -pix_fmt yuv420p -crf 26 -preset veryfast -r 24 -movflags +faststart \
    "$OUT/$b.mp4" </dev/null
}
export -f one
export OUT
ls "$SRC"/*.webm | xargs -P 4 -I{} bash -c 'one "$@"' _ {}
