# Blink

本地运行的用眼与工休提醒应用。安卓、iOS 使用 Capacitor 和系统本地通知；Windows 11、macOS 使用 Electron、系统托盘与可直接打卡的提示窗。日程、修正与活动记录存储在当前设备。首次安装后可断网启动。

[一期规格](docs/phase-one.md) · [调研稿](docs/blink-design.html) · [自动打包与下载](https://github.com/Zhao-wl/Blink/actions/workflows/offline.yml)

## 安装与打包

在成功的 Actions 运行底部下载对应 Artifact 并解压。Android 包为可侧载测试 APK；iOS 模拟器包可通过 Xcode 安装。未签名设备 IPA 需要开发者签名和匹配的描述文件才能在真机运行。Windows 安装包为 x64，macOS DMG 包含 Apple Silicon 和 Intel。开发测试构建可能显示系统签名提示。

开发环境使用 Node 24 和 npm。执行 `npm ci` 安装锁定依赖，`npm test` 验证工休计时与本地通知排程。

| 目标 | 本机开发或生成平台工程 |
| --- | --- |
| Android | `npm run android`，使用 Android Studio 打开生成的 android 工程。命令行打包使用 JDK 21 和 Android SDK 36 |
| iOS | 在装有完整 Xcode 的 Mac 执行 `npm run ios`，打开 ios/App/App.xcodeproj。真机使用自己的 Apple Developer Team 签名 |
| Windows 11 / macOS | `npm start` 运行桌面应用；在目标系统执行 `npm run pack:desktop` 生成安装包 |

每次推送 main、推送版本标签或手动运行工作流都会编译并上传四端包。构建机联网下载工具链和依赖，设备使用离线应用无需服务端。

## 提醒与数据

在「日程 → 通知设置」启用系统通知。移动端切入后台时向本机系统登记一个有界队列，最多 60 条、覆盖未来三天，回到应用后按当前作息重新排程。锁屏能否点亮、响铃、震动和准点显示受系统权限、省电与专注设置控制。桌面关闭窗口后在托盘继续运行，完整退出后停止提醒。非工作模式仅使用 Blink 可观察到的前台时长、当天用眼活动或定时用眼区间估算。

账号、跨设备同步与在线更新服务属于二期。
