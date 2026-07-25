package com.taowen.arglass.driver.xreal.air2ultra

import com.taowen.arglass.GlassesDisplayLayout
import com.taowen.arglass.GlassesDisplayProfile

/** Flora display modes used by ARLauncher/XREAL SDK 3.1.0. */
internal object XrealAir2UltraDisplayModeProtocol {
    private const val MODE_2D_60HZ = 1
    private const val MODE_3D_60HZ = 3
    const val MODE_3D_72HZ = 4
    private const val MODE_2D_72HZ = 5
    private const val MODE_3D_90HZ = 9
    const val MODE_2D_90HZ = 10
    private const val MODE_2D_120HZ = 11

    private data class ProfileEntry(val protocolValue: Int, val profile: GlassesDisplayProfile)

    private val profileTable = listOf(
        profile(MODE_2D_60HZ, 1920, 1080, 60, GlassesDisplayLayout.MONO_2D),
        profile(MODE_3D_60HZ, 3840, 1080, 60, GlassesDisplayLayout.FULL_SBS_3D),
        profile(MODE_3D_72HZ, 3840, 1080, 72, GlassesDisplayLayout.FULL_SBS_3D),
        profile(MODE_2D_72HZ, 1920, 1080, 72, GlassesDisplayLayout.MONO_2D),
        profile(MODE_3D_90HZ, 3840, 1080, 90, GlassesDisplayLayout.FULL_SBS_3D),
        profile(MODE_2D_90HZ, 1920, 1080, 90, GlassesDisplayLayout.MONO_2D),
        profile(MODE_2D_120HZ, 1920, 1080, 120, GlassesDisplayLayout.MONO_2D),
    )

    val profiles: List<GlassesDisplayProfile> = profileTable.map(ProfileEntry::profile)
    val preferred2dProfile: GlassesDisplayProfile? =
        profileTable.firstOrNull { it.protocolValue == MODE_2D_90HZ }?.profile

    fun decodeProfile(value: Int): GlassesDisplayProfile? =
        profileTable.firstOrNull { it.protocolValue == value }?.profile

    fun encodeProfile(profile: GlassesDisplayProfile): Int? =
        profileTable.firstOrNull { it.profile.id == profile.id }?.protocolValue

    private fun profile(
        protocolValue: Int,
        width: Int,
        height: Int,
        refreshRateHz: Int,
        layout: GlassesDisplayLayout,
    ) = ProfileEntry(
        protocolValue,
        GlassesDisplayProfile(
            id = "xreal_air_2_ultra_mode_$protocolValue",
            width = width,
            height = height,
            refreshRateHz = refreshRateHz,
            layout = layout,
            ),
    )
}
