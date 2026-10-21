#!/bin/bash
# 一键准备渲染素材：克隆上游 dsh-pet → 转码成「RGB+alpha 并排」的 H.264 → 生成 config.json
#
# 用法：
#   bash tools/fetch_upstream.sh            # 克隆到 .upstream/（默认）
#   UPSTREAM=/path/to/dsh-pet bash tools/fetch_upstream.sh
#
# 依赖：git、ffmpeg（带 libvpx-vp9/libx264）、bun（用来跑上游的 TS 配置合并）
set -e

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
UPSTREAM="${UPSTREAM:-$ROOT/.upstream/dsh-pet}"

if [ ! -d "$UPSTREAM" ]; then
  echo "==> 克隆上游 dsh-pet 到 $UPSTREAM"
  mkdir -p "$(dirname "$UPSTREAM")"
  git clone --depth 1 https://github.com/PC2005-cloud/dsh-pet.git "$UPSTREAM"
fi

if [ ! -d "$UPSTREAM/dsh-pet/assets/webm" ]; then
  echo "!! 上游目录结构不符合预期：$UPSTREAM/dsh-pet/assets/webm 不存在" >&2
  exit 1
fi

echo "==> 转码 + 生成配置"
UPSTREAM="$UPSTREAM/dsh-pet" bash "$ROOT/tools/build_assets.sh"

echo
echo "完成。素材已就位："
du -sh "$ROOT/app/src/main/assets/pet" 2>/dev/null || true
