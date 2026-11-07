# 红魔 VTuber（GeckoView 壳）

用一个自带现代引擎的 APK 替代 360 浏览器，直接全屏加载手机内 AidLux 里的 OLVT 前端：

- 目标地址：`http://127.0.0.1:12393/index.html?v=20261107`
- 引擎：GeckoView `159.0.20261102144711`（arm64-v8a）
- minSdk 28 / targetSdk 34，包名 `com.trae.olvt.shell`
- 启动时探测 `127.0.0.1:12393`，不通则显示提示页（可直接打开 AidLux / 重试）
- 麦克风：运行时申请 `RECORD_AUDIO`，并由 `PermissionDelegate` 授权给页面

## 构建

仓库已带 GitHub Actions（`.github/workflows/build.yml`），push 到 `main` 即产出
`app-debug.apk`（Actions → Artifacts 下载）。

本地构建（需 JDK 17 + Android SDK）：

```bash
gradle :app:assembleDebug
```

## 安装与使用

```bash
adb install -r app-debug.apk
```

1. 先在 AidLux 里启动 OLVT：
   `cd /home/aidlux/olvt && (setsid ./start_olvt.sh >> server.log 2>&1 < /dev/null &)`
2. 点桌面「红魔 VTuber」图标即可。

> 注意：debug 签名，重新构建后如需覆盖安装且签名变化，先卸载旧的。
