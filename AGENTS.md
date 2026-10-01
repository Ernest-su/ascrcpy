# AScrcpy Agent Guide

This file applies to the whole repository. Keep implementation and documentation consistent with
[`docs/architecture.md`](docs/architecture.md).

All `app` UI and launcher-icon changes must also follow the mandatory WeUI-derived tokens, spacing,
shape, state, dark-theme, and icon constraints in [`docs/design-system.md`](docs/design-system.md).

## Project intent

AScrcpy is an Android controller for another Android device. It connects directly to the target
`adbd`, starts a matching scrcpy server, decodes its H.264 stream with `MediaCodec`, and sends
control messages from a Compose UI.

The current supported transport is an already-enabled TCP adbd, normally on port 5555. Do not
claim that Android 11 wireless-debugging pairing or USB ADB is supported until it is implemented
and tested.

## Module boundaries

- `app` owns Compose UI, Android lifecycle, assets, and dependency wiring.
- `adb` is a separately published reusable library and must not depend on `app`, `scrcpy`, Compose, or scrcpy protocol
  types.
- `scrcpy` depends only on the public ADB facade for device communication. It owns server startup,
  scrcpy framing, decoding, and control-message serialization.
- Allowed dependency direction is `app -> scrcpy -> adb`; `app -> adb` is also allowed for direct
  connection and diagnostics.

Never expose `Socket`, `TcpAdbTransport`, protocol packets, or a third-party ADB library type from
the stable `AdbClient`/`AdbChannel` API. New TCP, USB, local-server, or third-party implementations
must remain replaceable through the existing interfaces and factories.

## Naming and source conventions

- All project-owned packages start with `ernest.ascrcpy`.
- The ADB public namespace is `ernest.ascrcpy.adb`.
- Use Kotlin for new application and library code unless a platform API requires Java or native
  code.
- Use suspending functions for blocking I/O and expose observable lifecycle state as `StateFlow`.
- Keep Android framework types out of the core ADB facade where practical. Android-specific key
  storage and transports belong in implementation packages.
- Use big-endian encoding for scrcpy messages and little-endian encoding for ADB packet headers
  and sync protocol fields, as required by their protocols.
- Bound all network lengths before allocating buffers. Treat malformed packets and unexpected EOF
  as protocol errors.

## scrcpy compatibility

The client protocol and bundled server are a matched pair. The current pinned version is 4.0:

- asset: `app/src/main/assets/scrcpy-server-v4.0`
- client constant: `ScrcpyClient.SERVER_VERSION`
- checksum and source information: `THIRD_PARTY_NOTICES.md`

Never update only one side. A scrcpy upgrade must update the asset, protocol implementation,
version constant, SHA-256 notice, tests, and architecture documentation in the same change.

The bundled server is unmodified Apache-2.0 software from the scrcpy project. Preserve its notice and
corresponding-source link when redistributing it, and keep stating the reuse scope accurately: the
server binary is reused, the controller side is an independent implementation.

## Build and test

Run the focused test for the module being changed, then run the full verification before handoff:

```shell
./gradlew :scrcpy:testDebugUnitTest \
  :app:testDebugUnitTest \
  :app:assembleDebug
```

When a device is connected, also run:

```shell
./gradlew :app:connectedDebugAndroidTest
```

Never commit `keystore.properties`, `*.jks` or `*.keystore`; they are ignored, and the release
workflow restores its keystore from repository secrets. Cutting a release is documented in
[docs/releasing.md](docs/releasing.md).

For an end-to-end device check, verify this sequence:

1. Install and launch `ernest.ascrcpy.MainActivity`.
2. Connect to an authorized TCP adbd.
3. Run the shell probe.
4. Upload and start scrcpy-server.
5. Reach `ScrcpyState.Streaming` and confirm the reported dimensions.
6. Exercise a control event and stop the session.
7. Confirm that the server `app_process` exits after the controller stops.

Testing against the same device as both controller and target can produce a black or recursive
preview even while the encoder/decoder pipeline is healthy. Prefer two devices when validating
visible frame contents and touch-coordinate accuracy.

## Change discipline

- Preserve the user's existing changes and do not commit generated `build/` directories.
- Keep commits grouped by function: ADB transport/API, scrcpy protocol/media, UI, tests/docs.
- Add or update tests for protocol serialization, state transitions, coordinate mapping, and
  regressions found on devices.
- Update `README.md` and `docs/architecture.md` whenever public APIs, module responsibilities,
  supported transports, or the session sequence changes.
- Do not log private keys, ADB authentication tokens, full device secrets, or arbitrary remote
  shell content.

## Known extension points

- Android 11+ TLS pairing and mDNS discovery.
- USB host transport implementing `AdbTransport`.
- Audio stream and `AudioTrack` playback.
- Clipboard/device-message receive loop.
- Keyboard, gamepad, and richer control messages.
- Foreground-service ownership for sessions that must survive UI recreation.

Implement these behind existing boundaries; do not fold transport or scrcpy protocol logic into
Compose code.
