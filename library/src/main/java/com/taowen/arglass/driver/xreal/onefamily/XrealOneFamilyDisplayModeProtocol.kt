package com.taowen.arglass.driver.xreal.onefamily

import com.taowen.arglass.DisplayMode
import com.taowen.arglass.GlassesDisplayLayout
import com.taowen.arglass.GlassesDisplayProfile

internal data class XrealOneDisplayModeCommand(val edid: Int, val inputMode: Int)

internal data class XrealOneDisplayProfileEntry(
    val command: XrealOneDisplayModeCommand,
    val profile: GlassesDisplayProfile,
)

internal interface XrealOneDpDisplayModeProtocol {
    val profiles: List<GlassesDisplayProfile>
    fun decode(edid: Int): DisplayMode?
    fun decodeProfile(edid: Int): GlassesDisplayProfile?
    fun encode(mode: DisplayMode): XrealOneDisplayModeCommand?
    fun encodeProfile(profile: GlassesDisplayProfile): XrealOneDisplayModeCommand?
}

internal fun xrealOneDisplayProfile(
    profileIdPrefix: String,
    edid: Int,
    inputMode: Int,
    width: Int,
    height: Int,
    refreshRateHz: Int,
    layout: GlassesDisplayLayout,
    compatibilityMode: DisplayMode,
) = XrealOneDisplayProfileEntry(
    XrealOneDisplayModeCommand(edid, inputMode),
    GlassesDisplayProfile(
        id = "$profileIdPrefix$edid",
        width = width,
        height = height,
        refreshRateHz = refreshRateHz,
        layout = layout,
        compatibilityMode = compatibilityMode,
    ),
)
