package com.taowen.arglass.driver.xreal.xbx

import com.taowen.arglass.DisplayMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class XrealXbxDisplayModeProtocolTest {
    @Test
    fun `encodes ARLauncher Helen defaults`() {
        assertEquals(10, XrealXbxDisplayModeProtocol.encode(DisplayMode.MIRROR_2D))
        assertEquals(4, XrealXbxDisplayModeProtocol.encode(DisplayMode.FULL_SBS_3D))
        assertEquals(2, XrealXbxDisplayModeProtocol.encode(DisplayMode.HIGH_REFRESH_SBS_3D))
    }

    @Test
    fun `decodes every known Helen 2D and 3D mode`() {
        listOf(1, 5, 10, 11).forEach {
            assertEquals(DisplayMode.MIRROR_2D, XrealXbxDisplayModeProtocol.decode(it))
        }
        assertEquals(DisplayMode.HIGH_REFRESH_SBS_3D, XrealXbxDisplayModeProtocol.decode(2))
        listOf(3, 4, 9).forEach {
            assertEquals(DisplayMode.FULL_SBS_3D, XrealXbxDisplayModeProtocol.decode(it))
        }
        assertNull(XrealXbxDisplayModeProtocol.decode(0))
    }
}
