# NOTICE · 第三方组件与素材来源

本项目（dsh-pet-android）是一个**非官方**的安卓移植，由以下部分组成。

## 1. 本项目自写代码（MIT）

- `app/src/main/java/com/dshpet/pet/**`（安卓壳：悬浮窗、触摸转发、本地资源服务、AI 调用、设置页、ROM 适配）
- `app/src/main/assets/pet/android-shim.js`（渲染适配层：WebGL 合成、合成指针事件、宿主接口桩、长按菜单）
- `tools/**`（素材转码与构建脚本）

Copyright (c) 2026 dsh-pet-android contributors — 见 [LICENSE](LICENSE)。

## 2. 上游 dsh-pet（MIT 代码）

- 仓库：<https://github.com/PC2005-cloud/dsh-pet>
- 版权：Copyright (c) 2026 PC2005-cloud
- 许可：MIT
- 本项目内联的上游文件：
  - `app/src/main/assets/pet/index.html`
  - `app/src/main/assets/pet/constants.js`（仅改动 `WINDOW_MARGIN_RATIO`：0.5 → 0.32）
  - `app/src/main/assets/pet/sprite.js`
  - `app/src/main/assets/pet/events.js`
  - `app/src/main/assets/pet/renderer.js`
  - `app/src/main/assets/pet/shared-core.js`（由上游 `src/shared` 构建出的 IIFE）

## 3. 上游美术素材（**禁止商用**）

上游 README「许可」明确写明：

> - 代码：MIT
> - 素材（动画 / 提示词 / 源视频）：允许开源使用，**禁止商用**
> - 二次创作约定：基于本项目的衍生 / 改版 / 换皮作品，在任何介绍、展示、分发该作品的地方，
>   须附上原作者 GitHub 地址：<https://github.com/PC2005-cloud/dsh-pet>

本仓库包含的素材：`app/src/main/assets/pet/webm`（动画，构建期由上游 WebM 转码为
RGB+alpha 并排 H.264）、`memes`（表情包）、`pic`（光标图标）、`config.json`（由上游
`assets/config.jsonc` 经上游合并逻辑生成）。这些属于上游条款允许的「开源使用」。

- **禁止商用**：付费分发、付费社群、广告变现、随硬件捆绑销售等任何商业用途均超出授权。
- 若要商用，请联系上游作者取得素材授权，或改用自己拥有权利的素材
  （`tools/pack_alpha.sh` 可直接把自己的透明动画转成所需格式）。
- `tools/fetch_upstream.sh` 可从上游重新生成全部素材（换素材/升级上游时用）。

## 4. 字体

上游随仓库分发 `上首软糖体.ttf`，该字体授权不明，**本仓库不包含它**。

气泡字体改用 **站酷快乐体 ZCOOL KuaiLe**（Google Fonts，SIL Open Font License 1.1），
文件 `app/src/main/assets/pet/fonts/ui.ttf`，许可全文 `licenses/ZCOOLKuaiLe-OFL.txt`。
OFL 允许自由使用、修改与再分发（不得单独售卖字体本身）。

## 5. 运行时依赖

无第三方库依赖：仅使用 Android Framework（`WebView`、`WindowManager`、`HttpURLConnection`、`org.json`）。
