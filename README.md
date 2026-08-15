# ar-glass-lib

Android library and standalone check app for USB-C AR glasses.

Supported models:

- **XREAL Air** (`3318:0424`, Air)
- **XREAL Air 2** (`3318:0428`, P55)
- **XREAL Air 2 Pro** (`3318:0432`, P55E)
- **XREAL Air 2 Ultra** (`3318:0426`, Flora)
- **XREAL XBX A01** (`3318:0440`, Helen)
- **XREAL XBX A01 Plus** (`3318:0442`, Helen Pro)
- **XREAL 1S** (`3318:043E`, GS)
- **XREAL One** (`3318:0438`, GF)
- **XREAL Light** (`0486:573C` MCU + `05A9:0680` OV580)
- **Grawoow G530 / MetaVision M53** (`1FF7:0FF4` MCU + `05A9:0F87` OV580)
- **RayNeo Air 3 / Air 3s / Air 3s Pro / Air 4 / Air 4 Pro**
  (`1BBB:AF50`, common open nine-axis HID IMU)
- **VITURE Luma** (`35CA:1131`, open Gen2 RAW IMU)
- **VITURE Luma Pro** (`35CA:1121` and `35CA:1141`, open Gen2 RAW IMU)
- **VITURE Luma Cyber** (`35CA:1151`, open Gen2 RAW IMU)
- **VITURE Pro 2** (`35CA:1301`, open Gen2 RAW IMU)
- **Rokid Air / Max** (`04D2:162B`, `162C`, `162D`, `162E`, `162F`, and `2180`; product string supplies the market name)
- **Rokid Max 2** (`04D2:2002`)
- **VITURE Beast** (`35CA:1201` and `35CA:1211`, Gen2 Native DOF)
- **LUCI displays** (`2C30:1030` and `2C30:1031`)
- **GOOVIS G3 family**: G3 Max (`880A:3501`), A1 (`880A:3502`),
  G3X (`880A:3503`), and G3X Pro (`880A:3506`)

## Capabilities

- Identify the glasses model from Android USB Host descriptors.
- Read versioned XREAL IMU reports (acceleration, angular velocity, magnetic field, temperature, and device timestamp).
- Declare whether a model supplies six- or nine-axis tracking and the calibration level of each sensor.
- Acquire factory and host calibration coefficients in the driver and expose them through `onImuCalibration`. Decoded SI samples stay raw; estimators decide whether to apply those coefficients. `parametersAppliedToSamples=false` marks that split. `parametersAppliedByDevice=true` is the narrower firmware-applied case.
- Preserve an exact discrete transport report in `ImuSample.rawReport` when the
  driver receives one, and preserve readable vendor calibration records in
  `ImuCalibrationData.rawPayloads`. Native continuous transports may leave
  `rawReport=null`, but still expose the same decoded SI vectors and calibration API.
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
product button. XREAL One, One Pro, and XREAL 1S currently set this flag to
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

`ArGlassesListener.onImuSample` receives the same interface for every model:
acceleration in m/s², angular velocity in rad/s, an optional magnetic vector in µT,
temperature, device timestamp, host receive timestamp, calibration state,
optional extended transport metadata, and optional exact `rawReport`. The device
timestamp remains the original glasses clock and is not replaced by Android
receive time; `hostTimestampNanos` is captured immediately after the transport
read so latency-sensitive consumers do not have to timestamp a delayed callback.
When a transport carries a hardware VSync time separately, it is exposed as
`transportMetadata.vsyncTimestampNanos` rather than conflated with either clock.
Transport decoding covers units and the runtime axis convention only.
`sample.calibration` states which corrections are already present in that sample.
`onImuCalibration` publishes factory or host coefficients plus zero or more
unmodified `rawPayloads` when the transport makes them available;
`parametersAppliedToSamples` says whether those coefficients have already been
applied, and `parametersAppliedByDevice=true` identifies opaque firmware-applied
coefficients. Consumers must not apply an already-applied set a second time.
Extended carriers can expose their timing/scaling fields through
`transportMetadata`.

XBX sessions stop the stream, fetch the complete factory JSON with IMU commands `0x14`/`0x15`, parse its accelerometer/gyroscope/magnetometer biases, per-sensor scale/skew matrices, `accel_q_gyro`/`gyro_q_mag` alignment, gyroscope gravity sensitivity, temperature-indexed gyroscope biases, and noise values, then restart streaming. The driver publishes those coefficients with `parametersAppliedToSamples=false` and leaves each decoded SI sample untouched so the selected pose estimator can reproduce official ownership. SI units and sensor-to-runtime axis mapping remain in the report decoder: XBX v2 `transform=1` publishes gyroscope/accelerometer as `[-source0,+source2,+source1]` and magnetic field as `[source1,source2,source0]`. Factory vectors and matrices remain in JSON component order; applying the report permutation to them again would double-transform the calibration. A captured XBX A01 factory blob carries the complete schema but uses identity scale/alignment and zero skew/gravity-sensitivity values; those neutral values must not be assumed for A01 Plus or other units. XREAL Air / Air 2 / Air 2 Pro / Air 2 Ultra use the same 0x14/0x15 factory JSON and the same publish-only contract.

RayNeo Air 3/4-family sessions query device information before streaming and honor
its `magnet_valid` flag. Command replies use report type `0xc8` and echo the
requested command at byte 8; command `0x3c` then supplies the 3×3 sensor
correction and gyroscope bias used by the vendor runtime. The recovered formula
is `accel'=M*accel` and `gyro'=M*(gyroRadPerSecond-bias)`. The driver
publishes that matrix and bias through `onImuCalibration` with
`parametersAppliedToSamples=false`, converts angular velocity from degrees/s to
rad/s, and maps package vectors `[x,y,z]` into the common runtime frame as
`[x,-z,y]`. A valid
finite 12-float `0x3c` reply is required before the Air stream starts; a
timeout or explicit `0xff` response fails the open with no raw-data fallback. In the
local Taurus 3.0 Pro firmware dated May 21 2025, `0x3c` is an identity matrix
plus a zero bias. Its 48-byte response contains no magnetic calibration. The
driver therefore
collects magnetic extrema while the user rotates the glasses around all three
axes, fits hard-iron bias plus a 3×3 soft-iron correction, and publishes a
second `MIXED` calibration event. Those host magnetic coefficients are also
left unapplied. Device ticks are 100 µs and are exported as
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
- The V2 report carries reconstructed IMU and hardware-VSync timestamps: its millisecond base and
  microsecond counter are combined separately with the 24-bit IMU age at payload offset 46 and
  24-bit VSync age at offset 52. The latter is published as `transportMetadata.vsyncTimestampNanos`.
- Startup reads the V2 long-packet calibration commands `0x3302` gyro temperature, `0x3303` IMU,
  `0x3304` magnetometer, `0x3305` accelerometer temperature, and `0x3306` magnetometer temperature;
  it validates their inner
  CRC-16/CCITT, and publishes gyro/accelerometer/magnetometer bias and 3x3 transforms, accelerometer
  scale, optional `q_mag_imu`, and the gyroscope temperature-drift table through `onImuCalibration`
  with `parametersAppliedToSamples=false`. The report decoder converts acceleration from g to m/s²
  and rotates package vectors into the common glasses runtime frame.
- Factory magnetic coefficients are published rather than applied. The device already supplies magnetic
  bias, a 3x3 correction/alignment matrix, and an optional temperature table, so the driver does
  not stack a generic persisted host ellipsoid fit on top of those factory corrections.
- `0x3140` queries Native/Bypass, `0x3142` queries 2D/3D, and `0x0142 [31|37]` selects 2D/3D.
- The Beast driver claims only its HID protocol interfaces and supports HID control-transfer fallback when an interface has no OUT endpoint.
- Beast's monocular camera is a separate `0C45:6368` USB device. The standalone check APK negotiates its 1920×1080@30 MJPEG stream on interface 1 / isochronous endpoint `0x81` directly through USB host APIs instead of Android Camera2.
- Android still requires the app's runtime `CAMERA` permission before granting USB permission to video-class devices; this permission gates USB access and does not mean Beast frames flow through Camera2.
- The native UVC path is adapted from `android-sensor-probe`, where this path was verified on Beast hardware. Its vendored LGPL-2.1-or-later libusb subset is built as a separate shared library and retains the upstream license/source files.

## VITURE family support notes

- The currently listed PID/model mapping is cross-checked against
  XRLinuxDriver and must still be verified against hardware or vendor metadata
  before exposing additional product IDs.
- Luma `1131`, Luma Pro `1121/1141`, Luma Cyber `1151`, and Pro 2 `1301` expose
  the open Gen2 `0301 [02 02]` RAW IMU stream with `7309` reports. They share
  the calibrated nine-axis V2 driver used by Beast. Their common V2 wire layout is confirmed, but
  their physical IMU mounting relative to the frame has not been checked model by model; the driver
  currently retains the Beast package-to-runtime transform for compatibility. SDK 2.4.0 accepts
  Luma Cyber as Gen2 but omits that name from its public raw-layout table, so its nine-axis aperture
  also remains pending a live-device comparison.
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
  (Air 3s Pro), `0x39` (Air 4), and `0x3a` (Air 4 Pro). The last two public
  names are established by the official Android `XRHelper`/OTA model table;
  Air 4 and Air 4 Pro currently share the same firmware image.
- The supported models and older Air generations share runtime USB identity
  `1BBB:AF50`; `SmartGlasses` is not a sufficient model discriminator. Opening
  is therefore a mandatory `0x00` board-ID probe. The returned session carries
  the resolved public model/capabilities, while a missing, older, or unknown
  board ID fails the open instead of retaining the provisional family model.
- The public `XrHidDeviceInfo` layout places `board_id` at byte 21 and
  `magnet_valid` at byte 51. It contains no CU field (byte 38 is the firmware
  build month); CU belongs to the DFU/OTA metadata path and is not used by the
  runtime driver or its persisted calibration key.
- The locally available Air 1s, Air Plus, Air 2, and Air 2s firmware payloads
  do not expose an unpacked, verifiable `99 65` sensor layout or the
  `99 c8`/`0x3c` calibration path found in the newer firmware. They are not a
  compatibility/fallback path: after the permission-gated `0x00` probe, the
  session rejects every board outside `0x35`, `0x36`, `0x37`, `0x39`, and
  `0x3a` without sending `0x3c` or `0x01`.
- The local GT/GT MAX Gemini firmware constructs the same `99 65`, 64-byte
  nine-axis carrier: acceleration starts at byte 4, gyroscope at 16,
  temperature at 28, magnetic X/Y at 32/36, device tick at 40, and magnetic Z
  at 52. Its normal-mode USB configuration has four interfaces: CDC control
  `0`, CDC data `1`, vendor HID `5`, and runtime DFU `6`. The HID interface has
  64-byte interrupt OUT `0x04` and IN `0x85` endpoints and carries the `66`/`99`
  protocol. The paired official runtime selects this HID interface, sends
  command `0x3c`, and consumes its 12-float transform/gyroscope-bias reply.
  It also sends `0x3e` for the complete -20°C..60°C, 81-point gyroscope
  bias table; replies are chunked into at most four XYZ float vectors per HID
  packet. The official 2.0.6 and 2.1.1 hosts copy those table floats unchanged;
  the active legacy path stores but does not consume them, so their firmware
  dimension cannot be proven farther from that path. The GT driver follows the
  observed no-conversion behavior, exposes all 81 points as the public rad/s
  table, and preserves every original `0x3e` report in `rawPayloads`, with
  `parametersAppliedToSamples=false`. Magnetometer hard/soft-iron
  fitting remains host-side and is also published rather than applied to samples.
- RayNeo transport selection is descriptor-strict rather than "first IN/OUT":
  Taurus uses HID interface `0` with interrupt OUT/IN `0x01/0x81`; Gemini uses
  HID interface `5` with `0x04/0x85`. Gemini CDC interfaces `0/1` and runtime
  DFU interface `6` are never claimed as IMU command channels.
- Gemini also contains its own magnetometer-aware DOF estimator. That internal
  fusion path is distinct from host raw-IMU processing, so the glasses' native
  hover does not depend on this library's host magnetic calibration.
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

## GOOVIS G3-family protocol notes

- D4 firmware recognizes VID `0x880A` with PID `0x3501` (internally G3),
  `0x3502` (A1), `0x3503` (G3X), and `0x3506` (G3XP). GOOVIS's current public
  product SKUs identify these as G3 Max, A1, G3X, and G3X Pro respectively.
- G3 Max uses dual 2560×1440 (2.5K) Micro-OLED panels. Its model metadata
  therefore declares 2560×1440 mono and 5120×1440 combined Full SBS layouts;
  G3X and G3X Pro use dual 1920×1080 panels. The G3 Max hardware supports up
  to 120 Hz, but this HID command does not select refresh rate, so the layout
  profiles retain the conservative 60 Hz timing until a physical EDID capture
  establishes the exact high-refresh input modes.
- One class-3 HID interface supplies interrupt IN and OUT endpoints. The driver
  sends 24-byte `AA 55 55 AA` output reports and accepts the normal 18-byte
  sensor input even though the HID descriptor declares 24 bytes. As with the
  XBX driver, a dedicated JNI/libusb session owns interface claiming, native
  command construction, interrupt transfers, IMU decoding, axis conversion,
  and device-timestamp accumulation; Kotlin only manages the model/session and
  forwards normalized samples.
- Display group `0` selects 2D with value `1` or side-by-side 3D with value `0`.
  Top/bottom is deliberately not exposed. This command changes content layout;
  it does not make the source select the profile's native resolution, refresh
  rate, or pixel clock. The host must independently select a matching EDID mode.
  No display-mode query or acknowledgement has been recovered, so setting a
  mode reports only that the complete USB output report was sent and querying
  the active profile returns unknown rather than a cached assumption.
- IMU group `1` starts/stops the six-axis stream. Samples are batch-averaged
  acceleration and angular velocity with byte 12 carrying the batch duration in
  milliseconds. The driver converts them to m/s² and rad/s and accumulates that
  duration as the device timestamp.
- No magnetometer, factory calibration, quaternion, or absolute device clock is
  exposed by the recovered protocol, so tracking support is declared as
  uncalibrated six-axis IMU.
- GOOVIS interrupt IN/OUT traffic is recorded by the same native USB diagnostics
  trace used by the XBX session and is included in diagnostics exports.

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
- Their 64-byte versioned IMU reports and initialization commands are
  cross-checked against both the SDK 3.1 `libnr_api.so` embedded schemas and
  the recovered firmware. Air, Air 2, Air 2 Pro, Air 2 Ultra, XBX A01 and XBX
  A01 Plus all select the SDK's `transform=1` report route: gyro and
  acceleration publish `[-source0,+source2,+source1]`, while magnetic publishes
  `[source1,source2,source0]` in microteslas. Model IDs choose schema/transport;
  they do not justify a second model-specific axis transform in ar-glass-lib.
- All common reports carry nine decoded axes. Factory JSON is published in the
  component order consumed by SDK 3.1 and remains unapplied to the raw SI
  samples. The report's byte 56 (v1) or 62 (v2) is the magnetic-observation
  freshness flag: the last magnetic vector remains visible on cached reports,
  but host calibration and fusion must consume only fresh observations.

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
- Helen's MCU schema names `0x1b`/`0x1c` read/write magnetic-state commands. An XBX A01
  capture returned the scalar state `9`, matching the official `nativeGetMagneticState(): Int`
  API rather than an additional calibration blob. The driver therefore does not query it as
  calibration data or send the corresponding setter.
- Version-2 reports decode gyro/accelerometer from little-endian signed fields.
  The magnetometer offset, denominator, and three unsigned samples are also
  little-endian; SDK 3.1 then emits source axes `[1, 2, 0]` using
  `100 * (raw - offset) / denominator`. The decoded magnetic vector is always
  exposed, including the protocol's cached value on reports without a new
  observation. `ImuSample.transportMetadata.magneticFieldFresh` distinguishes a
  fresh observation from that cache; fusion must not resubmit cached values.
  Like SDK 3.1, a zero divisor is allowed to produce IEEE Inf/NaN and is left for
  the typed-sample validity/range gate instead of causing the transport report to
  be dropped or silently replaced with zero/null.

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

## RayNeo nine-axis calibration notes

- Air-family runtime USB is `1BBB:AF50`; GT and GT Max runtime USB is
  `3941:AF50`. GT/GT Max board IDs are `0x40`/`0x41`. `3941:AF51` is DFU mode,
  not GT Max runtime mode.
- Device command `0x3c` supplies the 12-float accelerometer/gyroscope factory
  transform and gyroscope bias in rad/s. It does not supply a magnetometer
  hard/soft-iron calibration.
- Official legacy fusion maps accelerometer and gyroscope package vectors as
  `[x,-z,y]`. Its HID path does not submit the `99 65` magnetic fields to that
  fusion object, so applying the same rigid package rotation to magnetometer
  samples remains an explicitly documented inference pending hardware-axis
  verification; the exact vendor report is retained in `rawReport`.
- The standalone check APK exposes a RayNeo-specific magnetometer calibration
  Activity. Its implementation is clean-room code and does not package or load
  RayNeo's shared objects. It follows the behavior recovered from the official
  host path: online collection, a 2001-sample solve threshold, full-orientation
  coverage, a full cross-axis ellipsoid correction, and magnetic-disturbance
  rejection. Successful results are persisted per USB identity, board ID and
  CU and are applied by later driver sessions.

## XREAL One family protocol notes

- Runtime USB identities: One Pro `3318:0436` (Gina, official type 41), One `3318:0438` (GF, official type 47), and XREAL 1S `3318:043E` (GS, official type 71). Adjacent odd PIDs are bootloaders and are not opened as runtime devices.
- One-family 2D/3D switching is not the old XREAL USB MCU path. Control My
  Glasses 1.1.0 uses the USB-Ethernet DP RPC service at `169.254.2.1:52999`.
  Verified packets are `0x275e` get current EDID, `0x275f` set current EDID,
  `0x2821` get DP input mode, and `0x2822` set DP input mode.
  `EDID=5 + inputMode=1` switches XREAL One and XREAL 1S to `3840x1080@60`
  Full SBS 3D;
  `EDID=9 + inputMode=0` restores `1920x1080@90` 2D on One/One Pro; the GS
  firmware configuration identifies the corresponding XREAL 1S mono mode as
  `1920x1200@90`. These are the only One-family profiles exposed through
  `supportedDisplayProfiles`; additional EDID values are kept out of the public
  profile list until they are verified from hardware captures or open drivers.
- Do not treat Android seeing `3840x1080` as a complete 3D switch. That only
  proves the EDID side changed. X1 still needs `inputMode=1`; otherwise a Full
  SBS frame can be scanned as if it were `1920x1080`, showing only the center of
  the composed image in the glasses. The library now verifies both EDID and DP
  input mode, and will resend only `0x2822` when EDID is correct but input mode
  is not.
- One, One Pro, and XREAL 1S each provide their own EDID profile object and profile
  ID prefix. The TCP DP transport is shared; the user-visible profile list is
  not. Hardware captures currently verify EDID 5/inputMode 1 for XREAL One and
  XREAL 1S `3840x1080@60` Full SBS 3D; additional per-model EDID profiles should
  be added only after model-specific hardware captures or open-driver evidence.
- The standalone check APK includes a read-only "XREAL One EDID/input" activity.
  Users can first switch the glasses to a desired state with vendor tools or the
  glasses UI, for example `3840x1080@60` Full SBS 3D, then read the current
  `EDID` and `inputMode` values and send them to developers. Those captures are
  the intended path for adding model-specific EDID mappings that are not already
  verified.
- IMU is intentionally separate from Air/Flora/Helen HID code. It connects through the glasses' USB Ethernet link at `169.254.2.1:52998`.
- The JNI TCP reader treats `28 36` as notification command `0x2836` and
  `00 00 00 80` as its big-endian payload length. It therefore reassembles the
  complete 134-byte wire frame (6-byte envelope plus the firmware's 0x80-byte
  `NRImuSubmitExt` carrier), including acceleration, angular velocity,
  magnetometer, temperature, validity mask and all three timestamps. The driver
  also preserves the packed carrier tail: IMU/frame IDs, gyro/accelerometer/
  magnetometer numerators, output-numerator mask and group delay. The older
  XRLinuxDriver reader stopped at 84 bytes and discarded these fields.
- The public `rawReport` is that exact 134-byte TCP frame, and the host timestamp
  is captured by the native reader when a complete frame is removed from the
  TCP reassembly buffer. `data_mask & 0x4` is exposed as the carrier's magnetic
  observation/freshness signal.
- Recovered One-family firmware applies the per-sensor accelerometer, gyroscope
  and magnetometer factory matrices/biases before publishing `NRImuSubmitExt`.
  The coefficients themselves are device-owned and absent from the carrier, so
  `onImuCalibration` reports `DEVICE_FACTORY`, all three sensors as `FACTORY`,
  and `parametersAppliedByDevice=true`. The driver no longer adds a second host
  hard/soft-iron fit or suppresses magnetic samples while collecting one.
- Pilot `libnr_driver.so`'s `NRImuSubmitExt::HandleMessage` path
  (`0x34aa40..0x34ab64` in the recovered image) copies the packed carrier into
  the callback payload without sign changes or component permutation. The
  external-sensor protobuf conversion at `libnr_external_sensor.so+0x64c40`
  likewise preserves named x/y/z order. Firmware converter tables perform the
  device/protocol-specific raw swap before this boundary. Consequently the
  carrier's gyro, acceleration and magnetic x/y/z floats are published as-is;
  XRLinuxDriver's later `[-x,-z,-y]` mapping is not part of the official path
  and must not be reintroduced.
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
  the old Light layout is separate from the common 64-byte `transform=1`
  protocol. Its runtime mapping is `[x,-y,-z]`, gyro converts degrees/s to
  radians/s, and acceleration uses the firmware/reference value 9.81 m/s² per
  g. These rules were cross-checked against the recovered Light transport and
  `ar-drivers-rs`; XBX/Common or One-family transforms must not be copied here.
- Initialization stops streaming with `0x19/0`, reads the complete OV580
  factory stream through `0x14` then repeated `0x15`, publishes both the exact
  binary configuration and its embedded JSON, and restarts with `0x19/1`.
  Light is a six-axis source. Its accelerometer and gyroscope factory biases are
  exposed in the same `[x,-y,-z]` runtime frame, with
  `parametersAppliedToSamples=false`; decoded samples remain raw SI values.

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

## Rokid Max 2 protocol notes

- Max 2 is the distinct `04D2:2002` USB identity and does not use the older
  Air/Max passive HID report decoder.
- The driver follows the second-round hardware captures in
  [`xelsed/rokidmaxwmdapi`](https://github.com/xelsed/rokidmaxwmdapi), which
  found the single high-speed nine-axis stream on
  interface 2, endpoint `0x82`. Each transfer contains one or more 64-byte
  `0x11` samples: a packed 56-bit microsecond timestamp, acceleration at bytes
  `12..23`, gyroscope at `24..35`, and magnetometer at `36..47`. What the early
  reader called padding was later confirmed to contain the magnetic vector.
- The driver submits 4096-byte bulk reads and decodes every contained sample
  through the reusable C++ core plus its thin JNI adapter, matching the XBX
  native-decode/Kotlin-public-object split;
  it does not mix in or concurrently poll the older 512-byte control telemetry.
  Acceleration is normalized to m/s², angular velocity to rad/s, and magnetic
  field is exposed in µT.
- The referenced captures do not expose factory calibration coefficients, so
  samples advertise nine axes with calibration level `NONE`; the library does
  not invent factory calibration data.
- The source repository does not establish a safe, verified Max 2 USB 2D/3D
  switch command, so this driver deliberately claims only IMU capability.
- **XREAL One Pro** (`3318:0436`, Gina)
