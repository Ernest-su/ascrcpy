# AScrcpy 项目架构

## 1. 目标与范围

AScrcpy 让一台 Android 设备作为控制端，通过 ADB 连接另一台 Android 设备，在目标端以
`shell` 身份启动 scrcpy-server，将目标屏幕编码流传回控制端，并把控制端触摸事件注入目标端。

当前版本聚焦于最小可用链路：

- 已启用的 TCP ADB（默认端口 5555）、Tailcat 端口共享、Android 11+ 无线调试配对与 TLS 连接、USB Host ADB；
- scrcpy-server 4.0；
- H.264 视频，不启用音频；
- `MediaCodec` 硬件解码到 `SurfaceView`；
- 单点、多点触摸和按键控制消息；
- Compose 连接、诊断、自动启动预览、沉浸式全屏及错误状态界面。

Android 11+ 无线调试配对码、二维码配对与 NSD 服务发现，以及 USB Host ADB 已接入；音频和剪贴板同步尚未实现。

## 2. 模块与依赖

```text
┌──────────────────────────────────────────────────────────────┐
│ app · ernest.ascrcpy                                        │
│ Compose UI / Activity / ViewModel / SurfaceView / server资产 │
└──────────────────────┬───────────────────────┬───────────────┘
                       │                       │
                       ▼                       ▼
┌──────────────────────────────────┐  ┌─────────────────────────┐
│ scrcpy · ernest.ascrcpy.scrcpy   │  │ adb public facade       │
│ server生命周期、流协议、解码、控制 │  │ 连接、shell、push、通道   │
└──────────────────┬───────────────┘  └─────────────────────────┘
                   │                              ▲
                   └──────────────────────────────┘
                              仅依赖公共接口
```

Gradle 依赖方向为：

```text
app ──► scrcpy ──► adb
 └────────────────► adb
```

反向依赖不允许出现。特别是 `adb` 不得感知 scrcpy、Compose 或具体产品 UI。

### `:app`

主要职责：

- `MainActivity`：Activity 与 Compose 根节点；
- `MainViewModel`：组合 ADB 与 scrcpy 状态，持有会话生命周期；
- `MainScreen`：目标地址输入、连接诊断、视频 Surface、全屏/刘海区适配、触摸坐标映射和悬浮遥控器；
- `assets/scrcpy-server-v4.0`：与客户端协议严格匹配的服务端二进制。

`app` 是组合根，只负责调用库接口，不应实现 ADB framing 或 scrcpy 二进制协议。

### 独立 `adb` 库

这是由 [独立仓库](https://github.com/Ernest-su/adb) 通过 JitPack 发布的 Android Library；本仓库固定依赖 `v0.2.1`，不再包含其源码模块。公共抽象包括：

| 接口/模型 | 职责 |
|---|---|
| `AdbClient` | 连接、断开、shell、push、打开任意 adbd service |
| `AdbChannel` | ADB 逻辑流的全双工读写 |
| `AdbClientFactory` | 隔离具体 Client 构造方式 |
| `AdbKeyProvider` | ADB RSA 身份签名及公钥编码 |
| `AdbTransport` | 与 TCP、USB 等无关的原始字节传输 |
| `AdbTransportFactory` | 注入具体底层 transport |
| `AdbConnectionState` | 连接、授权、成功和失败状态 |

默认实现分层：

```text
DefaultAdbClient
├── protocol/AdbPacket        ADB packet 编解码、校验与命令常量
├── crypto/FileAdbKeyProvider 2048-bit RSA 身份及持久化
├── transport/TcpAdbTransport Socket TCP transport
├── transport/TlsAdbTransport 无线调试 TLS transport
└── transport/UsbAdbTransport Android USB Host transport
```

`DefaultAdbClient` 在一条 transport 上维护后台 reader，并依据 local id 将 `OKAY`、`WRTE`、
`CLSE` 分发给多个 `AdbChannel`，因此 scrcpy 的 video/control 与 server shell 可以并行存在。

USB Host 使用库提供的 `UsbAdbTransport`，App 负责枚举设备与申请 Android USB 权限。无线调试使用配对端口建立信任，再通过独立连接端口进入 TLS ADB 会话。二维码由 App 生成，并通过 Android NSD 发现目标配对和连接服务。配对码成功后同样按配对返回的 GUID 匹配 `_adb-tls-connect._tcp` 服务并自动连接；明确输入 Tailscale 地址时连接主机保留该地址，普通 Wi-Fi 配对则使用匹配服务解析出的地址，连接端口始终取自 NSD。发现或自动连接失败时回退到手动连接端口输入。`adb` v0.2.1 的无线 TLS 证书与 AOSP 字段保持一致，并允许调用方把 TCP、配对和 TLS socket 绑定到指定 Android `Network`。若整体替换成第三方
ADB 库，则实现新的 `AdbClient`，上层 `scrcpy` 和 `app` 无需修改。

App 按 GUID 保存多个无线设备的目标端上报名称、配对/路由地址和最近发现端口。ADB 私钥仍由库保存在
`noBackupFilesDir`；设备记录只用于列表展示与重新发现，不替代 TLS 身份。下次启动只加载列表，不发起连接；
用户选择设备并点击“查找并连接已保存设备”后才按 GUID 查找当前 `_adb-tls-connect._tcp` 服务并连接。
最近端口仅用于发现失败后的手动回退，因为目标端可随时更换端口。“配对其他设备”保留现有列表并打开新配对表单。

Tailcat 连接由 `app` 的 `TailcatForwarder` 管理官方 Tailcat 进程。它将被控端共享的 ADB 端口映射到控制端随机分配的 `127.0.0.1` 端口，然后把本地端口交给现有 `AdbClient.connect`。断开连接或销毁 ViewModel 时停止进程；ADB framing 和 scrcpy 协议仍由原有模块负责。当前只支持目标端已共享的 TCP ADB 端口，不自动启用目标端 adbd，也不通过 Tailcat 完成无线调试配对。

### `:scrcpy`

主要组成：

| 类型 | 职责 |
|---|---|
| `ScrcpyClient` | 上传/启动 server、打开通道、管理协程与关闭顺序 |
| `VideoStreamDecoder` | 解析 codec/session/frame 元数据并驱动 `MediaCodec` |
| `ControlMessageWriter` | 序列化触摸和按键控制消息 |
| `ScrcpyConfig` | 分辨率、码率、最大帧率配置 |
| `ScrcpyState` | 安装、启动、连接、播放、失败状态 |

该模块只通过 `AdbClient` 与设备通信，不依赖默认 TCP 实现。

## 3. ADB 连接架构

### 3.1 认证握手

```text
Controller App                           target adbd
     │                                       │
     │ CNXN(host::features=...)              │
     ├──────────────────────────────────────►│
     │ AUTH(token)                           │
     │◄──────────────────────────────────────┤
     │ AUTH(signature) 或 AUTH(public key)   │
     ├──────────────────────────────────────►│
     │ CNXN(device banner)                   │
     │◄──────────────────────────────────────┤
```

首次连接时目标设备可能要求用户确认 RSA 指纹。私钥保存在应用的 `noBackupFilesDir`，不会通过
Android 自动备份迁移。协议 `0x01000001` 的 peer 可以发送零 checksum，因此读取端同时接受有效
checksum 和零值。

### 3.2 ADB 多路复用

每个逻辑流通过 `OPEN/OKAY/WRTE/CLSE` 在单条 ADB transport 上复用。当前会话通常同时存在：

- `shell:CLASSPATH=... app_process ...`：保持 server 进程存活；
- `localabstract:scrcpy_<scid>`：视频通道；
- `localabstract:scrcpy_<scid>`：控制通道。

server shell 不能用简单的 `command &` 启动后立即关闭：部分系统会随 shell stream 结束清理子进程。
当前实现保持 shell channel 活跃，并在停止会话时通过协程取消关闭它。

## 4. scrcpy 会话时序

```text
MainViewModel       ScrcpyClient         AdbClient         target Android
     │                    │                  │                    │
     │ start(surface)     │                  │                    │
     ├───────────────────►│ push(server.jar) │                    │
     │                    ├─────────────────►│ sync SEND/DATA     │
     │                    │                  ├───────────────────►│
     │                    │ shell app_process│                    │
     │                    ├─────────────────►│───────────────────►│
     │                    │ open video       │                    │
     │                    ├─────────────────►│ localabstract      │
     │                    │ open control     │                    │
     │                    ├─────────────────►│ localabstract      │
     │                    │ codec/session/frame packets           │
     │                    │◄───────────────────────────────────────┤
     │ Streaming(size)    │                  │                    │
     │◄───────────────────┤                  │                    │
     │ touch/key          │ control message  │                    │
     ├───────────────────►├───────────────────────────────────────►│
```

server 使用 `tunnel_forward=true` 在目标端监听 abstract Unix socket。控制端通过 adbd 的
`localabstract:` service 直接连接，不需要在控制端 Android 上运行外部 `adb` 可执行程序。

## 5. 视频数据链路

```text
目标屏幕 → scrcpy-server MediaCodec encoder → ADB video channel
         → 4-byte codec id
         → 12-byte session/frame header
         → H.264 payload
         → VideoStreamDecoder
         → Android MediaCodec decoder
         → SurfaceView
```

当前只接受 codec id `h264`。视频流包含两类 12 字节大端序 header：

- session packet：最高位为 1，携带 width/height；
- media packet：携带 config/key-frame 标记、PTS 和 payload 长度。

收到新的 session size 时重建 decoder，以处理旋转、折叠或目标显示尺寸变化。视频 payload 长度有
上限检查，避免畸形数据触发无界内存分配。

## 6. 控制数据链路

`SurfaceView` 接收本地 `MotionEvent`，先按 fit-center 内容区域将本地坐标映射为远端视频坐标：

```text
scale = min(viewWidth / remoteWidth, viewHeight / remoteHeight)
remoteX = (localX - contentLeft) / scale
remoteY = (localY - contentTop) / scale
```

letterbox 区域的事件被忽略。每个 Android pointer id 被保留，并序列化为 scrcpy 32 字节 touch
message。MOVE 事件会为所有当前 pointer 分别发送消息。

全屏和普通预览都使用 session 上报的实际宽高比约束 `SurfaceView`，在可用区域内按
fit-center 策略居中显示，多余区域保持黑色，不拉伸或裁剪被控设备画面。

控制消息采用大端序，包含 action、pointer id、远端坐标、远端尺寸、pressure 与 button flags。

## 7. 状态与所有权

### ADB 状态

```text
Disconnected → Connecting → Authorizing → Connected
                       └───────────────→ Failed
```

### scrcpy 状态

```text
Idle → InstallingServer → StartingServer → ConnectingStreams → Streaming
  ▲              └────────────── 任意阶段 ───────────────────→ Failed
  └──────────────────────────── stop/close ───────────────────────┘
```

`MainViewModel` 是当前 UI 会话所有者：

- Activity/Compose 提供和销毁 `Surface`；
- ViewModel 创建并关闭 `AdbClient`、`ScrcpyClient`；
- `ScrcpyClient` 拥有 server、video 和 control jobs/channels；
- stop 时取消 jobs，关闭两个 stream，再由 shell stream 关闭触发 server 退出。

连接成功后 `MainViewModel` 会在视频 Surface 就绪时自动启动 scrcpy。UI 默认进入隐藏系统栏的
沉浸式预览；悬浮遥控器可整体拖动，靠近屏幕两侧时吸附并收缩为遥控器图标，从边缘拖出后恢复完整面板。
面板提供方向/确定/返回/Home/菜单、音量加减和红色电源按键；长按收缩后的图标可在全屏预览与普通主界面之间切换。
视频可延伸到屏幕裁切区，但悬浮控件始终受 safe-drawing insets 约束。

控制端旋转和窗口尺寸变化由 `MainActivity` 原地处理，不能仅因配置变化重建 Activity 并关闭连接。
Compose 切换普通/全屏布局时可能短时间创建多个 `SurfaceView`；`MainViewModel` 只接受当前
`Surface` 实例的销毁通知，并在 Surface 稳定后再启动镜像，避免旧 Surface 的回调停止新会话。
`ScrcpyClient` 通过会话代次隔离异步任务，已取消或过期任务的失败不能覆盖当前会话状态。

当前没有前台服务，因此进程被系统回收或 Activity 长时间处于后台时不保证会话持续。

## 8. 安全与兼容性

- 目标设备必须主动启用并授权 ADB；应用不会绕过系统授权。
- 不记录 RSA 私钥或 AUTH token。
- UI 展示 shell 诊断输出时应避免默认执行包含敏感信息的命令。
- scrcpy wire protocol 是内部协议，server 和 client 必须锁定同一版本。
- `scrcpy-server-v4.0` 是 scrcpy 官方未修改的 Apache-2.0 二进制；分发要求与复用范围见 `THIRD_PARTY_NOTICES.md`。
- legacy TCP ADB 是明文 transport，应只在可信网络使用；Android 11+ 无线调试使用配对和 TLS 连接。

## 9. 测试策略

| 层级 | 当前覆盖 | 后续重点 |
|---|---|---|
| 独立 `adb` 仓库单元测试 | endpoint 校验 | packet、AUTH、公钥、stream 分发、sync push |
| `scrcpy` 单元测试 | 待补充 | frame header、control message、状态转换 |
| `app` 单元测试 | 悬浮遥控器边界与展开/收起/贴边/缩放比例运算 | 主题、连接历史筛选 |
| Compose instrumentation | 断开状态与 Connect 操作、主机历史建议、输入框焦点保持、遥控器拖放与短区域缩放 | 连接/失败/Streaming 状态、坐标映射 |
| 云设备 E2E | ADB、shell、push、server、1920×1072 streaming | 双设备画面内容、旋转、多点触控、断线重连 |

完整本地验证命令：

```shell
./gradlew :scrcpy:testDebugUnitTest \
  :app:testDebugUnitTest \
  :app:assembleDebug \
  :app:connectedDebugAndroidTest
```

## 10. 演进方向

建议保持现有边界按以下顺序扩展：

1. 为 ADB packet、控制消息和视频 header 增加确定性单元测试；
2. 扩展无线发现和设备选择；
3. 增加多 USB 设备选择与插拔处理；
4. 增加断线重连、超时与显式 server 日志通道；
5. 增加音频 channel、解码和 `AudioTrack`；
6. 增加 device-message reader 和剪贴板同步；
7. 用前台服务承载需要跨 Activity 生命周期的长会话。
