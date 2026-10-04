# AScrcpy

[![License: Apache-2.0](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.3-purple.svg)](https://kotlinlang.org)
[![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-2026.03-4285F4.svg)](https://developer.android.com/jetpack/compose)

[English](README.md) | [简体中文](README.zh-CN.md)

用一台 Android 设备镜像并控制另一台 Android 设备。AScrcpy 把完整的 scrcpy 控制端装进 Android
手机或平板：不需要电脑，不需要 root，目标设备上也不会留下任何安装。

它直连目标设备的 `adbd`，推送匹配的 scrcpy server，用 `MediaCodec` 解码 H.264 码流，再把触摸和
按键转换成 scrcpy 控制消息。整个控制端界面基于 Jetpack Compose 构建。

---

## 致谢 —— 本项目站在 scrcpy 之上

**AScrcpy 之所以存在，是因为有 [scrcpy](https://github.com/Genymobile/scrcpy)。**

Romain Vimont（`@rom1v`）与 scrcpy 的所有贡献者设计并实现了这套协议、设备端 server，以及
"不向设备安装任何东西就能镜像 Android" 这个想法本身。AScrcpy 直接复用了他们的成果：

- `app/src/main/assets/scrcpy-server-v4.0` 是**未经修改的 scrcpy 4.0 server**，与官方发布的产物
  逐字节一致；
- 控制端使用 **scrcpy wire protocol**，客户端与 server 锁定为同一版本。

其余部分——ADB Host 实现、串流与控制协议处理、`MediaCodec` 解码、以及全部 Compose 界面——都是
本项目独立实现的，没有包含任何 scrcpy 客户端源码。

scrcpy 本身以 Apache License 2.0 发布，因此 AScrcpy 采用同样的许可证。具体版本、校验和、许可证
全文与对应源码见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

如果 scrcpy 对你有帮助，欢迎在上游支持它——AScrcpy 是它的下游。

---

## 为什么做 Android 控制 Android

scrcpy 很好，但它假设另一端是一台电脑。而当你想控制的设备是电视盒子、机顶盒、基于 Android 的
一体机，或者别人闲置的手机时，电脑往往恰恰是不存在的那一环。

AScrcpy 去掉了这个前提：

|  | scrcpy | AScrcpy |
|---|---|---|
| 控制端运行在 | Linux、Windows、macOS | Android 手机或平板 |
| 准备方式 | 在电脑上安装 | 在手机上安装 |
| 连接方式 | USB 或 TCP/IP | TCP ADB、Tailcat、Android 11+ 无线调试、USB 主机 |
| UI 技术栈 | SDL / 原生 | Jetpack Compose、Material 3、WeUI 视觉语言 |
| 遥控导航 | 电脑键盘与鼠标 | 屏幕悬浮方向键、返回、Home、菜单、音量、电源 |
| 音频、剪贴板、HID、录制 | 支持 | 尚未实现 |

同一个网络里的两台设备就够了。用手机控制没有键盘、没有应用商店的 Android 电视，也是最自然的
使用方式。

## 功能

- 支持 TCP ADB、Tailcat 远程连接、Android 11+ 无线调试配对码或二维码，以及 USB 主机连接，使用持久化 RSA 身份。
- Shell 执行、sync push，以及多路复用的任意 ADB service。
- 内置匹配的 scrcpy-server 4.0，随会话启动和停止。
- 通过 `MediaCodec` 将 H.264 低延迟解码到 `SurfaceView`。
- 单指与多指触摸转发，带压力值和正确的坐标映射。
- 连接后自动进入沉浸式预览，适配挖孔与刘海区域。
- 可拖动的悬浮遥控器，松手靠边吸附；拖离边缘即展开成完整面板。始终只有一套布局，按可用空间
  等比缩放，横屏下依然可用。
- 长按停靠图标可在全屏预览与主界面之间切换。
- 点击展开后遥控器左上角按钮，也可切换镜像全屏状态。
- 连接后可从首页进入设备管理，执行 Shell 命令、查看应用列表、浏览远程文件；可将文件和目录下载到选定的本地目录，删除远程文件前需要确认。
- 应用列表支持清空应用数据，以及在确认后为设备当前用户卸载应用。
- 应用列表保留包名展示，并支持为设备当前用户停用或重新启用应用。
- 持久化、可筛选的连接主机历史，支持一键删除。
- 中英文界面，支持 Android 13 应用独立语言选择。
- 会话状态、错误报告、旋转与 Surface 重建处理，以及完整关闭流程。

## 界面截图
主界面可通过 TCP ADB、无线调试和 USB 主机连接，并逐步汇报会话状态。连接成功后预览自动全屏显示，悬浮遥控器用于发送

![AScrcpy 主界面，包含 ADB 设备卡片](screenshots/main.jpg)

导航按键：停靠在边缘时是一个小图标，拖离边缘则展开为下图所示的完整面板。

![被控 Android 电视主界面，悬浮遥控器展开为完整面板](screenshots/mirror.jpg)


## 环境要求

- **控制端**为 Android 8.0（API 26）及以上。
- TCP 模式要求目标已开启 ADB 网络端口，常见做法是 `adb tcpip 5555`。
- Tailcat 模式要求目标已开启 TCP ADB，并已通过 `tailcat serve 5555` 共享该端口。选择 Tailcat，输入服务端显示的 Tailcat 地址和远端 ADB 端口（默认 5555）。地址相当于连接凭据，应只发给授权的控制端。应用内置 ARM64/ARMv7/x86_64 Tailcat 客户端，连接断开时会停止隧道；其他 CPU 架构暂不支持。
- 无线调试要求目标运行 Android 11+，两台设备处于同一可达的 Wi-Fi 网络。配对码模式先使用临时配对端口配对，再输入独立连接端口；二维码模式由本应用展示二维码，目标设备扫码后自动发现并连接。
- USB 模式要求控制端支持 USB 主机、目标开启 USB 调试，并批准 USB 访问和 RSA 授权。
- legacy TCP ADB 是明文传输，请只在可信网络使用。

## 构建

```shell
./gradlew :scrcpy:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug
```

需要 JDK 17。使用 Android CLI 安装到已连接设备：

```shell
android run --device=<serial> --activity=ernest.ascrcpy.MainActivity \
  --apks=app/build/outputs/apk/debug/app-debug.apk
```

在已连接设备上运行 instrumentation 测试：

```shell
./gradlew :app:connectedDebugAndroidTest
```

## 使用

1. 把 AScrcpy 安装到控制端手机上。
2. 选择 TCP、Tailcat、无线配对码、无线二维码或 USB 主机。配对码模式输入临时配对端点和配对码后，AScrcpy 会发现独立连接端点并自动连接；应用按 GUID 保存多个已配对设备及其上报的型号名称和路由地址。下次启动时从已配对设备列表选择目标，再点击“查找并连接已保存设备”；仅打开 App 不会自动重连。明确输入 Tailscale 地址时保留该地址以维持 Tailscale 路径，普通 Wi-Fi 配对则使用匹配 NSD 服务解析出的地址。若 mDNS 发现或自动连接失败，再检查或手动输入连接端口并点击“连接”；可通过“配对其他设备”新增设备。二维码模式由目标设备扫描本应用展示的码；USB 模式请批准权限弹窗。
3. TCP 模式输入目标地址和端口；Tailcat 模式输入共享地址和远端 ADB 端口。点击 **连接**。应用会记住 TCP 主机，但不保存 Tailcat 地址。
4. 点击 **Test shell** 确认连接，再点击 **Start mirroring**，预览会自动全屏打开。
5. 直接在预览画面上进行触摸操作，用悬浮遥控器发送导航键。遥控器可以拖到任意位置；松手时若靠近
   左右边缘，则收成小图标停靠。

## 模块

| 模块 | 内容 |
|---|---|
| `app` | Compose 控制端界面、Android 生命周期、assets、依赖装配 |
| `adb` | 可复用的 ADB Host 库；其公开 API 不会暴露 TCP 实现 |
| `scrcpy` | scrcpy server 生命周期、串流协议、`MediaCodec` 解码、控制消息 |

允许的依赖方向为 `app -> scrcpy -> adb`，此外 `app -> adb` 用于直连和诊断。所有项目自有包均以
`ernest.ascrcpy` 开头，ADB 命名空间为 `ernest.ascrcpy.adb`。

延伸阅读：[docs/architecture.md](docs/architecture.md) 说明模块边界与协议流程，
[docs/design-system.md](docs/design-system.md) 规定 UI 强制性约束，[AGENTS.md](AGENTS.md) 说明仓库
开发规则。

## 使用 ADB 库

[独立 `adb` 库](https://github.com/Ernest-su/adb) 可用于工具、诊断或其他 Android 应用。先在 `dependencyResolutionManagement.repositories` 中加入 `maven { url = uri("https://jitpack.io") }`。

```kotlin
dependencies {
    implementation("com.github.Ernest-su:adb:v0.2.1")
}
```

仅使用稳定的公开外观：

```kotlin
val client = DefaultAdbClient.factory(context).create()
val device = client.connect(AdbEndpoint("192.168.1.20", 5555))
val result = client.shell("getprop ro.product.model")
client.push(inputStream, "/data/local/tmp/tool.jar")
val channel = client.open("localabstract:my_service")
```

ADB 库通过 `AdbClient` 提供 USB 主机和无线 TLS 实现；第三方实现仍可替换该公开外观。

## 路线图

以下功能尚未实现，且都已按现有模块边界设计：

- 扩展无线发现和设备选择。
- 支持选择多个 USB 设备并处理插拔。
- 音频转发与 `AudioTrack` 播放。
- 剪贴板与设备消息接收循环。
- 键盘、手柄和更丰富的控制消息。
- 前台服务，让会话在界面重建后继续存活。

## 参与贡献

欢迎提交 issue 和 pull request，尤其是上面列出的扩展点——每一项都限定在单个模块内，且位于既有
接口之后。

提交 pull request 前请先运行：

```shell
./gradlew :scrcpy:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug
./gradlew :app:connectedDebugAndroidTest    # 需要连接设备
```

请先阅读 [AGENTS.md](AGENTS.md)：其中记录了本仓库要求的命名约定、模块边界、协议规则和变更纪律。

维护者请参阅 [docs/releasing.md](docs/releasing.md) 发版手册。推送 `v1.2.3` 形式的 tag 后，
GitHub Actions 会自动构建已签名 APK 并发布为 GitHub Release。

## 已验证设备

- **控制端**：OnePlus `PJE110`，Android 16 —— 安装、启动，以及包含主机输入框与悬浮遥控器行为的
  Compose instrumentation 测试。
- **目标设备**：`KONKA Android TV KKAML966D5`，Android 14 —— ADB 连接与授权、Shell、服务端上传
  与启动，以及 `1920 × 1072` 下的 H.264 编解码链路。

用同一台设备既当控制端又当目标端时，即使编解码链路完全正常，也可能得到黑屏或递归画面。因此验证
画面内容和触摸坐标时请尽量使用两台设备。

## 许可证

Copyright 2026 Ernest-su and AScrcpy contributors.

基于 Apache License, Version 2.0 发布。许可证全文见 [LICENSE](LICENSE)，内置 scrcpy server 的
相关信息见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

### Tailcat 无线配对

在 Android 11+ 目标设备开启开发者选项与无线调试，打开“使用配对码配对设备”并保持窗口打开。用同一个监听器共享当前两个远端端口：

```shell
tailcat --key=new serve 配对端口 连接端口
```

控制端选择 **Tailcat 无线配对**，输入打印的 Tailcat 地址、目标的配对端口、独立的 TLS 连接端口及当前六位配对码，点击 **配对并连接**。应用将两个端口映射到随机回环端口，使用持久化 ADB 身份配对、通过 TLS 连接并自动投屏。输入的是目标端口，不是电脑转发器的本地端口；无需 mDNS，隧道也不转发 mDNS。

配对成功后清空配对码。目标仍信任本应用身份时，可输入当前地址和连接端口，点击 **连接已配对设备**，仅转发连接端口，无需新配对码。地址与配对码不保存、不写日志。Wi-Fi 或无线调试重启可能改变端口，Tailcat 重启可能改变地址，应重新获取当前值。断开会停止隧道，不会关闭目标无线调试或撤销授权；撤销须在目标设置中忘记控制端。

APK 内置 ARM64、ARMv7 和 x86_64 Tailcat 0.7.0。Android 8/9 控制端可能因上游 Android DNS 协议不兼容导致 Tailcat 引导失败，需兼容的 Tailcat 构建或较新系统。目标设置必须提前启用并授权，双方 Tailcat 网络必须可用；本模式不代替目标设置操作。未实现前台服务，不保证长时间后台会话；模拟器验证不代表真机兼容性。

可选的 `TailcatWirelessDeviceTest` 使用真实目标验证配对、TLS 连接、shell、投屏、HOME 控制、服务端退出和无配对码重连。普通 Gradle 设备测试在未提供现场输入时跳过此用例。构建并安装两个 debug APK 后，使用 `adb shell run-as ernest.ascrcpy` 将包含 `address`、`pairing_port`、`connect_port`、`pairing_code` 的私有 JSON 写入控制端应用的 `files/tailcat-device-check.json`，再执行：

```shell
adb -s 控制端序列号 shell am instrument -w -r \
  -e class ernest.ascrcpy.ui.main.TailcatWirelessDeviceTest \
  ernest.ascrcpy.test/androidx.test.runner.AndroidJUnitRunner
```

测试读取后删除输入文件。应在安装后写入，因为 Gradle 设备测试可能清理应用数据。使用第二台设备作为目标，保持当前配对窗口打开，不将凭据放入命令参数、版本管理文件或测试日志。控制端 `TailcatSession` 日志记录简短 SHA-256 路由指纹及远端/本地端口；`TailcatDeviceTest` 记录验证阶段与视频尺寸。
