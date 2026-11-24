# Blink

本地运行的用眼与工休提醒应用。安卓、iOS 使用 Capacitor 和系统本地通知；Windows 11、macOS 使用 Electron、系统托盘与可直接打卡的提示窗。日程、修正与活动记录存储在当前设备。首次安装后可断网启动。

[一期规格](docs/phase-one.md) · [二期实施计划](docs/phase-two.md) · [三期产品规格](docs/phase-three.md) · [调研稿](docs/blink-design.html) · [自动打包与下载](https://github.com/Zhao-wl/Blink/actions/workflows/offline.yml)

## 二期安卓内测

安装 Releases 中的「Blink 内测」。在「日程 → 账号与同步」注册后，可以跨会话同步打卡、撤销、用眼区间、作息和当天安排，离线时继续在本机记录。从 0.2.7 开始，安卓内测包通过公网 HTTPS 连接成都 ECS；使用 Wi-Fi 或移动数据即可同步，断网后继续在本机记录。原有四端离线包继续独立发布。[安卓内测包](https://github.com/Zhao-wl/Blink/releases?q=android-stage) · [联调与恢复](server/README.md)

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

在「日程 → 通知设置」启用系统通知。移动端在作息或设置变化时预先登记提醒队列，最多 60 条、覆盖未来三天，回到应用后按当前作息重新排程。锁屏能否点亮、响铃、震动和准点显示受系统权限、省电与专注设置控制。桌面最小化时继续提醒，关闭窗口或从托盘退出时结束运行。非工作模式仅使用 Blink 可观察到的前台时长、当天用眼活动或定时用眼区间估算。

账号、跨设备同步与在线更新服务属于二期。

### 签名与测试安装

iOS 真机签名使用四个 GitHub Actions Secrets：`IOS_P12_BASE64`（签名证书及私钥导出的 P12）、`IOS_P12_PASSWORD`、`IOS_PROFILE_BASE64`（与 com.zhaowl.blink 匹配的 mobileprovision），以及扩展的 `IOS_WIDGET_PROFILE_BASE64`。配置后再次运行会额外生成 signed IPA。可安装的设备与分发范围由描述文件决定。证书和密码只应存入仓库 Secrets。

Android 默认 APK 使用运行器的测试签名。需要连续覆盖安装时请在仓库 Secrets 中配置固定的 `ANDROID_KEYSTORE_BASE64`、`ANDROID_KEYSTORE_PASSWORD`、`ANDROID_KEY_ALIAS`、`ANDROID_KEY_PASSWORD`；工作流将改用该密钥签名 release 包。不同签名之间无法覆盖安装。

### 模式、外观与后台倒计时

「今日」的工作／非工作页签显示当前生效的模式，点另一个页签立即切换，仍沿用原有自动上下班规则。「日程 → 外观」可选跟随系统、亮色或暗色。

Android 退到桌面或锁屏时继续计时和提醒。启用「锁屏显示倒计时」后，下一次提醒会以一条通知显示在原有系统锁屏上。从最近任务划掉 Blink 时，应用会撤销已排程提醒、移除倒计时并停止后台服务。再次打开后按当前作息排程。「日程 → 通知设置 → 允许锁屏准时提醒」可开启系统闹钟权限。「提醒时亮屏约 15 秒」需要主动勾选。iOS 划掉已挂起的应用时，系统可能不会通知 Blink，系统已接收的本地通知和实时活动可能仍会显示。

一加 ColorOS 上的提醒亮屏和锁屏卡片各有独立开关。在「设置 → 应用 → 特殊应用权限 → 开启屏幕」中允许 Blink 开启屏幕，再到 Blink 的「应用信息 → 通知管理」勾选「锁屏」。内测包在系统设置中显示为「Blink 内测」。可在 Blink 的「日程 → 通知设置」随时关闭提醒亮屏。


iOS 使用系统本地通知，iOS 16.2 及以上在活动时段使用实时活动展示当前倒计时。系统管理实时活动的展示、过期与结束。签名真机包另需为扩展 com.zhaowl.blink.countdown 配置对应的 IOS_WIDGET_PROFILE_BASE64。
