package com.taowen.arglass.driver.xreal.ones

import com.taowen.arglass.GlassesDisplayLayout
import com.taowen.arglass.GlassesDisplayProfile
import com.taowen.arglass.driver.xreal.onefamily.XrealOneDpDisplayModeProtocol
import com.taowen.arglass.driver.xreal.onefamily.XrealOneDisplayModeCommand
import com.taowen.arglass.driver.xreal.onefamily.XrealOneDisplayProfileEntry
import com.taowen.arglass.driver.xreal.onefamily.xrealOneDisplayProfile

/** GS/XREAL 1S declares its own profile list; EDID 5/inputMode 1 was verified on XREAL 1S hardware. */
internal object XrealOneSDisplayModeProtocol : XrealOneDpDisplayModeProtocol {
    private const val EDID_3D_3840_1080_60HZ = 5
    private const val EDID_2D_1920_1200_90HZ = 9
    override val requireInputModeReadback = false
    override val inputModeWriteAttempts = 3

    private val profileTable = listOf(
        profile(EDID_3D_3840_1080_60HZ, inputMode = 1, 3840, 1080, 60, GlassesDisplayLayout.FULL_SBS_3D),
        profile(EDID_2D_1920_1200_90HZ, inputMode = 0, 1920, 1200, 90, GlassesDisplayLayout.MONO_2D),
    )

    override val profiles: List<GlassesDisplayProfile> = profileTable.map(XrealOneDisplayProfileEntry::profile)

    override fun decodeProfile(edid: Int): GlassesDisplayProfile? =
        profileTable.firstOrNull { it.command.edid == edid }?.profile

    override fun encodeProfile(profile: GlassesDisplayProfile): XrealOneDisplayModeCommand? =
        profileTable.firstOrNull { it.profile.id == profile.id }?.command

    private fun profile(
        edid: Int,
        inputMode: Int,
        width: Int,
        height: Int,
        refreshRateHz: Int,
        layout: GlassesDisplayLayout,
    ) = xrealOneDisplayProfile(
        profileIdPrefix = "xreal_one_s_edid_",
        edid = edid,
        inputMode = inputMode,
        width = width,
        height = height,
        refreshRateHz = refreshRateHz,
        layout = layout,
    )
}
