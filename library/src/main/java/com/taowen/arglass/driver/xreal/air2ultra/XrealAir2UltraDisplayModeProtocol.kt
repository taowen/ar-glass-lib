package com.taowen.arglass.driver.xreal.air2ultra

import com.taowen.arglass.DisplayMode

/** Flora display modes used by ARLauncher/XREAL SDK 3.1.0. */
internal object XrealAir2UltraDisplayModeProtocol {
    private const val MODE_2D_60HZ = 1
    const val MODE_3D_120HZ = 2
    private const val MODE_3D_60HZ = 3
    const val MODE_3D_72HZ = 4
    private const val MODE_2D_72HZ = 5
    private const val MODE_3D_90HZ = 9
    const val MODE_2D_90HZ = 10
    private const val MODE_2D_120HZ = 11

    fun decode(value: Int): DisplayMode? = when (value) {
        MODE_2D_60HZ, MODE_2D_72HZ, MODE_2D_90HZ, MODE_2D_120HZ -> DisplayMode.MIRROR_2D
        MODE_3D_120HZ -> DisplayMode.HIGH_REFRESH_SBS_3D
        MODE_3D_60HZ, MODE_3D_72HZ, MODE_3D_90HZ -> DisplayMode.FULL_SBS_3D
        else -> null
    }

    fun encode(mode: DisplayMode): Int = when (mode) {
        DisplayMode.MIRROR_2D -> MODE_2D_90HZ
        DisplayMode.FULL_SBS_3D -> MODE_3D_72HZ
        DisplayMode.HIGH_REFRESH_SBS_3D -> MODE_3D_120HZ
        DisplayMode.HALF_SBS_3D -> error("Air 2 Ultra/Flora has no Half SBS mode in the XREAL mode table")
    }
}
