package com.taowen.arglass.driver.xreal.ones

import com.taowen.arglass.DisplayMode
import com.taowen.arglass.GlassesDisplayLayout
import com.taowen.arglass.GlassesDisplayProfile
import com.taowen.arglass.driver.xreal.onefamily.XrealOneDpDisplayModeProtocol
import com.taowen.arglass.driver.xreal.onefamily.XrealOneDisplayModeCommand
import com.taowen.arglass.driver.xreal.onefamily.XrealOneDisplayProfileEntry
import com.taowen.arglass.driver.xreal.onefamily.xrealOneDisplayProfile

/** GS/One S declares its own profile list instead of reusing One's public profile IDs. */
internal object XrealOneSDisplayModeProtocol : XrealOneDpDisplayModeProtocol {
    private const val EDID_3D_3840_1080_60HZ = 5
    private const val EDID_2D_1920_1080_90HZ = 9

    private val profileTable = listOf(
        profile(EDID_3D_3840_1080_60HZ, inputMode = 1, 3840, 1080, 60, GlassesDisplayLayout.FULL_SBS_3D, DisplayMode.FULL_SBS_3D),
        profile(EDID_2D_1920_1080_90HZ, inputMode = 0, 1920, 1080, 90, GlassesDisplayLayout.MONO_2D, DisplayMode.MIRROR_2D),
    )

    override val profiles: List<GlassesDisplayProfile> = profileTable.map(XrealOneDisplayProfileEntry::profile)

    override fun decode(edid: Int): DisplayMode? = decodeProfile(edid)?.compatibilityMode

    override fun decodeProfile(edid: Int): GlassesDisplayProfile? =
        profileTable.firstOrNull { it.command.edid == edid }?.profile

    override fun encode(mode: DisplayMode): XrealOneDisplayModeCommand? = when (mode) {
        DisplayMode.MIRROR_2D -> XrealOneDisplayModeCommand(EDID_2D_1920_1080_90HZ, inputMode = 0)
        DisplayMode.FULL_SBS_3D -> XrealOneDisplayModeCommand(EDID_3D_3840_1080_60HZ, inputMode = 1)
        DisplayMode.HALF_SBS_3D, DisplayMode.HIGH_REFRESH_SBS_3D -> null
    }

    override fun encodeProfile(profile: GlassesDisplayProfile): XrealOneDisplayModeCommand? =
        profileTable.firstOrNull { it.profile.id == profile.id }?.command

    private fun profile(
        edid: Int,
        inputMode: Int,
        width: Int,
        height: Int,
        refreshRateHz: Int,
        layout: GlassesDisplayLayout,
        compatibilityMode: DisplayMode,
    ) = xrealOneDisplayProfile(
        profileIdPrefix = "xreal_one_s_edid_",
        edid = edid,
        inputMode = inputMode,
        width = width,
        height = height,
        refreshRateHz = refreshRateHz,
        layout = layout,
        compatibilityMode = compatibilityMode,
    )
}
