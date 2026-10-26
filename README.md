# 地球Online · Android

![Platform](https://img.shields.io/badge/platform-Android-3DDC84) ![Version](https://img.shields.io/badge/version-v2.0.0-blue) ![License](https://img.shields.io/badge/license-MIT-green)

把人生当成一场开放世界游戏的纯原生 Android 端（不依赖 WebView）。

## 当前状态

活跃开发（v2.0.0，封板日期 2026-10-26）。

## 技术栈

| 类别 | 选型 |
| --- | --- |
| 语言 | Kotlin 2.0.21 |
| UI | Jetpack Compose + Material 3 |
| 视觉 | Haze 液态玻璃（离屏缓存，不可降级）、Coil 3 图片、Vico 图表、Compose-Rich-Editor 笔记编辑器 |
| 架构 | MVVM + Repository（ViewModel + StateFlow） |
| 导航 | Navigation Compose |
| 本地存储 | DataStore（键值）/ Room（结构化，数据库 v10） |
| 网络 | Retrofit + OkHttp（WebDAV 同步 / AI 对话） |
| 依赖注入 | Hilt |
| 异步 | Coroutines + Flow |
| 地图 | 高德 Android SDK |
| 范围 | minSdk 24，compileSdk / targetSdk 35 |
| 打包 | APK + AAB |

## 性能红线

Haze 液态玻璃特效是 Android 端的不可降级视觉基线：

- Haze 特效严禁降级。任何卡顿问题都必须通过离屏缓存（Offscreen / 内容根唯一合成层）与模糊输入降采样解决。
- 禁止以"关闭模糊""降低模糊度""改纯色描边"作为修复手段。降级模糊等于破坏视觉基线，不被接受。
- 卡顿排查顺序：先确认离屏缓存与降采样是否就位，再定位具体重绘热点，绝不以关闭特效掩盖问题。

## 跨端契约

四端共用同一份数据契约 `earth-online-backup.json`：字段全 camelCase、WebDAV 按主键合并、软删除对齐。完整契约见 Web 仓库 [docs/contract.md](https://github.com/IllNFree/EarthOnline-Web/blob/main/docs/contract.md)。

## 快速开始

```bash
./gradlew assembleDebug
```

连上 Android 设备（USB 调试）或启动 API 24+ 模拟器，选择 `app` 配置点击 Run。

## 目录结构（v2.0.0）

```
app/src/main/java/com/example/earthonline/
├── EarthOnlineApp.kt            # @HiltAndroidApp
├── MainActivity.kt              # 起屏 + setContent(Root)
├── data/
│   ├── model/                   # 实体与 DTO
│   ├── local/                   # entity / Converters / dao / AppDatabase / datastore
│   └── repository/              # Repositories（含 BackupRepository）
├── di/AppModules.kt             # Hilt 模块
├── ui/
│   ├── theme/                   # Color / Theme / Type
│   ├── navigation/              # Screen / BottomNav / AppNavHost
│   ├── Root/Root.kt             # 引导页 / 主框架分流 + 主题
│   ├── MainScaffold.kt          # 底部导航 + 更多抽屉
│   ├── home/                    # 主页（速览 / 日历 / 时间轴）
│   ├── profile/                 # 个人档案
│   ├── tasks/ backpack/ achievements/ data/ map/ notes/ ai/ settings/
│   └── onboarding/OnboardingScreen.kt
└── util/                        # DateUtils 等
```

## 仓库导航

- Web（PWA）：https://github.com/IllNFree/EarthOnline-Web
- Desktop（Windows）：https://github.com/IllNFree/EarthOnline-Desktop
- iOS：https://github.com/IllNFree/EarthOnline-iOS

## 许可证

[MIT](LICENSE)

---

## 签名与打包

`app/build.gradle.kts` 已内置签名逻辑：读取项目根目录 `local.properties` 的 `EO_STORE_*` 字段；若未配置则回退默认签名（可正常打包，仅无法上架分发）。

1. 生成 keystore（仅首次）：

```bash
keytool -genkeypair -v -keystore earth-online-release.jks \
  -keyalg RSA -keysize 2048 -validity 10000 -alias earthonline
```

2. 在 `local.properties`（已 gitignore，勿提交）写入：

```properties
EO_STORE_FILE=../earth-online-release.jks
EO_STORE_PASSWORD=你的store密码
EO_KEY_ALIAS=earthonline
EO_KEY_PASSWORD=你的key密码
```

3. 打包：

```bash
./gradlew :app:assembleRelease     # 产物 app/build/outputs/apk/release/app-release.apk
./gradlew :app:bundleRelease       # 产物 app/build/outputs/bundle/release/app-release.aab
```

> 安装 v2.0.0 前需先卸载旧版（debug 包）：正式签名与旧 debug 签名不兼容。
