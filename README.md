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
- **RayNeo Air 3 / Air 3s / Air 3s Pro / Air 4 / Air 4 Pro**
  (`1BBB:AF50`, common open nine-axis HID IMU)
- **VITURE Luma** (`35CA:1131`, open Gen2 RAW IMU)
- **VITURE Luma Pro** (`35CA:1121` and `35CA:1141`, open Gen2 RAW IMU)
- **VITURE Luma Cyber** (`35CA:1151`, open Gen2 RAW IMU)
- **Rokid glasses** (`04D2:162B`, `162C`, `162D`, `162E`, `162F`, `2002`, and `2180`; product string supplies the market name)
- **VITURE Beast** (`35CA:1201` and `35CA:1211`, Gen2 Native DOF)
- **LUCI displays** (`2C30:1030` and `2C30:1031`)

## Capabilities

- Identify the glasses model from Android USB Host descriptors.
- Read versioned XREAL IMU reports (acceleration, angular velocity, magnetic field, temperature, and device timestamp).
- Declare whether a model supplies six- or nine-axis tracking and the calibration level of each sensor.
- Keep factory calibration acquisition and application inside the driver while exposing the applied SI-unit parameters through `onImuCalibration` for diagnostics.
- Query and switch 2D, Half SBS, Full SBS, and high-refresh SBS display modes.
- List and switch glasses-native display profiles using common width, height, refresh-rate, and 2D/3D layout metadata while keeping each vendor's protocol value inside its driver.
- Expose capability metadata for host apps that need to correlate glasses models with Android external-display information.
- Reuse the protocol implementation from Kotlin/Java or link the native `ar_glass` CMake target directly into another JNI library.

The `library` module is the reusable API. The `app` module is an independently installable, framework-Views diagnostic UI that waits for glasses, identifies them, and lets the user run each check explicitly. Each check has its own Activity and implementation:

- `ImuCheckActivity`: opens only the IMU interface and validates its stream.
- `DisplayModeCheckActivity`: opens only the display-control interface and provides standalone **开启 3D** / **关闭 3D（恢复 2D）** controls. It selects the model's preferred supported 2D/3D modes while model-specific commands remain isolated in their drivers.
- `DisplayProfileSwitchActivity`: lists every verified glasses-native display profile declared by the current driver, shows `width × height @ refresh-rate` plus the 2D/3D layout, and switches by asking the driver to translate that common profile into its own protocol value.
- `CameraCheckActivity`: appears only for VITURE Beast and previews the separately enumerated `0C45:6368` camera through direct UVC/libusb MJPEG capture.
- `XrealEyeCameraCheckActivity`: appears for the XREAL One family and tests the open One + Eye USB Ethernet TCP/HEVC path (`169.254.2.1:52995`) without any vendor SO. XREAL Eye is not treated as UVC.

The launcher Activity only identifies the glasses and navigates to a selected check. Display mode commands are never sent during passive detection.

The standalone APK also has a **导出诊断 zip** action. There is only one export
flow: the app writes a multi-file diagnostic archive into its cache and
immediately opens Android's share sheet, matching Arctrl's long-press
diagnostic sharing behavior. Raw protocol captures are kept as separate binary
files instead of being mixed into logcat:

- `diagnostics.txt`: generated summary with Android build information, visible
  USB devices and interfaces, recognized glasses, Android display modes, XREAL
  One-family USB Ethernet readiness, and best-effort current EDID/inputMode.
- `events.txt`: app-generated status, permission, session, display-mode, crash,
  and export events. This is not logcat.
- `usb-transfers.bin`: append-only USB permission and raw control/bulk/interrupt
  transfer records, including VID/PID, endpoint or control parameters, result
  length, and payload bytes. Record magic is `ARUS` and format version is 1;
  operation 1 is device-to-host, 2 is host-to-device, 3 is a permission request,
  and 4 is its result.
- `xreal-one-dp-rpc.bin`: append-only XREAL One-family TCP DP RPC records for
  `169.254.2.1:52999`, including connect attempts, set/get EDID/inputMode raw
  request frames, raw response frames, and transport errors. Record magic is
  `ARDP` and format version is 1.
- `crashes.bin`: append-only uncaught exception records with `ARCR` magic,
  format version 1, timestamp, thread name, and stack trace bytes.
- `formats.txt`: exact binary record layout for the files above.

Model code is isolated below `library/.../driver/<vendor>/<model>/`. A driver owns its USB identity, interfaces, wire protocol, IMU decoder, and display-mode behavior. `GlassesDriverRegistry` is the only shared routing table; adding a model does not add protocol branches to another model's session.
For XREAL display profiles, each concrete model declares its own
`supportedDisplayProfiles` and profile IDs. Shared JNI transports are allowed,
but model-level mode tables, preferred modes, payload widths, EDID mappings,
and user-visible profile lists must stay in the concrete model's driver object
so one XREAL product can diverge without changing another product.
Each `GlassesModel` explicitly declares `preferred2dDisplayProfile`,
`preferred3dDisplayProfile`, and `showInArctrlDisplayModeToggle`. The last field is
only Arctrl home-screen UI policy for the 2D/3D toggle. It is intentionally
separate from `DISPLAY_MODE` and `supportedDisplayProfiles`, so the standalone
diagnostic APK can keep developer mode checks even when Arctrl should hide the
product button. XREAL One, One Pro, and One S currently set this flag to
`false`.

When adding or correcting a glasses protocol, cross-check every available
reference and prefer direct hardware captures or this repository's previously
validated implementation when references disagree. XRLinuxDriver is a broad
Linux/OpenXR-oriented reference, but it is not the source of truth for every
model and does not cover the XBX/Helen display-mode behavior verified in this
project. Keep model-specific evidence with the model-specific driver:

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

The two primary open-source references are also checked in as reference-only
Git submodules for local cross-checking:

```bash
git submodule update --init --recursive references/open-source/XRLinuxDriver references/open-source/ar-drivers-rs
```

- `references/open-source/XRLinuxDriver`
- `references/open-source/ar-drivers-rs`

These submodules are not build inputs. Do not add their directories to Gradle
source sets, CMake `add_subdirectory`, compiler include paths, JNI sources, or
runtime asset packaging. When behavior is copied into this library, reimplement
the protocol in `library/src/main/.../driver/...` with this repository's JNI and
diagnostics boundaries instead of importing or compiling reference files.

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
val in3d = session.isIn3d()
session.switchTo3d()
session.switchTo2d()
val profile = connected.model.supportedDisplayProfiles.firstOrNull {
    it.width == 1920 && it.height == 1080 && it.refreshRateHz == 120
}
if (profile != null) session.setDisplayProfile(profile)
```

`ArGlassesListener.onImuSample` receives SI-unit samples. The device timestamp remains the original glasses clock and is not replaced by Android receive time; `hostTimestampNanos` is captured immediately after the USB read so latency-sensitive consumers do not have to timestamp a delayed callback. `calibration` states which corrections the driver has already applied. `onImuCalibration` exposes those applied parameters for inspection, but consumers must not apply them a second time.

XBX sessions stop the stream, fetch the complete factory JSON with IMU commands `0x14`/`0x15`, parse its accelerometer bias, factory and temperature-indexed gyroscope biases, magnetometer bias, and noise values, then restart streaming. Selection of the nearest temperature point, bias subtraction, SI units, and sensor-to-runtime axis mapping all remain inside the XBX driver.

RayNeo Air 3/4-family sessions query device information before streaming and honor
its `magnet_valid` flag. Command replies use report type `0xc8` and echo the
requested command at byte 8; command `0x3c` then supplies the 3×3 sensor
correction and accelerometer offset used by the vendor runtime. The driver
applies these parameters, converts angular velocity from degrees/s to rad/s,
and exposes the applied matrix and offset through `onImuCalibration`. In the
local Taurus 3.0 Pro firmware dated May 21 2025, `0x3c` is an identity matrix
plus a zero offset. Its 48-byte response contains no magnetic calibration. The
driver therefore
collects magnetic extrema while the user rotates the glasses around all three
axes, fits hard-iron bias plus diagonal soft-iron scale, publishes a second
`MIXED` calibration event, and marks magnetic samples `HOST_ESTIMATED` only
after that fit succeeds. Device ticks are 100 µs and are exported as
nanoseconds without replacing the separate host receive timestamp.

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
- Beast's monocular camera is a separate `0C45:6368` USB device. The standalone check APK negotiates its 1920×1080@30 MJPEG stream on interface 1 / isochronous endpoint `0x81` directly through USB host APIs instead of Android Camera2.
- Android still requires the app's runtime `CAMERA` permission before granting USB permission to video-class devices; this permission gates USB access and does not mean Beast frames flow through Camera2.
- The native UVC path is adapted from `android-sensor-probe`, where this path was verified on Beast hardware. Its vendored LGPL-2.1-or-later libusb subset is built as a separate shared library and retains the upstream license/source files.

## VITURE family support notes

- The currently listed PID/model mapping is cross-checked against
  XRLinuxDriver and must still be verified against hardware or vendor metadata
  before exposing additional product IDs.
- Luma `1131`, Luma Pro `1121/1141`, and Luma Cyber `1151` expose the open
  Gen2 `0301 [02 02]` RAW IMU stream with `7309` reports. They currently
  advertise IMU support only in the standalone APK.
- XRLinuxDriver performs their 2D/3D switching through VITURE's proprietary
  `libglasses.so`. The Android SDK license prohibits unauthorized copying,
  distribution, and use, so ar-glass-lib neither bundles it nor falsely exposes
  that SDK-only display control.
- One/Lite/Pro expose SDK pose callbacks but do not yet have a cross-verified
  open raw-IMU implementation. Luma Ultra uses the proprietary Carina path.

## RayNeo Air 3/4-family protocol notes

- XRLinuxDriver identifies the device as `1BBB:AF50`; its full display and pose
  implementation calls the proprietary `libRayNeoXRMiniSDK.so`.
- The open backend sends HID commands `66 00`, `66 3c`, and `66 01`. Device-info
  and calibration replies are `99 c8` command ACKs; `99 65` is the direct
  64-byte sensor report containing acceleration, angular velocity, magnetic
  field, temperature, and timestamp. It therefore advertises IMU support only
  in the standalone APK.
- The Taurus 3.0 Pro firmware scales accelerometer samples to m/s², gyroscope
  samples to degrees/s, leaves magnetometer samples unscaled, and uses 100 µs
  device ticks. It also performs an internal stationary gyroscope-bias estimate;
  there is no corresponding USB magnetic-calibration command in this firmware.
- Local Taurus 2.0, 3.0, 3.0 Pro, and 4.0 firmware all contain this same
  sensor report, ACK layout, scaling, and 48-byte `0x3c` identity/zero response.
  Their device-info board IDs are `0x35` (Air 3), `0x36` (Air 3s), `0x37`
  (Air 3s Pro), and `0x39`/`0x3a` (Air 4 family). Because Air 4 and Air 4 Pro
  share one firmware, that firmware does not establish which of the last two
  board IDs is which public model.
- All five models share runtime USB identity `1BBB:AF50`. Before opening the
  device, Android descriptors identify only an Air 3 group, Air 4 group, or
  generic family name. The driver reports the more precise board-ID result
  after the device-info ACK arrives.
- The locally available Air 1s, Air Plus, Air 2, and Air 2s firmware payloads
  do not expose an unpacked, verifiable `99 65` sensor layout or the
  `99 c8`/`0x3c` calibration path found in the newer firmware, so this library
  does not advertise a raw-IMU driver for them.
- The local GT/GT MAX package contains an internal DOF implementation but no
  verified host USB raw-IMU or calibration protocol, so no GT driver is
  advertised either.
- `AirSDK XR Unity v1.0.3` confirms the older client-side command numbers
  `0x00` (device info), `0x01`/`0x02` (sensor on/off), `0x03` (device-side
  gyroscope calibration), and a `0x65` sensor callback. It contains no VID/PID
  or model table: the AAR delegates USB enumeration and transport to the
  separately installed `com.tcl.xrmanager.main` service. It exposes no factory
  calibration data and its 2022 sensor-field layout differs from the Taurus
  2.0+ firmware, so it is not sufficient evidence for an older-model raw-IMU
  driver.
- XRLinuxDriver ships the RayNeo SDK only for x86_64, so it cannot be used in
  this Android ARM64 library. 2D/3D is intentionally not advertised until that
  SDK behavior has an open protocol implementation.

## LUCI protocol notes

- USB identities: VID `0x2C30`, PID `0x1030` or `0x1031`.
- 2D/3D switching uses a 64-byte HID Feature Report (`SET_REPORT`, value `0x0302`).
- The LUCI driver exposes display-mode checks. It does not advertise IMU because this protocol does not provide a verified LUCI sensor stream.

## XREAL Air 2 Ultra protocol notes

- USB application identity: VID `0x3318`, PID `0x0426`.
- MCU: interface 0; IMU: interface 2, matching XRLinuxDriver's
  `xrealInterfaceLibrary` product table.
- IMU uses CRC32-protected `0xaa` control frames and 64-byte versioned reports.
- Display modes use CRC32-protected `0xfd` MCU commands `0x07` (query) and `0x08` (set).
- There is no public cross-model display-mode enum. Public 2D/3D control is
  `isIn3d()`, `switchTo3d()`, and `switchTo2d()`. Model-specific drivers
  translate their private mode, EDID, or inputMode values to declared display
  profiles.

Protocol behavior was adapted from the open-source `android-sensor-probe` project and its XREAL protocol research. Hardware behavior still needs validation on each firmware version.

For Air 2 Ultra/Flora, the ARLauncher-compatible preferred modes are `10`
(1920x1080@90 2D), `4` (3840x1080@72 3D), and `9`
(3840x1080@90 3D). `supportedDisplayProfiles` exposes the cross-checked
native combinations: 1920x1080 2D at 60/72/90/120 Hz and 3840x1080 Full SBS
3D at 60/72/90 Hz. 3840x1080@120 SBS is intentionally not exposed: ARLauncher
and `ar-drivers-rs` do not list it, and XRLinuxDriver maps the 120 Hz SBS slot
down to 90 Hz SBS. Flora's official mode table has no Half SBS entry. Unlike
Helen, Flora encodes the command `0x08` mode payload as one byte; this matches
the previously hardware-validated implementation and `ar-drivers-rs`.
The public `switchTo3d()` / `switchTo2d()` path follows XRLinuxDriver's current
mode mapping instead of forcing one fixed preferred mode: 60 Hz 2D maps to
60 Hz SBS, 72 Hz 2D maps to 72 Hz SBS, 90 Hz 2D maps to 90 Hz SBS, and 120 Hz
2D maps down to 90 Hz SBS. Returning to 2D applies the inverse mapping.

## XREAL Air family protocol notes

- Air `3318:0424`, Air 2 `3318:0428`, and Air 2 Pro `3318:0432` use MCU interface 4 and IMU interface 3.
- All three use the one-byte `0x07` / `0x08` display-mode protocol and expose 2D, Full SBS, Half SBS, and 90 Hz SBS modes.
- Their current cross-checked mode values match, but each model has its own
  profile object and profile ID prefix (`xreal_air_*`, `xreal_air_2_*`,
  `xreal_air_2_pro_*`) instead of sharing one public profile table.
- Their 64-byte versioned IMU reports and initialization commands are cross-checked against both `ar-drivers-rs` and XRLinuxDriver's `xrealInterfaceLibrary`.

## XREAL XBX protocol notes

- XBX A01 uses `3318:0440`; XBX A01 Plus uses `3318:0442`.
- Both use the Helen transport but remain separately registered models.
- XBX/Helen behavior is based on this repository's hardware captures, release
  history, and official SDK/probe evidence. Do not implement XBX by inheriting
  XRLinuxDriver's generic XREAL display-mode table; that reference does not
  cover the ARctrl-validated XBX/Helen Full SBS 3D path.
- The driver claims MCU interface 0 first and sends `0x26`, `0x57`, `0x12(1)`, `0x02(1)`, `0x34`, `0x35`.
- It then performs the required `0x31 / "3.1.1"` SDK handshake and two initial heartbeats before claiming IMU interface 1.
- A 100 ms MCU heartbeat remains active for the session lifetime.
- IMU initialization stops the old stream, reads the complete calibration blob, syncs, and starts the versioned 64-byte report stream.
- Version-2 reports decode gyro/accelerometer from little-endian signed fields,
  while magnetometer multiplier/divisor are big-endian and magnetic samples use
  XREAL's high-byte sign-bit transform. Invalid magnetic divisors are exposed as
  `ImuSample.magneticField == null` instead of a misleading zero vector.

- Display query/switch uses the same MCU `0x07` / `0x08` commands after completing the Helen bootstrap.
- Helen does not use the generic display-mode wire values. XBX/Helen currently
  exposes no Half SBS mode. Mode values in command `0x08` are always encoded as
  four-byte little-endian integers, matching the official `int EGlassMode` ABI.
  Some Android hosts enumerate a valid Full SBS 3D output as 640x480; that host
  display enumeration must not be used as the command success criterion.
- XBX A01 (`3318:0440`) was calibrated on OnePlus 13 by switching each exposed
  mode, auto-confirming Android 16's projection prompt, and checking the active
  external HDMI mode. XBX A01 Plus (`3318:0442`) uses the same table:
  - `mode=1`: 1920x1080@60Hz 2D. This remains the default 2D restore command.
  - `mode=10`: 1920x1080@90Hz 2D.
  - `mode=11`: 1920x1080@120Hz 2D, single-refresh EDID.
  - `mode=17`: 1920x1080@120Hz 2D, multi-refresh EDID exposing
    120/90/60Hz after reconnect.
  - `mode=3`: 3840x1080@60Hz Full SBS 3D. This remains the default 3D toggle.
  - `mode=4`: 3840x1080@72Hz Full SBS 3D.
  - `mode=5`, `mode=9`, and `mode=2` were not accepted by this A01 during the
    calibration run, so they are not exposed by the calibrated
    `supportedDisplayProfiles` tables.
- A01 and A01 Plus each still provide their own `supportedDisplayProfiles`
  object and profile ID prefix. Keep the per-model protocol objects separate so
  future hardware-specific calibration can diverge without cross-model fallout.

All XREAL USB interfaces and transfers are owned by `XrealNativeUsbSession` in
JNI/libusb. Kotlin retains Android device enumeration/permission only; XREAL
One-family USB-Ethernet DP control and IMU frame decode still use JNI for their
native wire readers. Kotlin owns Android Network selection, and the One + Eye
RGB camera backend uses Kotlin sockets for the 52995/52999 TCP/HEVC transport.
Native transactions perform framing, request-ID matching, bounded
asynchronous-event skipping, and write the shared binary diagnostics stream
where the transport is native-backed.

## XREAL One family protocol notes

- Runtime USB identities: One Pro `3318:0436` (Gina, official type 41), One `3318:0438` (GF, official type 47), and One S `3318:043E` (GS, official type 71). Adjacent odd PIDs are bootloaders and are not opened as runtime devices.
- One-family 2D/3D switching is not the old XREAL USB MCU path. Control My
  Glasses 1.1.0 uses the USB-Ethernet DP RPC service at `169.254.2.1:52999`.
  Verified packets are `0x275e` get current EDID, `0x275f` set current EDID,
  `0x2821` get DP input mode, and `0x2822` set DP input mode.
  `EDID=5 + inputMode=1` switches XREAL One and One S to `3840x1080@60`
  Full SBS 3D;
  `EDID=9 + inputMode=0` restores
  `1920x1080@90` 2D. These are the only One-family profiles exposed through
  `supportedDisplayProfiles`; additional EDID values are kept out of the public
  profile list until they are verified from hardware captures or open drivers.
- Do not treat Android seeing `3840x1080` as a complete 3D switch. That only
  proves the EDID side changed. X1 still needs `inputMode=1`; otherwise a Full
  SBS frame can be scanned as if it were `1920x1080`, showing only the center of
  the composed image in the glasses. The library now verifies both EDID and DP
  input mode, and will resend only `0x2822` when EDID is correct but input mode
  is not.
- One, One Pro, and One S each provide their own EDID profile object and profile
  ID prefix. The TCP DP transport is shared; the user-visible profile list is
  not. Hardware captures currently verify EDID 5/inputMode 1 for XREAL One and
  One S `3840x1080@60` Full SBS 3D; additional per-model EDID profiles should
  be added only after model-specific hardware captures or open-driver evidence.
- The standalone check APK includes a read-only "XREAL One EDID/input" activity.
  Users can first switch the glasses to a desired state with vendor tools or the
  glasses UI, for example `3840x1080@60` Full SBS 3D, then read the current
  `EDID` and `inputMode` values and send them to developers. Those captures are
  the intended path for adding model-specific EDID mappings that are not already
  verified.
- IMU is intentionally separate from Air/Flora/Helen HID code. It connects through the glasses' USB Ethernet link at `169.254.2.1:52998`.
- The JNI TCP reader follows XRLinuxDriver's vendored `xreal_one_driver`: find
  header `28 36 00 00 00 80`, require marker `00 40 1F 00 00 40`, reassemble
  84-byte frames, and expose acceleration, angular velocity, and the device
  timestamp in Android-oriented coordinates.
- XRLinuxDriver notes that One/One Pro/1S require latest firmware and glasses
  stabilizer/anchor features disabled. Those prerequisites apply to the IMU
  path; they do not by themselves define an open 2D/3D switching protocol.
- ARLauncher exposes RGB-camera frames through `StartRGBCameraDataCapture`, `TryAcquireLatestImage`, and `TryGetRGBCameraDataPlane`, with `RGB_888` and `YUV_420_888` formats. Its native path is `SessionManager` -> `NRRGBCameraWrapper` -> the `NRRgbCamera*` plugin ABI. Extraction of the bundled Gina firmware confirms `rgb_camera_enable` and the `uvc_bulk_15` composite-mode string, but live XREAL One + Eye testing on 2026-07-24 showed the default `3318:0438` main device reports `hasVideoCapture=false` and exposes no VideoControl/VideoStreaming interface. The supported open implementation therefore uses the captured USB Ethernet TCP/HEVC path and does not link or load ARLauncher SO files.

## XREAL One + Eye RGB camera protocol notes

这部分来自 ARLauncher 真机流量抓包、logcat 和 `libnr_api.so` 字符串交叉验证，
没有依赖官方 SO：

- One + Eye 的 RGB 摄像头不是 Android Camera2，也不是标准 UVC。实测
  `dumpsys media.camera` 为 0 个 camera；插入眼镜后 Android 侧出现 USB
  Ethernet/NCM 网络，手机地址是 `169.254.2.10/24`，眼镜地址是
  `169.254.2.1`。
- 控制通道复用 One-family DP/状态 TCP 服务 `169.254.2.1:52999`。XREAL
  frame 格式为 2 字节 command、4 字节大端 payload 长度、随后 payload。
  RGB start command 是 `0x2781`，stop command 是 `0x2782`。
- RGB start 请求 payload 为 `80 00 seq_hi seq_lo 1a 00`，成功 ACK 为
  `00 00 seq_hi seq_lo 22 00`。stop 请求和 ACK 结构相同，只是 command
  换成 `0x2782`。
- 视频流在 `169.254.2.1:52995`。ARLauncher 的顺序是先连接 52995，再在
  52999 发送 `0x2781` start。视频方向的 frame command 是 `0x2785`，
  payload 中前面约 97 字节是 XREAL 帧元数据，真正的 Annex-B HEVC 从
  后续 start code 开始。
- 2026-07-24 的 Pocket FIT 实测中，物理重插后 One 可能短暂枚举后立刻
  disconnect，并且 `eth0` 不出现。先进入 ARLauncher 的 AR 空间后，
  官方 service 会让 `3318:0438`、`eth0 169.254.2.10/24`、52999/52998
  稳定保持；随后本库的 open TCP/HEVC 后端可以在不加载官方 SO 的情况下
  自己连接 52995/52999 拉流。
- 实测流为 `video/hevc`，`1280x720`，`30fps`。第一帧包含 VPS/SPS/PPS
  NAL，后续帧主要是 HEVC VCL NAL。ARLauncher native 日志中也能看到
  `androiddec config: 0 -> 1280`、`1 -> 720`、`3 -> 30` 和
  `Codec format 1280x720 video/hevc`。
- `XrealOneEyeCameraSession` 实现了这个开源 TCP/HEVC 后端：通过
  `ConnectivityManager` 找到 link address 为 `169.254.2.10` 的 Android
  Network，连接 52995/52999，发送 start/stop，并返回同时包含 raw XREAL
  payload、metadata、HEVC bytes 和 NAL 类型的 `XrealOneEyeHevcFrame`。本库
  不把 XREAL Eye 当作 UVC 设备处理。

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
