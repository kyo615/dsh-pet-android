#!/bin/bash
# 从上游 dsh-pet 仓库重生成安卓渲染资源（在服务器上跑）
#   1) 106 段 VP9-alpha webm → 「左 480×270 RGB + 右 240×135 alpha」的 H.264 mp4
#   2) 用上游 readAllConfig 生成成品 config.json（bun 直接跑 TS 源码）
set -e
UP=${UPSTREAM:-/tmp/dshpet3/dsh-pet}
ASSETS=$(cd "$(dirname "$0")/.." && pwd)/app/src/main/assets/pet
mkdir -p "$ASSETS/webm" "$ASSETS/memes" "$ASSETS/fonts" "$ASSETS/pic"
# 字体默认不带（上首软糖体的商用授权需自行确认）；确有权使用再 WITH_FONT=1
if [ "${WITH_FONT:-0}" = "1" ]; then
  cp "$UP"/assets/fonts/*.ttf "$ASSETS/fonts/" 2>/dev/null || true
fi
cp "$UP"/assets/memes/*.png "$ASSETS/memes/"
cp "$UP"/assets/pic/cursor-grab.png "$UP"/assets/pic/cursor-grabbing.png "$ASSETS/pic/"
SRC="$UP/assets/webm" OUT="$ASSETS/webm" bash "$(dirname "$0")/pack_alpha.sh"
cat > /tmp/mkcfg.mjs <<'JS'
import { readFileSync, writeFileSync } from 'node:fs';
const { readAllConfig } = await import(process.env.UP + '/src/host/config.ts');
const merged = readAllConfig({
  defaultFile: process.env.UP + '/assets/config.jsonc',
  userFile: '/tmp/nonexistent/main-config.jsonc',
  petDir: '/tmp/nonexistent/pet',
});
const main = merged.main;
main.pets = [{ name: '蓝毛小女仆', id: 'main', size: 160, balanceEnabled: false,
  whisperEnabled: true, workStatusEnabled: false, fixedEnabled: true, display: 'desktop',
  position: { corner: 'bottom-right', marginX: 12, marginY: 20 } }];
main.font = { family: 'candy', size: 15, followPet: false };
main.confineToScreen = true;
main.chatImageEnabled = false;
main.whisperImageEnabled = true;
main.notificationsEnabled = false;
main.eventsRefreshSec = { balance: 1800, whisper: 300 };
writeFileSync(process.env.OUT_CFG, JSON.stringify(merged));
JS
UP="$UP" OUT_CFG="$ASSETS/config.json" bun run /tmp/mkcfg.mjs
echo "assets ready: $(du -sh "$ASSETS" | cut -f1)"
