这个目录：

- index.html / constants.js / sprite.js / events.js / renderer.js
  来自上游开源项目 dsh-pet（MIT），见仓库根目录 NOTICE.md。
- shared-core.js  上游 src/shared 的构建产物（bun 打包成 IIFE，window.PetShared）。
- android-shim.js 本项目新增的安卓适配层（MIT）。
- webm/           106 段动画：由上游 VP9-alpha WebM 转码成「左 480x270 RGB + 右 240x135 alpha」
                  的 H.264，运行时用 WebGL 合成透明（见 tools/pack_alpha.sh）。
- memes/ pic/     上游表情包与光标图标（上游条款：允许开源使用、禁止商用）。
- config.json     由上游 readAllConfig 生成的成品配置（tools/build_assets.sh）。
- fonts/ui.ttf    站酷快乐体（SIL OFL 1.1，见 licenses/ZCOOLKuaiLe-OFL.txt）。

重新生成/替换素材：bash tools/fetch_upstream.sh
