package com.taowen.arglass.driver.xreal

import com.taowen.arglass.DisplayMode
import com.taowen.arglass.GlassesDisplayLayout
import com.taowen.arglass.GlassesDisplayProfile

internal interface XrealMcuDisplayModeProtocol {
    val profiles: List<GlassesDisplayProfile>
    val queryPayloadBytes: Int
    val setPayloadBytes: Int
    fun decode(value: Int): DisplayMode?
    fun decodeProfile(value: Int): GlassesDisplayProfile?
    fun encode(mode: DisplayMode): Int
    fun encodeProfile(profile: GlassesDisplayProfile): Int?
}

internal data class XrealMcuDisplayProfileEntry(
    val protocolValue: Int,
    val profile: GlassesDisplayProfile,
)

internal fun xrealMcuDisplayProfile(
    profileIdPrefix: String,
    protocolValue: Int,
    width: Int,
    height: Int,
    refreshRateHz: Int,
    layout: GlassesDisplayLayout,
    compatibilityMode: DisplayMode,
) = XrealMcuDisplayProfileEntry(
    protocolValue,
    GlassesDisplayProfile(
        id = "$profileIdPrefix$protocolValue",
        width = width,
        height = height,
        refreshRateHz = refreshRateHz,
        layout = layout,
        compatibilityMode = compatibilityMode,
    ),
)
