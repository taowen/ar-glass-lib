package com.taowen.arglass.driver.xreal.onefamily

import com.taowen.arglass.DisplayMode

/** XREAL MCU display-mode values and the refresh-preserving SBS mapping. */
internal object XrealOneFamilyDisplayModeProtocol {
    private const val MODE_2D_60HZ = 1
    private const val MODE_3D_60HZ = 3
    private const val MODE_3D_72HZ = 4
    private const val MODE_2D_72HZ = 5
    private const val MODE_3D_1920_60HZ = 8
    private const val MODE_3D_90HZ = 9
    private const val MODE_2D_90HZ = 10
    private const val MODE_2D_120HZ = 11

    fun decode(value: Int): DisplayMode? = when (value) {
        MODE_2D_60HZ, MODE_2D_72HZ, MODE_2D_90HZ, MODE_2D_120HZ -> DisplayMode.MIRROR_2D
        MODE_3D_60HZ, MODE_3D_72HZ, MODE_3D_90HZ -> DisplayMode.FULL_SBS_3D
        MODE_3D_1920_60HZ -> DisplayMode.HALF_SBS_3D
        else -> null
    }

    fun encode(mode: DisplayMode, currentValue: Int): Int = when (mode) {
        DisplayMode.MIRROR_2D -> when (currentValue) {
            MODE_3D_60HZ, MODE_3D_1920_60HZ -> MODE_2D_60HZ
            MODE_3D_72HZ -> MODE_2D_72HZ
            MODE_3D_90HZ -> MODE_2D_90HZ
            else -> currentValue.takeIf(::is2d) ?: MODE_2D_60HZ
        }
        DisplayMode.FULL_SBS_3D -> when (currentValue) {
            MODE_2D_60HZ -> MODE_3D_60HZ
            MODE_2D_72HZ -> MODE_3D_72HZ
            MODE_2D_90HZ, MODE_2D_120HZ -> MODE_3D_90HZ
            else -> currentValue.takeIf(::isFullSbs) ?: MODE_3D_60HZ
        }
        DisplayMode.HIGH_REFRESH_SBS_3D -> MODE_3D_90HZ
        DisplayMode.HALF_SBS_3D -> MODE_3D_1920_60HZ
    }

    private fun is2d(value: Int) = value == MODE_2D_60HZ || value == MODE_2D_72HZ ||
        value == MODE_2D_90HZ || value == MODE_2D_120HZ

    private fun isFullSbs(value: Int) = value == MODE_3D_60HZ || value == MODE_3D_72HZ ||
        value == MODE_3D_90HZ
}
