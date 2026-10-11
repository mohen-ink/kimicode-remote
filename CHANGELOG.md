# 更新日志

所有版本的变更记录在此文件中。格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)。

## [1.3.1] - 2026-10-11

### 修复

- WebView 文件选择器选完文件网页收不到：自建 `ACTION_GET_CONTENT` 请求（兼容机型差异），回传前兜底从 ClipData 提取并申请读权限

## [1.3] - 2026-10-11

### 新增

- WebView 网页文件上传：支持 `<input type="file">` 系统选择器与拍照上传（capture）
- 首页「使用上一次链接」按钮，一键回到最近一次连接（含 Cookie 恢复）

## [1.2] - 2026-10-11

### 新增

- 历史连接记录保留站点 Cookie 快照（含会话 Cookie），再次打开自动恢复登录态

## [1.1] - 2026-10-11

### 修复

- release 混淆破坏 ML Kit 导致扫码闪退、相册识别失败：补充 keep 规则
- 允许 http 明文流量（远程面板常为局域网 http 链接）

### 变更

- release 构建启用 R8 + 资源收缩，仅保留 arm64-v8a，体积 43MB → 8MB
- APK 重命名为 `KimiRemote-v{version}.apk`

## [1.0] - 2026-10-11

### 新增

- 相机实时扫码（CameraX + ML Kit）
- 从相册选择二维码图片识别（系统 Photo Picker，免存储权限）
- 应用内 WebView 沉浸式打开链接，返回键优先网页后退
- 历史连接记录（最近 50 条，点击重进、支持清空）
- 首页：扫码 / 从相册选择 / 历史连接
