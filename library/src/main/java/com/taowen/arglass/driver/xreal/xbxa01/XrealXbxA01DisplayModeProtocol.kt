package com.taowen.arglass.driver.xreal.xbxa01

import com.taowen.arglass.GlassesDisplayLayout
import com.taowen.arglass.GlassesDisplayProfile
import com.taowen.arglass.driver.xreal.XrealMcuDisplayModeProtocol
import com.taowen.arglass.driver.xreal.XrealMcuDisplayProfileEntry
import com.taowen.arglass.driver.xreal.xrealMcuDisplayProfile

internal object XrealXbxA01DisplayModeProtocol : XrealMcuDisplayModeProtocol {
    private const val MODE_2D_60HZ = 1
    private const val MODE_3D_60HZ = 3
    private const val MODE_3D_72HZ = 4
    private const val MODE_2D_90HZ = 10
    private const val MODE_2D_120HZ = 11
    private const val MODE_2D_120HZ_MULTI_REFRESH = 17

    override val queryPayloadBytes = 4
    override val setPayloadBytes = 4

    private val profileTable = listOf(
        profile(MODE_2D_60HZ, 1920, 1080, 60, GlassesDisplayLayout.MONO_2D),
        profile(MODE_2D_90HZ, 1920, 1080, 90, GlassesDisplayLayout.MONO_2D),
        profile(MODE_2D_120HZ, 1920, 1080, 120, GlassesDisplayLayout.MONO_2D),
        profile(MODE_2D_120HZ_MULTI_REFRESH, 1920, 1080, 120, GlassesDisplayLayout.MONO_2D),
        profile(MODE_3D_60HZ, 3840, 1080, 60, GlassesDisplayLayout.FULL_SBS_3D),
        profile(MODE_3D_72HZ, 3840, 1080, 72, GlassesDisplayLayout.FULL_SBS_3D),
    )

    override val profiles: List<GlassesDisplayProfile> = profileTable.map(XrealMcuDisplayProfileEntry::profile)

    override fun decodeProfile(value: Int): GlassesDisplayProfile? =
        profileTable.firstOrNull { it.protocolValue == value }?.profile

    override fun encodeProfile(profile: GlassesDisplayProfile): Int? =
        profileTable.firstOrNull { it.profile.id == profile.id }?.protocolValue

    override fun acceptsSetStatus(value: Int, status: Int): Boolean = status == 0

    private fun profile(
        protocolValue: Int,
        width: Int,
        height: Int,
        refreshRateHz: Int,
        layout: GlassesDisplayLayout,
    ) = xrealMcuDisplayProfile(
        profileIdPrefix = "xreal_xbx_a01_mode_",
        protocolValue = protocolValue,
        width = width,
        height = height,
        refreshRateHz = refreshRateHz,
        layout = layout,
    )
}
