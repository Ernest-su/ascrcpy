# AScrcpy

Android-to-Android screen mirroring and control built with Jetpack Compose.

See [the architecture document](docs/architecture.md) for module boundaries and protocol flows,
and [AGENTS.md](AGENTS.md) for repository development rules.

## Modules

- `app`: Compose controller UI and application lifecycle.
- `adb`: reusable ADB host library. Its public API does not expose the TCP implementation.
- `scrcpy`: scrcpy 4.0 server lifecycle, stream protocol, MediaCodec decoder, and control messages.

All project-owned packages start with `ernest.ascrcpy`. The ADB module namespace is
`ernest.ascrcpy.adb`.

## Build

```shell
./gradlew :adb:testDebugUnitTest :scrcpy:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug
```

Install with the Android CLI:

```shell
android run --device=<serial> --activity=ernest.ascrcpy.MainActivity \
  --apks=app/build/outputs/apk/debug/app-debug.apk
```

## Using the ADB module

Add the module dependency:

```kotlin
dependencies {
    implementation(project(":adb"))
}
```

Consume only the stable facade:

```kotlin
val client = DefaultAdbClient.factory(context).create()
val device = client.connect(AdbEndpoint("192.168.1.20", 5555))
val result = client.shell("getprop ro.product.model")
client.push(inputStream, "/data/local/tmp/tool.jar")
val channel = client.open("localabstract:my_service")
```

`AdbClient`, `AdbChannel`, `AdbKeyProvider`, `AdbTransport`, and their factories are interfaces.
A future USB implementation can supply another `AdbTransportFactory`; a third-party ADB library
can implement `AdbClient` directly. Neither option requires changes in the app or scrcpy module.

The included implementation connects directly to an already-enabled TCP adbd. Android 11+
wireless-debugging pairing and USB transport are extension points, not implemented yet.

## Current feature set

- Direct TCP ADB with persistent RSA identity and device authorization.
- Shell, sync push, and multiplexed arbitrary ADB services.
- Bundled matching scrcpy-server 4.0.
- H.264 low-latency decoding to `SurfaceView` with MediaCodec.
- Single- and multi-pointer control message forwarding.
- Session state, error reporting, rotation/session-size handling, and clean shutdown.

Audio, clipboard synchronization, Android 11 pairing, discovery, and USB transport are planned
extensions.

## Test device

Validated on a connected `KONKA Android TV KKAML966D5`, Android 14:

- application installation and Compose launch;
- direct ADB connection and authorization;
- shell execution and server upload;
- scrcpy-server startup;
- H.264 encode/decode pipeline at `1920 × 1072`;
- video and control channel establishment.

## Third-party software

The binary in `app/src/main/assets/scrcpy-server-v4.0` is the unmodified scrcpy 4.0 server.
See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
