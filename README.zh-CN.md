# AScrcpy

[English](README.md) | [简体中文](README.zh-CN.md)

AScrcpy 是一个使用 Jetpack Compose 构建的 Android 设备间屏幕镜像与远程控制应用。

模块边界和协议流程请参阅[架构文档](docs/architecture.md)，UI 强制性约束请参阅
[视觉设计规范](docs/design-system.md)，仓库开发规则请参阅 [AGENTS.md](AGENTS.md)。

## 模块

- `app`：Compose 控制端 UI 和应用生命周期。
- `adb`：可复用的 ADB Host 库，其公开 API 不暴露 TCP 实现。
- `scrcpy`：scrcpy 4.0 服务端生命周期、串流协议、MediaCodec 解码和控制消息。

所有项目自有包均以 `ernest.ascrcpy` 开头。ADB 模块命名空间为
`ernest.ascrcpy.adb`。

## 构建

```shell
./gradlew :adb:testDebugUnitTest :scrcpy:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug
```

使用 Android CLI 安装：

```shell
android run --device=<serial> --activity=ernest.ascrcpy.MainActivity \
  --apks=app/build/outputs/apk/debug/app-debug.apk
```

## 使用 ADB 模块

添加模块依赖：

```kotlin
dependencies {
    implementation(project(":adb"))
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

`AdbClient`、`AdbChannel`、`AdbKeyProvider`、`AdbTransport` 及其工厂均为接口。
未来的 USB 实现可提供另一个 `AdbTransportFactory`；第三方 ADB 库也可直接实现
`AdbClient`。两种方案都不需要修改 app 或 scrcpy 模块。

当前内置实现会直接连接已启用的 TCP adbd。Android 11+ 无线调试配对和 USB 传输仅为扩展点，
尚未实现。

## 当前功能

- 直连 TCP ADB，支持持久化 RSA 身份和设备授权。
- Shell、sync push 和多路复用的任意 ADB service。
- 内置匹配的 scrcpy-server 4.0。
- 通过 MediaCodec 将 H.264 低延迟解码到 `SurfaceView`。
- 单指和多指控制消息转发。
- 连接后自动进入沉浸式预览，悬浮控件适配屏幕挖孔/刘海区域。
- 可拖动、靠边吸附的 Mini 遥控器，支持方向、确定、返回、Home、菜单、音量和电源键。
- 持久化连接主机历史，支持选择和一键删除。
- 中英文界面，支持 Android 13 应用独立语言选择。
- 会话状态、错误报告、旋转/会话尺寸变化处理和完整关闭。

音频、剪贴板同步、Android 11 无线配对、发现和 USB 传输属于计划中的扩展。

## 测试设备

已在连接的 `KONKA Android TV KKAML966D5` Android 14 设备上验证：

- 应用安装和 Compose 启动；
- 直接 ADB 连接与授权；
- Shell 执行和服务端上传；
- scrcpy-server 启动；
- `1920 × 1072` 下的 H.264 编解码链路；
- 视频和控制通道建立。

## 第三方软件

`app/src/main/assets/scrcpy-server-v4.0` 中的二进制文件是未修改的 scrcpy 4.0 服务端。
详见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
