watercontrol (Android port)
===========================

This application is a Kotlin/Android port of **waterctl**, an open-source controller for
"Bluetooth water controllers" (蓝牙水控器) made by 深圳市常工电子.

The port was written by **DeepSeek** (https://chat.deepseek.com) working under human
direction. See the "Credits" section below.

Upstream project
----------------

    https://github.com/celesWuff/waterctl

    MIT License

    Copyright (c) 2021 celesWuff

The `deputy` key-derivation module additionally carries:

    Copyright (c) 2021-2024 celesWuff, Deputy

Ported components
-----------------

The following upstream files were translated to Kotlin. Behaviour is kept deliberately
identical, including several bugs, which are marked as "bug-for-bug compatible" in the
source where they occur.

    src/algorithms.ts      -> protocol/Algorithms.kt
    src/payloads.ts        -> protocol/Payloads.kt
    src/solvers.ts         -> protocol/Solvers.kt
    src/utils.ts           -> protocol/WaterUtils.kt
    src/deputy.wat         -> protocol/Deputy.kt        (WebAssembly translated to Kotlin)
    src/bluetooth.ts       -> WaterSession.kt + bluetooth/WaterBleClient.kt
    src/errors.ts          -> WaterSession.Reason + MainActivity error dialogs
    src/logger.ts          -> Logger.kt
    src/solvers.spec.ts    -> test/.../protocol/SolversTest.kt
    src/algorithms.spec.ts -> test/.../protocol/AlgorithmsTest.kt
    src/utils.spec.ts      -> test/.../protocol/AlgorithmsTest.kt

Intentional differences from upstream
-------------------------------------

1. `makeDatetimeArray()` uses the device's local time zone. Upstream hard-codes
   `Asia/Shanghai`, which is wrong for a phone that is set to another zone.
2. `RxdFrameParser` exists only here. It reassembles frames that BLE delivers across several
   notifications and repairs the dropped leading `0xFD` bytes. It accepts every byte
   sequence the upstream parser accepted.
3. Connecting and starting are two separate user actions on two separate buttons, guarded by
   the extra `CONNECTED` session state. Upstream has a single button that toggles starting and
   ending, because Web Bluetooth's device chooser already did the connecting -- and the
   connect/start pair is implicit in its two clicks. On Android that conflation would let a
   mere association open a billing session, so the link state (Connect/Disconnect) and the
   billing state (Start/Stop) are kept apart. Start/Stop is disabled while disconnected, and
   "Stop" now returns to `CONNECTED` rather than tearing the link down -- upstream's toggle
   disconnects on every stop, which is wasteful when the user is about to start again.
4. Device discovery presents the scan result as a picker instead of auto-connecting. A
   dormitory corridor has several controllers in range, so names matching the controller
   prefixes (`Water*`, `shui*`, `cg*`, `水控*`, `热水*`) are listed first and everything else
   is available behind "显示全部设备". Upstream relies on the browser's chooser for this.
5. The last *successfully* connected controller is remembered (`LastDeviceStore`) so it can be
   reconnected without scanning. This has no upstream counterpart, since the browser chooser
   does not persist a choice either.

Credits
-------

    Original project (protocol, key derivation, application design)
        celesWuff            https://github.com/celesWuff/waterctl
        Deputy               the `deputy` WebAssembly key-derivation module

    Android port (all Kotlin source in this repository)
        DeepSeek             https://chat.deepseek.com
        Written by DeepSeek under human direction: the requirements, the real-device
        testing and the design decisions were made by the repository owner; the protocol
        translation, BLE layer, UI implementation and debugging were produced by the AI.

License
-------

This port is distributed under the MIT License; the full text, including both the upstream
and the port's copyright notices, is in [LICENSE](LICENSE).
