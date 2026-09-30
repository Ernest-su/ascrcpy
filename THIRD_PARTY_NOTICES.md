# Third-party notices

## scrcpy-server 4.0

AScrcpy redistributes the unmodified `scrcpy-server` version 4.0 binary from the application assets.
The file is byte-identical to the artifact published by the scrcpy project.

- Project: <https://github.com/Genymobile/scrcpy>
- Tag: `v4.0`
- Copyright: Copyright (C) 2018 Genymobile, Copyright (C) 2018-2026 Romain Vimont, and scrcpy contributors
- License: Apache License, Version 2.0
- License text: <https://github.com/Genymobile/scrcpy/blob/v4.0/LICENSE>
- Corresponding source: <https://github.com/Genymobile/scrcpy/tree/v4.0>
- Binary SHA-256: `84924bd564a1eb6089c872c7521f968058977f91f5ff02514a8c74aff3210f3a`

The whole scrcpy repository, client and server alike, is licensed under the Apache License 2.0.
Redistributing this binary therefore requires keeping the copyright and license notices above, and
offering the corresponding source, which this file and the project repository do.

### Scope of reuse

Only the server binary is reused, and only to run on the target device. The controller side of
AScrcpy - the ADB host implementation, the stream protocol handling, the `MediaCodec` decoder, the
control-message encoder and the entire user interface - is an independent implementation written for
this project. It contains no scrcpy client source code.

AScrcpy speaks the scrcpy wire protocol, which is an internal protocol of the scrcpy project and is
versioned together with it. The bundled server and the client must therefore always be the same
scrcpy version; see `ScrcpyClient.SERVER_VERSION`.

## Design reference

The visual language of the floating remote follows the Tencent WeUI design language. WeUI is an open
source project maintained by the WeChat design team and is distributed under its own license; no
WeUI source code, trademark, or product asset is copied into this repository.
