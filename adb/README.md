# `ernest.ascrcpy.adb`

Reusable Android ADB-host module with an implementation-independent public API.

## API boundaries

- Application API: `AdbClient`, `AdbChannel`, `AdbEndpoint`, state and result models.
- Identity API: `AdbKeyProvider`.
- Transport API: `AdbTransport` and `AdbTransportFactory`.
- Default implementation: `DefaultAdbClient`, `TcpAdbTransport`, and `FileAdbKeyProvider`.

The default key provider stores a generated 2048-bit RSA identity under the application's
no-backup directory. A target device may ask the user to authorize the key on first connection.

The module contains no dependency on Compose or on scrcpy and can be reused independently.
