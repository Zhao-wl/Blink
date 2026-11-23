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

### 签名与测试安装

iOS 真机打包支持三个 GitHub Actions Secrets：`IOS_P12_BASE64`（签名证书及私钥导出的 P12）、`IOS_P12_PASSWORD`、`IOS_PROFILE_BASE64`（与 com.zhaowl.blink 匹配的 mobileprovision）。配置后再次运行会额外生成 signed IPA。可安装的设备与分发范围由描述文件决定。证书和密码只应存入仓库 Secrets。

Android 默认 APK 使用运行器的测试签名。需要连续覆盖安装时请在仓库 Secrets 中配置固定的 `ANDROID_KEYSTORE_BASE64`、`ANDROID_KEYSTORE_PASSWORD`、`ANDROID_KEY_ALIAS`、`ANDROID_KEY_PASSWORD`；工作流将改用该密钥签名 release 包。不同签名之间无法覆盖安装。

### 模式、外观与后台倒计时

「今日」的工作／非工作页签显示当前生效的模式，点另一个页签立即切换，仍沿用原有自动上下班规则。「日程 → 外观」可选跟随系统、亮色或暗色。

启用系统通知后，Android 使用可见的前台计时服务持有本地提醒队列，退到桌面或划掉任务后可继续。服务使用系统计时通知展示下次提醒，用户可在通知设置中关闭锁屏显示。熄屏唤醒时间受厂商省电策略、精确闹钟权限和系统通知设置影响。系统强制停止应用后需重新打开。桌面关闭窗口后在托盘继续，选择退出会停止运行。

iOS 使用系统本地通知，iOS 16.2 及以上在活动时段使用实时活动展示当前倒计时。系统管理实时活动的展示、过期与结束。签名真机包另需为扩展 com.zhaowl.blink.countdown 配置对应的 IOS_WIDGET_PROFILE_BASE64。
