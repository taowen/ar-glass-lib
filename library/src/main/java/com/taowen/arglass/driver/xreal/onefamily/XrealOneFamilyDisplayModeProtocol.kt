package com.taowen.arglass.driver.xreal.onefamily

import com.taowen.arglass.DisplayMode

/** XREAL One-family DP RPC EDID values captured from Control My Glasses 1.1.0 on real hardware. */
internal object XrealOneFamilyDisplayModeProtocol {
    private const val EDID_3D_3840_1080_60HZ = 5
    private const val EDID_3D_3840_1080_72HZ = 6
    private const val EDID_3D_3840_1080_90HZ = 7
    private const val EDID_2D_1920_1080_90HZ = 9

    data class Command(val edid: Int, val inputMode: Int)

    fun decode(edid: Int): DisplayMode? = when (edid) {
        EDID_2D_1920_1080_90HZ -> DisplayMode.MIRROR_2D
        EDID_3D_3840_1080_60HZ, EDID_3D_3840_1080_72HZ, EDID_3D_3840_1080_90HZ -> DisplayMode.FULL_SBS_3D
        else -> null
    }

    fun encode(mode: DisplayMode): Command? = when (mode) {
        DisplayMode.MIRROR_2D -> Command(EDID_2D_1920_1080_90HZ, inputMode = 0)
        DisplayMode.FULL_SBS_3D -> Command(EDID_3D_3840_1080_60HZ, inputMode = 1)
        DisplayMode.HALF_SBS_3D, DisplayMode.HIGH_REFRESH_SBS_3D -> null
    }
}
