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

## 3. 上游美术素材（**禁止商用**，不在本仓库中分发）

上游 README「许可」明确写明：

> - 代码：MIT
> - 素材（动画 / 提示词 / 源视频）：允许开源使用，**禁止商用**
> - 二次创作约定：基于本项目的衍生 / 改版 / 换皮作品，在任何介绍、展示、分发该作品的地方，
>   须附上原作者 GitHub 地址：<https://github.com/PC2005-cloud/dsh-pet>

涉及的素材包括：`dsh-pet/assets/webm`（动画）、`dsh-pet/assets/memes`（表情包）、
`dsh-pet/assets/pic`（光标/通知图标）、`dsh-pet/assets/config.jsonc`（默认配置）、
`dsh-pet/prompts`（提示词配方）与 `video/` 源视频。

- 本仓库**不包含**上述文件；`tools/fetch_upstream.sh` 会在构建时从上游克隆并转码。
- 构建产物（APK）中会包含这些素材的转码结果，因此**构建产物的使用同样受"禁止商用"约束**。
- 若要商用，请直接联系上游作者取得素材授权，或改用自己拥有权利的素材
  （`tools/pack_alpha.sh` 可直接把自己的透明动画转成所需格式）。

## 4. 字体

上游随仓库分发 `上首软糖体.ttf`。该字体的商业使用授权需另行确认，**本仓库不包含、构建脚本也不下载**
该字体（缺失时回退系统字体）。若你已取得授权，可自行放入 `app/src/main/assets/pet/fonts/`。

## 5. 运行时依赖

无第三方库依赖：仅使用 Android Framework（`WebView`、`WindowManager`、`HttpURLConnection`、`org.json`）。
