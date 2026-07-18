# ar-glass-lib

Android library and standalone check app for USB-C AR glasses.

Supported models:

- **XREAL Air 2 Ultra** (`3318:0426`, Flora)
- **XREAL XBX A01** (`3318:0440`, Helen)
- **XREAL XBX A01 Plus** (`3318:0442`, Helen Pro)
- **XREAL One S** (`3318:043E`, GS)
- **Rokid Air / Max** (`04D2:162F`)
- **VITURE Beast** (`35CA:1201` and `35CA:1211`, Gen2 Native DOF)
- **LUCI displays** (`2C30:1030` and `2C30:1031`)

## Capabilities

- Identify the glasses model from Android USB Host descriptors.
- Read versioned XREAL IMU reports (acceleration, angular velocity, magnetic field, temperature, and device timestamp).
- Query and switch 2D, Half SBS, Full SBS, and high-refresh SBS display modes.
- Inspect the resolution and refresh rate exposed by Android for the external display.
- Reuse the protocol implementation from Kotlin/Java or link the native `ar_glass` CMake target directly into another JNI library.

The `library` module is the reusable API. The `app` module is an independently installable, framework-Views diagnostic UI that waits for glasses, identifies them, and lets the user run each check explicitly. Each check has its own Activity and implementation:

- `ImuCheckActivity`: opens only the IMU interface and validates its stream.
- `DisplayModeCheckActivity`: opens only the display-control interface and provides standalone **开启 3D** / **关闭 3D（恢复 2D）** controls. It selects the model's preferred supported 3D mode while model-specific commands remain isolated in their drivers.
- `ResolutionCheckActivity`: uses `DisplayManager` and never opens USB endpoints.
- `CameraCheckActivity`: appears only for VITURE Beast, prefers an external Camera2 device, and falls back to direct UVC/libusb preview from the separately enumerated `0C45:6368` camera.

The launcher Activity only identifies the glasses and navigates to a selected check. Display mode commands are never sent during passive detection.

The standalone APK also has a **导出诊断日志** action. It exports two separate binary files instead of mixing diagnostics into logcat:

- `usb-transfers.bin`: append-only USB permission and raw control/bulk/interrupt transfer records, including VID/PID, endpoint or control parameters, result length, and payload bytes. Record magic is `ARUS` and format version is 1; operation 1 is device-to-host, 2 is host-to-device, 3 is a permission request, and 4 is its result.
- `crashes.bin`: append-only uncaught exception records with `ARCR` magic, format version 1, timestamp, thread name, and stack trace bytes.

Model code is isolated below `library/.../driver/<vendor>/<model>/`. A driver owns its USB identity, interfaces, wire protocol, IMU decoder, and display-mode behavior. `GlassesDriverRegistry` is the only shared routing table; adding a model does not add protocol branches to another model's session.

## Build

Requires JDK 17, Android SDK 36, and an installed Android NDK/CMake toolchain. Build the independently installable check APK with:

```bash
./scripts/build-apk.sh
```

The APK is copied to `dist/ar-glass-check-debug.apk`. To build an unsigned release APK instead, run `./scripts/build-apk.sh release`.

For module-only builds:

```bash
./gradlew :library:assembleDebug :app:assembleDebug
```

## Android API

```kotlin
val manager = ArGlassesManager(context, context.mainExecutor, listener)
val connected = manager.scan().firstOrNull() ?: return
if (!manager.hasPermission(connected.device)) manager.requestPermission(connected.device)
val session = manager.open(connected.device)
session.setDisplayMode(DisplayMode.FULL_SBS_3D)
```

`ArGlassesListener.onImuSample` receives SI-unit samples. The device timestamp remains the original glasses clock and is not replaced by Android receive time.

## White-box native integration

Add `library/src/main/cpp` with CMake `add_subdirectory`, link the static `ar_glass` target, and include `ar_glass.h`:

```cmake
add_subdirectory(path/to/ar-glass-lib/library/src/main/cpp ar-glass-lib)
target_link_libraries(your_jni_target PRIVATE ar_glass)
```

The public native surface provides XREAL MCU/IMU packet construction and versioned IMU decoding without requiring the standalone JNI adapter.

## VITURE Beast protocol notes

- USB controller identities: VID `0x35CA`, PID `0x1201` or `0x1211`.
- Gen2 V2 packets use a `10 00` header, little-endian message ID and payload length, and a 16-bit payload checksum.
- RAW IMU starts with message `0x0301` and payload `02 02` (120 Hz); reports use message `0x7309`.
- `0x3140` queries Native/Bypass, `0x3142` queries 2D/3D, and `0x0142 [31|37]` selects 2D/3D.
- The Beast driver claims only its HID protocol interfaces and supports HID control-transfer fallback when an interface has no OUT endpoint.
- Beast's monocular camera is a separate `0C45:6368` USB device. The standalone check APK negotiates its 1920×1080@30 MJPEG stream on interface 1 / isochronous endpoint `0x81` when Android does not expose it through Camera2.
- The native UVC fallback is adapted from `android-sensor-probe`, where this path was verified on Beast hardware. Its vendored LGPL-2.1-or-later libusb subset is built as a separate shared library and retains the upstream license/source files.

## LUCI protocol notes

- USB identities: VID `0x2C30`, PID `0x1030` or `0x1031`.
- 2D/3D switching uses a 64-byte HID Feature Report (`SET_REPORT`, value `0x0302`).
- The LUCI driver exposes display-mode and resolution checks. It does not advertise IMU because this protocol does not provide a verified LUCI sensor stream.

## XREAL Air 2 Ultra protocol notes

- USB application identity: VID `0x3318`, PID `0x0426`.
- MCU: interface 0; IMU: interface 1.
- IMU uses CRC32-protected `0xaa` control frames and 64-byte versioned reports.
- Display modes use CRC32-protected `0xfd` MCU commands `0x07` (query) and `0x08` (set).
- Generic `DisplayMode.wireValue` values are used only by drivers whose protocol
  defines that mapping. Model-specific drivers translate to their native modes.

Protocol behavior was adapted from the open-source `android-sensor-probe` project and its XREAL protocol research. Hardware behavior still needs validation on each firmware version.

For Air 2 Ultra/Flora, the ARLauncher-compatible preferred modes are `10`
(1920x1080@90 2D), `4` (3840x1080@72 3D), and `2` (3840x1080@120 3D).
Flora's official mode table has no Half SBS entry. Unlike Helen, Flora encodes
the command `0x08` mode payload as one byte; this matches the previously
hardware-validated implementation and `ar-drivers-rs`.

## XREAL XBX protocol notes

- XBX A01 uses `3318:0440`; XBX A01 Plus uses `3318:0442`.
- Both use the Helen transport but remain separately registered models.
- The driver claims MCU interface 0 first and sends `0x26`, `0x57`, `0x12(1)`, `0x02(1)`, `0x34`, `0x35`.
- It then performs the required `0x31 / "3.1.1"` SDK handshake and two initial heartbeats before claiming IMU interface 1.
- A 100 ms MCU heartbeat remains active for the session lifetime.
- IMU initialization stops the old stream, reads the complete calibration blob, syncs, and starts the versioned 64-byte report stream.
- Display query/switch uses the same MCU `0x07` / `0x08` commands after completing the Helen bootstrap.
- Helen does not use the generic display-mode wire values. Matching ARLauncher,
  the preferred modes are `10` (1920x1080@90 2D), `4` (3840x1080@72 3D),
  and `2` (3840x1080@120 3D). Mode values in command `0x08` are always encoded
  as four-byte little-endian integers, matching the official `int EGlassMode` ABI.

All XREAL USB interfaces and transfers are owned by `XrealNativeUsbSession` in
JNI. Kotlin retains Android device enumeration/permission and the One-family
USB-Ethernet TCP reader, but does not claim interfaces or issue XREAL endpoint
transfers. Native transactions perform framing, request-ID matching, bounded
asynchronous-event skipping, and route every Android bulk call through the
shared binary diagnostics recorder.

## XREAL One S protocol notes

- Runtime USB identity: `3318:043E` (GS, official type 71). The adjacent odd PID is a bootloader and is not opened as a runtime device.
- Display query/switch uses XREAL MCU interface 0 with FD commands `0x07` / `0x08`.
- Matching the official One-series `ControlSet2D3DMode` path, preferred modes are
  `10` (1920x1080@90 2D), `4` (3840x1080@72 3D), and `2`
  (3840x1080@120 3D). The official mode table has no Half SBS entry.
- IMU is intentionally separate from Air/Flora/Helen HID code. It connects through the glasses' USB Ethernet link at `169.254.2.1:52998`.
- The stream is reassembled into 84-byte frames and exposes acceleration, angular velocity, and the device timestamp in Android-oriented coordinates.
- The USB Ethernet frame implementation follows `android-sensor-probe`'s `XrealOneTcpReader`; it needs final verification on One S firmware because that reader was originally validated on the earlier One family.

## Rokid Air / Max protocol notes

- Both models use USB identity `04D2:162F`; the USB product string distinguishes Max, with Air as the fallback.
- IMU, magnetometer, keys, and proximity reports arrive passively on interrupt endpoint `0x82`.
- Display-mode vendor control transfers query and switch mirrored 2D, Full SBS 3D, high-refresh 2D, and high-refresh SBS 3D.
- The implementation follows the MIT-licensed `ar-drivers-rs` Rokid driver and its Android port in `android-sensor-probe`.
