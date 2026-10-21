这个目录里的 webm/ memes/ pic/ fonts/ config.json 是构建时从上游 dsh-pet 仓库拉取并转码生成的，
不随仓库分发（见 tools/fetch_upstream.sh 与 tools/build_assets.sh）。

仓库只包含渲染层 JS：index.html / constants.js / sprite.js / events.js / renderer.js
（来自上游 dsh-pet，MIT）与 android-shim.js（本项目新增）。
