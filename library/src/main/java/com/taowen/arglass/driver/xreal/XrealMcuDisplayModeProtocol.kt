package com.taowen.arglass.driver.xreal

import com.taowen.arglass.GlassesDisplayLayout
import com.taowen.arglass.GlassesDisplayProfile

internal interface XrealMcuDisplayModeProtocol {
    val profiles: List<GlassesDisplayProfile>
    val queryPayloadBytes: Int
    val setPayloadBytes: Int
    fun decodeProfile(value: Int): GlassesDisplayProfile?
    fun encodeProfile(profile: GlassesDisplayProfile): Int?
    fun acceptsSetStatus(value: Int, status: Int): Boolean = status == 0
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
) = XrealMcuDisplayProfileEntry(
    protocolValue,
    GlassesDisplayProfile(
        id = "$profileIdPrefix$protocolValue",
        width = width,
        height = height,
        refreshRateHz = refreshRateHz,
        layout = layout,
    ),
)
