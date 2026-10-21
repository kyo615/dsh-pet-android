# dsh-pet-android

> **dsh-pet 的安卓移植版**：把开源桌宠 [dsh-pet](https://github.com/PC2005-cloud/dsh-pet) 的渲染层搬进安卓悬浮窗——
> 透明置顶、拖动甩抛、长按菜单、100+ 段手绘风动画，可选接自己的 AI 接口做碎碎念与对话。
>
> **非官方移植**，与上游作者无隶属关系。代码 MIT，美术素材的授权见 [授权](#授权) 一节。
>
> 仓库：<https://github.com/kyo615/dsh-pet-android>

<sub>English: an unofficial Android port of the open-source desktop pet *dsh-pet*. Transparent always-on-top
overlay, 106 hand-drawn animations, drag/throw physics, long-press menu, optional OpenAI-compatible AI for
chatter and chat. The app renders through a full-screen non-touchable window plus a small touch window, and
composites RGB+alpha video in WebGL because Android WebView drops the alpha plane of VP9-alpha WebM.</sub>

---

## 特性

- **悬浮窗常驻**：透明、无边框、盖在其它应用之上；宠物身体以外**不挡触摸**（只有一小块触摸窗跟随宠物）。
- **动画与物理**：106 段 640×360 / 24fps 动画（待机呼吸、转向、点击回应、被拖拽、各种小动作），
  拖动为弹簧跟手，甩出去按抛物线飞行、撞屏幕边缘反弹、落地 Q 弹。
- **长按菜单**：碎碎念 / 对话 / 动作点播（按分类逐级展开）/ 回到原位。
- **可选 AI**：填 OpenAI 兼容接口（DeepSeek、one-api 等都行）即可定时碎碎念、和宠物聊天（带记忆）。
  **不填也能用**——内置 30 条台词 + 随机表情包，动画与互动完全不受影响。
- **帧率可调**：12 / 15 / 18 / 20 / 24 fps，低端机降帧省电，高端机满帧顺滑。
- **国产 ROM 适配**：按品牌跳转自启动管理页（小米/OPPO/realme/一加/vivo/iQOO/荣耀/华为/三星/魅族），
  跳不过去时给出该品牌当前版本的手动路径。
- **省电**：息屏暂停视频与定时碎碎念；WebGL 只在画面变化时上传纹理。
- 开机自启、前台服务常驻通知、无网络权限以外的采集（不收集任何数据）。

## 与上游项目的关系（请先读）

| 部分 | 来源 | 授权 |
|---|---|---|
| 安卓壳（Java、悬浮窗、触摸、AI、设置页） | 本仓库 | MIT |
| 渲染层 `index.html / sprite.js / events.js / constants.js / renderer.js / shared-core.js` | 上游 dsh-pet | MIT |
| 动画 / 表情包 / 提示词 / 源视频 | 上游 dsh-pet | **允许开源使用，禁止商用** |
| 字体（上首软糖体） | 上游 dsh-pet | 本仓库**不包含**，商用请自行确认字体授权 |

本仓库**不包含任何美术素材**：`tools/fetch_upstream.sh` 会克隆上游仓库并在构建时转码生成，
它们不会进入 git 历史。这样仓库本身只分发 MIT 代码，美术素材的授权责任边界清楚。

## 构建

前置：JDK 17、Android SDK 35、Gradle 8.x/9.x、`git`、`ffmpeg`（需带 libvpx-vp9 / libx264）、`bun`。

```bash
git clone https://github.com/kyo615/dsh-pet-android.git
cd dsh-pet-android

# 1) 拉取上游素材并转码（约 2 分钟，产出 app/src/main/assets/pet/{webm,memes,pic,fonts,config.json}）
bash tools/fetch_upstream.sh

# 2) 构建
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=/path/to/android-sdk
gradle :app:assembleRelease
# 产物：app/build/outputs/apk/release/app-release.apk
```

- 默认用 debug 签名，方便直接安装；正式发布请换自己的 keystore（`app/build.gradle` 的 `signingConfigs`）。
- 首次构建需要联网拉 Android Gradle Plugin；之后可加 `--offline`。
- 素材是构建期生成的，新增/替换动画只要把 `tools/pack_alpha.sh` 的输入换成自己的素材即可。

## 使用

1. 打开应用 → 授予「显示在其他应用上层」→ 点「启动桌宠」。
2. 建议再开「电池优化白名单」，以及到系统里允许自启动（设置页有按品牌的一键跳转与手动路径）。
3. 拖动 = 按住宠物拖；长按 ≈ 0.7 秒 = 菜单；点一下 = 回应动画。
4. AI（可选）：填接口地址、密钥、模型名 → 测试连接 → 保存。

## 技术实现

```
┌───────────── 渲染窗（全屏 / 不可触摸 / 不可获焦）─────────────┐
│  WebView: dsh-pet 渲染器 + android-shim.js                    │
│  ├─ WebGL：左半 RGB + 右半 alpha 的 H.264 → premultiplied RGBA │
│  └─ 宠物用 transform 移动（不移动窗口）                        │
└───────────────────────────────────────────────────────────────┘
┌──── 触摸窗（只有宠物身体大小，可穿透）────┐
│  View.onTouchEvent → JS 合成 PointerEvent │
└───────────────────────────────────────────┘
菜单/对话打开时：渲染窗临时切成「原生触摸（对话再可获焦）」，触摸窗关掉
```

几个关键决定：

1. **为什么不用 VP9-alpha WebM 直接播**：安卓 WebView 的视频合成会丢掉 alpha（黑底），
   软件光栅化又根本不解码。所以构建期把动画转成「左 480×270 彩色 + 右 240×135 灰度 alpha」的
   H.264（`tools/pack_alpha.sh`），运行时用 WebGL 着色器采样右半亮度当 alpha，透明与解码器无关。
2. **为什么要两块窗**：安卓悬浮窗是矩形，整块都会吃触摸。渲染窗全屏但 `FLAG_NOT_TOUCHABLE`，
   触摸窗只有宠物身体那么大，其余区域完全穿透。
3. **为什么菜单/对话切原生触摸**：子菜单靠 `mouseenter` 展开、"点外面关闭"监听 `document` 的
   `mousedown`、输入框要软键盘（窗口必须可触摸 + 可获焦）。合成事件喂不出这些，所以打开时切原生，
   关闭后立刻切回，另有看门狗兜底防止状态卡住。
4. **本地资源**：`LocalServer` 只监听 `127.0.0.1` 随机端口，按上游的路径规则提供素材（支持 Range），
   页面与素材同源，视频/字体/图片都能直接加载。

## 目录结构

```
app/src/main/java/com/dshpet/pet/
  MainActivity.java   设置页（卡片式，自绘 Toggle，零第三方依赖）
  PetService.java     前台服务：渲染窗 + 触摸窗 + 手势转发 + 定时碎碎念 + AI 推送
  LocalServer.java    127.0.0.1 静态资源与配置服务（Range 支持）
  PetConfig.java      包内 config.json 读取与用户覆盖（大小/漫游/碎碎念）
  PetPrefs.java       设置存储
  AiClient.java       OpenAI 兼容 /chat/completions（碎碎念 / 对话 / 记忆）
  Canned.java         没配 AI 时的内置台词
  OemGuide.java       国产 ROM 自启动页跳转与手动路径
  Toggle.java         自绘开关
app/src/main/assets/pet/
  index.html constants.js sprite.js events.js renderer.js   ← 上游渲染层（MIT）
  shared-core.js                                            ← 上游 shared 的构建产物
  android-shim.js                                           ← 本项目的安卓适配层
tools/
  fetch_upstream.sh   克隆上游 + 生成素材
  build_assets.sh     素材与 config.json 一键生成
  pack_alpha.sh       VP9-alpha WebM → RGB+alpha 并排 H.264
```

## 授权

- **本项目代码**：MIT（见 [LICENSE](LICENSE)）。
- **上游代码**（`app/src/main/assets/pet/` 里的渲染层）：MIT，版权归 PC2005-cloud。
- **上游美术素材**（动画/表情包/提示词/源视频）：上游声明「允许开源使用，**禁止商用**」。
  本仓库不分发这些素材；由 `tools/fetch_upstream.sh` 在构建时获取。
  **如果你要把构建产物拿去销售，请先取得素材权利人的商业授权。**
- **二次创作约定**（上游要求）：基于该项目的衍生/改版/换皮作品，在**任何介绍、展示、分发的地方**
  都需要附上作者仓库地址 <https://github.com/PC2005-cloud/dsh-pet>。本项目的设置页「关于 →
  开源许可与致谢」和本 README 都已保留该署名。
- **字体**：仓库与构建流程都不包含商业字体；默认回退到系统字体。如需上游同款字体，请自行取得授权后放入
  `app/src/main/assets/pet/fonts/`。

## 致谢

- [PC2005-cloud/dsh-pet](https://github.com/PC2005-cloud/dsh-pet)：渲染器、动画素材、配置体系与素材生成管线，
  这个移植能成立几乎全靠它。
- 上游素材由 AI 视频工具生成后经水印→抠像→归一化→转码管线处理，管线脚本同样来自上游仓库。

## 免责声明

本项目与 DeepSeek / DeepSeek Harness 官方无关，也未获得其背书；"DSH" 仅用于说明兼容来源。
软件按 MIT 许可「按原样」提供，不附带任何担保。使用本软件产生的任何后果由使用者自行承担。

## 已知限制

- 余额查询、DSH 会话工作状态联动属于宿主能力，安卓移植版未实现。
- 悬浮窗可点区域 = 宠物身体 + 20dp；命中区外的点击一律交给下层应用（这是有意的）。
- 厂商 ROM 可能强杀后台；已提供自启动跳转与电池白名单引导，但不能绕过系统策略。
- 没有在全部机型上实测；遇到问题欢迎提 issue 并附上机型、系统版本与复现步骤。

## 参与

欢迎 PR / issue。改动渲染层时注意：`app/src/main/assets/pet/` 下的 5 个文件来自上游，
升级上游版本时请用上游最新文件覆盖，然后重新应用 `constants.js` 里的 `WINDOW_MARGIN_RATIO` 改动
（0.5 → 0.32，安卓窗口更小、少挡触摸）。
