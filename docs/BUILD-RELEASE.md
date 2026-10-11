# 构建与发布指南

本文档说明如何本地构建 release 包，以及如何通过 GitHub Action 发布正式版本。

## 前置条件

- JDK 17（本地可用 Android Studio 自带 JBR：`Android Studio/jbr`）
- Android SDK（API 37 编译，`local.properties` 指向 SDK 目录）
- 签名密钥：`keystore/kimiremote.jks`（不入库，需本地备份）
- 签名配置：仓库根目录 `keystore.properties`（不入库），格式：

  ```properties
  storeFile=../keystore/kimiremote.jks
  storePassword=...
  keyAlias=...
  keyPassword=...
  ```

## 版本号规则

- **versionCode**：每次发版 +1
- **versionName**：语义化 `主.次.修订`
  - 大功能 / 界面改版 → 升次版本（如 1.3 → 1.4）
  - 小修小补 / bug 修复 → 只升修订位（如 1.3.1 → 1.3.2）
- 发版时需同步修改三处：
  1. `app/build.gradle.kts` 的 `versionCode` / `versionName`
  2. `app/build.gradle.kts` 中 `androidComponents` 里的 APK 重命名文件名
  3. `CHANGELOG.md` 顶部新增版本段落

## 本地构建

```bash
# Windows（Git Bash），JAVA_HOME 未设置时显式指定
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew assembleRelease

# 产物（已签名）
app/build/outputs/apk/release/KimiRemote-v{version}.apk
```

注意：release 构建启用了 R8 与资源收缩，ML Kit / CameraX 已在 `proguard-rules.pro` 中 keep，**不要删除这些规则**，否则扫码会闪退（debug 包不混淆，无法复现此类问题）。

## 发布正式版（GitHub Action）

推送 `v*` 格式的 tag 自动触发 [release.yml](.github/workflows/release.yml)：

```bash
git tag v1.4
git push origin main --tags
```

Action 流程：

1. JDK 17 + Gradle 环境准备
2. 从 **GitHub Secrets** 还原签名密钥与配置（`KEYSTORE_BASE64`、`STORE_PASSWORD`、`KEY_ALIAS`、`KEY_PASSWORD`）
3. `assembleRelease` 构建
4. 产物重命名为 `KimiRemote-v{version}.apk`（版本号取自 tag，去掉 `v` 前缀）
5. 从 `CHANGELOG.md` 提取对应版本段落作为 Release 描述
6. 创建 GitHub Release 并上传 APK

验证结果：

```bash
gh run list -R mohen-ink/kimicode-remote
gh release view v1.4 -R mohen-ink/kimicode-remote
```

发布失败重跑 tag：删除远端 tag 后重新推送即可。

```bash
git push origin :refs/tags/v1.4
git tag -f v1.4 && git push origin v1.4
```

## CI 构建要求速查

| 项目 | 值 |
|---|---|
| 触发方式 | 推送 `v*` tag |
| 运行环境 | ubuntu-latest |
| JDK | 17（Temurin） |
| ABI | 仅 arm64-v8a |
| 混淆 | R8 + shrinkResources |
| 签名 | Secrets 注入的 keystore |
| 产物命名 | `KimiRemote-v{version}.apk` |
| Release 描述 | CHANGELOG.md 对应版本段落 |
