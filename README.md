# ar-glass-lib

Android library and standalone check app for USB-C AR glasses.

Supported models:

- **XREAL Air** (`3318:0424`, Air)
- **XREAL Air 2** (`3318:0428`, P55)
- **XREAL Air 2 Pro** (`3318:0432`, P55E)
- **XREAL Air 2 Ultra** (`3318:0426`, Flora)
- **XREAL XBX A01** (`3318:0440`, Helen)
- **XREAL XBX A01 Plus** (`3318:0442`, Helen Pro)
- **XREAL One S** (`3318:043E`, GS)
- **XREAL One** (`3318:0438`, GF)
- **XREAL Light** (`0486:573C` MCU + `05A9:0680` OV580)
- **Grawoow G530 / MetaVision M53** (`1FF7:0FF4` MCU + `05A9:0F87` OV580)
- **RayNeo Air 3S Pro** (`1BBB:AF50`, open HID IMU)
- **VITURE Luma** (`35CA:1131`, open Gen2 RAW IMU)
- **VITURE Luma Pro** (`35CA:1121` and `35CA:1141`, open Gen2 RAW IMU)
- **VITURE Luma Cyber** (`35CA:1151`, open Gen2 RAW IMU)
- **Rokid glasses** (`04D2:162B`, `162C`, `162D`, `162E`, `162F`, `2002`, and `2180`; product string supplies the market name)
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
- `XrealEyeCameraCheckActivity`: appears for the XREAL One family and uses the open libusb/UVC backend to negotiate and read MJPEG without any vendor SO. It requests USB permission only after a matching XREAL One VideoStreaming interface is present.

The launcher Activity only identifies the glasses and navigates to a selected check. Display mode commands are never sent during passive detection.

The standalone APK also has a **导出诊断日志** action. It exports two separate binary files instead of mixing diagnostics into logcat:

- `usb-transfers.bin`: append-only USB permission and raw control/bulk/interrupt transfer records, including VID/PID, endpoint or control parameters, result length, and payload bytes. Record magic is `ARUS` and format version is 1; operation 1 is device-to-host, 2 is host-to-device, 3 is a permission request, and 4 is its result.
- `crashes.bin`: append-only uncaught exception records with `ARCR` magic, format version 1, timestamp, thread name, and stack trace bytes.

Model code is isolated below `library/.../driver/<vendor>/<model>/`. A driver owns its USB identity, interfaces, wire protocol, IMU decoder, and display-mode behavior. `GlassesDriverRegistry` is the only shared routing table; adding a model does not add protocol branches to another model's session.

When adding or correcting a glasses protocol, use
[`XRLinuxDriver/src/devices`](https://github.com/wheaney/XRLinuxDriver/tree/main/src/devices)
as the source of truth. Its device selection, USB identities, display-mode
mappings, IMU transport, initialization, axes, and units take precedence when
the references disagree. Cross-check the other projects for Android adaptation
and additional protocol evidence, but do not use them to override
XRLinuxDriver behavior:

- [`android-sensor-probe`](https://github.com/taowen/android-sensor-probe)
  provides Android USB Host, permission, JNI, and hardware-check examples, plus
  Android ports of several glasses protocols.
- [`ar-drivers-rs`](https://github.com/badicsalex/ar-drivers-rs) provides
  compact Rust implementations of multiple glasses drivers and is especially
  useful for verifying raw packet layouts, commands, IMU decoding, coordinate
  transforms, and unit conversions.

Follow any interface-library or device-specific dependency used by a reference
implementation. Compare independent implementations where possible; do not
infer one model's protocol solely from another model in this library.

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

The public native surface provides XREAL MCU/IMU packet construction and versioned IMU decoding without requiring the standalone JNI adapter. In the Android library, Kotlin enumerates descriptors and handles permission/UI only. Interface claims and all control, bulk, interrupt, and UVC transfers execute through JNI/libusb; Kotlin production sources do not call Android USB transfer APIs.

The native transport records every control, bulk, interrupt, and UVC transfer directly to `usb-transfers.bin`. Android-fd and direct-libusb camera paths use the same `ARUS` binary record format.

Composite glasses are represented as one `ConnectedGlasses` entry with a primary
controller and one or more companion USB devices. Permission requests are
serialized across every component before the driver opens either fd. This is
used by XREAL Light and Grawoow instead of exposing their MCU and OV580 as two
unrelated glasses.

## VITURE Beast protocol notes

- USB controller identities: VID `0x35CA`, PID `0x1201` or `0x1211`.
- Gen2 V2 packets use a `10 00` header, little-endian message ID and payload length, and a 16-bit payload checksum.
- RAW IMU starts with message `0x0301` and payload `02 02` (120 Hz); reports use message `0x7309`.
- `0x3140` queries Native/Bypass, `0x3142` queries 2D/3D, and `0x0142 [31|37]` selects 2D/3D.
- The Beast driver claims only its HID protocol interfaces and supports HID control-transfer fallback when an interface has no OUT endpoint.
- Beast's monocular camera is a separate `0C45:6368` USB device. The standalone check APK negotiates its 1920×1080@30 MJPEG stream on interface 1 / isochronous endpoint `0x81` when Android does not expose it through Camera2.
- The native UVC fallback is adapted from `android-sensor-probe`, where this path was verified on Beast hardware. Its vendored LGPL-2.1-or-later libusb subset is built as a separate shared library and retains the upstream license/source files.

## VITURE family support notes

- XRLinuxDriver is authoritative for the supported PID list and model names.
- Luma `1131`, Luma Pro `1121/1141`, and Luma Cyber `1151` expose the open
  Gen2 `0301 [02 02]` RAW IMU stream with `7309` reports. They currently
  advertise IMU and resolution checks only.
- XRLinuxDriver performs their 2D/3D switching through VITURE's proprietary
  `libglasses.so`. The Android SDK license prohibits unauthorized copying,
  distribution, and use, so ar-glass-lib neither bundles it nor falsely exposes
  that SDK-only display control.
- One/Lite/Pro expose SDK pose callbacks but do not yet have a cross-verified
  open raw-IMU implementation. Luma Ultra uses the proprietary Carina path.

## RayNeo Air 3S Pro protocol notes

- XRLinuxDriver identifies the device as `1BBB:AF50`; its full display and pose
  implementation calls the proprietary `libRayNeoXRMiniSDK.so`.
- The open backend sends HID command `66 01` and decodes the independently
  captured `99 65` acceleration, angular velocity, magnetic field, temperature,
  and timestamp report. It therefore advertises IMU and resolution only.
- XRLinuxDriver ships the RayNeo SDK only for x86_64, so it cannot be used in
  this Android ARM64 library. 2D/3D is intentionally not advertised until that
  SDK behavior has an open protocol implementation.

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

## XREAL Air family protocol notes

- Air `3318:0424`, Air 2 `3318:0428`, and Air 2 Pro `3318:0432` use MCU interface 4 and IMU interface 3.
- All three use the one-byte `0x07` / `0x08` display-mode protocol and expose 2D, Full SBS, Half SBS, and 90 Hz SBS modes.
- Their 64-byte versioned IMU reports and initialization commands are cross-checked against both `ar-drivers-rs` and XRLinuxDriver's `xrealInterfaceLibrary`.

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
JNI/libusb. Kotlin retains Android device enumeration/permission only; XREAL
One-family USB-Ethernet TCP DP control and IMU connect/read/frame decode are also owned by JNI.
Native transactions perform framing, request-ID matching, bounded
asynchronous-event skipping, and write the shared binary diagnostics stream.

## XREAL One family protocol notes

- Runtime USB identities: One Pro `3318:0436` (Gina, official type 41), One `3318:0438` (GF, official type 47), and One S `3318:043E` (GS, official type 71). Adjacent odd PIDs are bootloaders and are not opened as runtime devices.
- One-family 2D/3D switching is not the old XREAL USB MCU path. Control My
  Glasses 1.1.0 uses the USB-Ethernet DP RPC service at `169.254.2.1:52999`.
  Verified packets are `0x275e` get current EDID, `0x275f` set current EDID,
  and `0x2822` set DP input mode. `EDID=5 + inputMode=1` switches XREAL One to
  `3840x1080@60` Full SBS 3D; `EDID=9 + inputMode=0` restores
  `1920x1080@90` 2D.
- IMU is intentionally separate from Air/Flora/Helen HID code. It connects through the glasses' USB Ethernet link at `169.254.2.1:52998`.
- The JNI TCP reader follows XRLinuxDriver's vendored `xreal_one_driver`: find
  header `28 36 00 00 00 80`, require marker `00 40 1F 00 00 40`, reassemble
  84-byte frames, and expose acceleration, angular velocity, and the device
  timestamp in Android-oriented coordinates.
- XRLinuxDriver notes that One/One Pro/1S require latest firmware and glasses
  stabilizer/anchor features disabled. Those prerequisites apply to the IMU
  path; they do not by themselves define an open 2D/3D switching protocol.
- ARLauncher exposes RGB-camera frames through `StartRGBCameraDataCapture`, `TryAcquireLatestImage`, and `TryGetRGBCameraDataPlane`, with `RGB_888` and `YUV_420_888` formats. Its native path is `SessionManager` -> `NRRGBCameraWrapper` -> the `NRRgbCamera*` plugin ABI. Extraction of the bundled Gina firmware confirms `rgb_camera_enable`, XREAL `3318:0438`, an MJPEG UVC gadget, and the `uvc_bulk_15` composite mode. The implementation does not link or load ARLauncher SO files; it negotiates the UVC descriptors at runtime and supports bulk transfers instead of applying Beast's isochronous assumptions.

## XREAL Light protocol notes

- The MCU is `0486:573C`; the IMU/camera companion is OV580 `05A9:0680`.
- MCU commands are 64-byte, Adler32-protected ASCII frames. Display values are
  `1` 2D, `2` Half SBS, `3` Full SBS, and `4` 72 Hz Full SBS.
- The driver sends the SDK-enable command before display control and maintains
  the required 250 ms heartbeat while a display session is active.
- OV580 IMU reports expose independently scaled gyro and accelerometer fields;
  USB transfer and decoding were cross-checked against `ar-drivers-rs` and
  `android-sensor-probe`.

## Grawoow G530 / MetaVision M53 protocol notes

- The MCU is `1FF7:0FF4`; the OV580 IMU companion is `05A9:0F87` with interrupt
  endpoint `0x89`.
- MCU commands use the `AA BB` control protocol: `0x8007` queries display mode
  and `0x8008` switches between 2D and Full SBS.
- IMU offsets, scale factors, axes, and USB identities are independently
  present in `ar-drivers-rs` and `android-sensor-probe`.

## Rokid Air / Max protocol notes

- Both models use USB identity `04D2:162F`; the USB product string distinguishes Max, with Air as the fallback.
- IMU, magnetometer, keys, and proximity reports arrive passively on interrupt endpoint `0x82`.
- Display-mode vendor control transfers query and switch mirrored 2D, Full SBS 3D, high-refresh 2D, and high-refresh SBS 3D.
- The implementation follows the MIT-licensed `ar-drivers-rs` Rokid driver and its Android port in `android-sensor-probe`.
- **XREAL One Pro** (`3318:0436`, Gina)
