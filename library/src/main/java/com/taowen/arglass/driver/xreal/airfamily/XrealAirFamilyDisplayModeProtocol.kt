package com.taowen.arglass.driver.xreal.airfamily

import com.taowen.arglass.DisplayMode

/** Display modes shared by the original Air, Air 2, and Air 2 Pro MCU protocol. */
internal object XrealAirFamilyDisplayModeProtocol {
    private const val MODE_2D_60HZ = 1
    private const val MODE_3D_60HZ = 3
    private const val MODE_3D_72HZ = 4
    private const val MODE_2D_72HZ = 5
    private const val MODE_HALF_SBS_60HZ = 8
    private const val MODE_3D_90HZ = 9
    private const val MODE_2D_90HZ = 10
    private const val MODE_2D_120HZ = 11

    fun decode(value: Int): DisplayMode? = when (value) {
        MODE_2D_60HZ, MODE_2D_72HZ, MODE_2D_90HZ, MODE_2D_120HZ -> DisplayMode.MIRROR_2D
        MODE_HALF_SBS_60HZ -> DisplayMode.HALF_SBS_3D
        MODE_3D_60HZ, MODE_3D_72HZ, MODE_3D_90HZ -> DisplayMode.FULL_SBS_3D
        else -> null
    }

    fun encode(mode: DisplayMode): Int = when (mode) {
        DisplayMode.MIRROR_2D -> MODE_2D_60HZ
        DisplayMode.FULL_SBS_3D -> MODE_3D_60HZ
        DisplayMode.HALF_SBS_3D -> MODE_HALF_SBS_60HZ
        DisplayMode.HIGH_REFRESH_SBS_3D -> MODE_3D_90HZ
    }
}
