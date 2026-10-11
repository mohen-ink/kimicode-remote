# Kimi远程控制

Kimi Code 桌面端「手机远程连接」的 **Android 客户端**。扫码 / 从相册识别配对二维码，在应用内沉浸式打开控制面板，驱动电脑上的 Kimi Code Agent。

> 桌面端插件：[ziyiclouds-blip/kimicode-ylmb](https://github.com/ziyiclouds-blip/kimicode-ylmb)（Kimi Code 用量面板，内含手机远程连接功能）

## 功能

- **扫码**：相机实时识别二维码（ML Kit，本地识别，无需 Google 服务）
- **从相册选择**：识别相册图片中的二维码
- **使用上一次链接**：一键回到最近一次连接
- **历史连接**：最近 50 条记录，点击即连
- **会话保持**：每条历史记录快照当时的站点 Cookie（含会话 Cookie），下次打开自动恢复，免重复配对
- **沉浸式浏览**：应用内 WebView 全屏打开面板，支持网页文件上传与拍照上传
- 非 http(s) 的二维码内容识别后可查看与复制

## 使用前准备

1. **电脑端安装 Kimi Code 桌面端**及 [用量面板插件](https://github.com/ziyiclouds-blip/kimicode-ylmb)
2. 在插件侧栏打开「手机」浮层，选择连接模式（内网 / CF 隧道 / 中继服务器）并开启
3. 浮层会显示配对二维码
4. 手机端：打开本 App → 扫码（或从相册选二维码截图）→ 自动在应用内打开控制面板

安全提醒：**配对 = 控制授权**。手机可驱动电脑 Agent（含读写文件、执行命令），请勿分享二维码或链接给他人。

## 下载

前往 [Releases](https://github.com/mohen-ink/kimicode-remote/releases) 下载最新 `KimiRemote-v*.apk`。

- 仅支持 **arm64-v8a**（2016 年后绝大多数 Android 手机）
- Android 8.0（API 26）及以上
- 约合 8 MB

## 权限说明

| 权限 | 用途 |
|---|---|
| 相机 | 扫描二维码；网页请求拍照上传时调用 |
| 网络 | 加载远程控制面板 |
| 相册 | 经系统 Photo Picker 选择二维码图片，**无需存储权限** |

## 构建与发布

```bash
./gradlew assembleRelease   # 输出 app/build/outputs/apk/release/
```

- Release 构建启用 R8 + 资源收缩，仅 arm64-v8a
- 签名配置读取仓库根目录 `keystore.properties`（不入库），格式：

  ```properties
  storeFile=../keystore/kimiremote.jks
  storePassword=...
  keyAlias=...
  keyPassword=...
  ```

- CI：推送 `v*` 格式的 tag 自动构建并发布 GitHub Release，产物为已签名 APK

## 更新日志

见 [CHANGELOG.md](CHANGELOG.md)。

## License

MIT
