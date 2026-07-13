# ar-glass-lib

Android library and standalone check app for USB-C AR glasses. The first supported model is **XREAL Air 2 Ultra** (`3318:0426`, Flora).

## Capabilities

- Identify the glasses model from Android USB Host descriptors.
- Read versioned XREAL IMU reports (acceleration, angular velocity, magnetic field, temperature, and device timestamp).
- Query and switch 2D, Half SBS, Full SBS, and high-refresh SBS display modes.
- Inspect the resolution and refresh rate exposed by Android for the external display.
- Reuse the protocol implementation from Kotlin/Java or link the native `ar_glass` CMake target directly into another JNI library.

The `library` module is the reusable API. The `app` module is an independently installable, framework-Views diagnostic UI that waits for glasses, identifies them, and lets the user run each check explicitly. Display mode commands are never sent during passive detection.

## Build

Requires JDK 17, Android SDK 36, and an installed Android NDK/CMake toolchain.

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

## XREAL Air 2 Ultra protocol notes

- USB application identity: VID `0x3318`, PID `0x0426`.
- MCU: interface 0; IMU: interface 1.
- IMU uses CRC32-protected `0xaa` control frames and 64-byte versioned reports.
- Display modes use CRC32-protected `0xfd` MCU commands `0x07` (query) and `0x08` (set).
- Wire mode values: 1 = 2D, 2 = Half SBS, 3 = Full SBS, 4 = high-refresh SBS.

Protocol behavior was adapted from the open-source `android-sensor-probe` project and its XREAL protocol research. Hardware behavior still needs validation on each firmware version.
