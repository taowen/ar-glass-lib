package com.taowen.arglass.driver.xreal.xbxa01plus

import com.taowen.arglass.DisplayMode
import com.taowen.arglass.GlassesDisplayLayout
import com.taowen.arglass.GlassesDisplayProfile
import com.taowen.arglass.driver.xreal.XrealMcuDisplayModeProtocol
import com.taowen.arglass.driver.xreal.XrealMcuDisplayProfileEntry
import com.taowen.arglass.driver.xreal.xrealMcuDisplayProfile

internal object XrealXbxA01PlusDisplayModeProtocol : XrealMcuDisplayModeProtocol {
    private const val MODE_2D_60HZ = 1
    private const val MODE_3D_120HZ = 2
    private const val MODE_3D_60HZ = 3
    private const val MODE_3D_72HZ = 4
    private const val MODE_2D_72HZ = 5
    private const val MODE_3D_90HZ = 9
    private const val MODE_2D_90HZ = 10
    private const val MODE_2D_120HZ = 11

    override val queryPayloadBytes = 4
    override val setPayloadBytes = 4

    private val profileTable = listOf(
        profile(MODE_2D_60HZ, 1920, 1080, 60, GlassesDisplayLayout.MONO_2D, DisplayMode.MIRROR_2D),
        profile(MODE_3D_120HZ, 3840, 1080, 120, GlassesDisplayLayout.FULL_SBS_3D, DisplayMode.HIGH_REFRESH_SBS_3D),
        profile(MODE_3D_60HZ, 3840, 1080, 60, GlassesDisplayLayout.FULL_SBS_3D, DisplayMode.FULL_SBS_3D),
        profile(MODE_3D_72HZ, 3840, 1080, 72, GlassesDisplayLayout.FULL_SBS_3D, DisplayMode.FULL_SBS_3D),
        profile(MODE_2D_72HZ, 1920, 1080, 72, GlassesDisplayLayout.MONO_2D, DisplayMode.MIRROR_2D),
        profile(MODE_3D_90HZ, 3840, 1080, 90, GlassesDisplayLayout.FULL_SBS_3D, DisplayMode.HIGH_REFRESH_SBS_3D),
        profile(MODE_2D_90HZ, 1920, 1080, 90, GlassesDisplayLayout.MONO_2D, DisplayMode.MIRROR_2D),
        profile(MODE_2D_120HZ, 1920, 1080, 120, GlassesDisplayLayout.MONO_2D, DisplayMode.MIRROR_2D),
    )

    override val profiles: List<GlassesDisplayProfile> = profileTable.map(XrealMcuDisplayProfileEntry::profile)

    override fun decode(value: Int): DisplayMode? = decodeProfile(value)?.compatibilityMode

    override fun decodeProfile(value: Int): GlassesDisplayProfile? =
        profileTable.firstOrNull { it.protocolValue == value }?.profile

    override fun encode(mode: DisplayMode): Int = when (mode) {
        DisplayMode.MIRROR_2D -> MODE_2D_90HZ
        DisplayMode.HALF_SBS_3D -> error("XBX/Helen has no Half SBS mode in the 1.3.3-validated mode table")
        DisplayMode.FULL_SBS_3D -> MODE_3D_60HZ
        DisplayMode.HIGH_REFRESH_SBS_3D -> MODE_3D_120HZ
    }

    override fun encodeProfile(profile: GlassesDisplayProfile): Int? =
        profileTable.firstOrNull { it.profile.id == profile.id }?.protocolValue

    override fun acceptsSetStatus(value: Int, status: Int): Boolean = status == 0

    private fun profile(
        protocolValue: Int,
        width: Int,
        height: Int,
        refreshRateHz: Int,
        layout: GlassesDisplayLayout,
        compatibilityMode: DisplayMode,
    ) = xrealMcuDisplayProfile(
        profileIdPrefix = "xreal_xbx_a01_plus_mode_",
        protocolValue = protocolValue,
        width = width,
        height = height,
        refreshRateHz = refreshRateHz,
        layout = layout,
        compatibilityMode = compatibilityMode,
    )
}
