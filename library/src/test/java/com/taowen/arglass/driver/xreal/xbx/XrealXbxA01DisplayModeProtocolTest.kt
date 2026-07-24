package com.taowen.arglass.driver.xreal.xbx

import com.taowen.arglass.DisplayMode
import com.taowen.arglass.driver.xreal.xbxa01.XrealXbxA01DisplayModeProtocol
import com.taowen.arglass.driver.xreal.xbxa01plus.XrealXbxA01PlusDisplayModeProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class XrealXbxA01DisplayModeProtocolTest {
    @Test
    fun `encodes A01 ARLauncher Helen defaults`() {
        assertEquals(10, XrealXbxA01DisplayModeProtocol.encode(DisplayMode.MIRROR_2D))
        assertEquals(4, XrealXbxA01DisplayModeProtocol.encode(DisplayMode.FULL_SBS_3D))
        assertEquals(9, XrealXbxA01DisplayModeProtocol.encode(DisplayMode.HIGH_REFRESH_SBS_3D))
    }

    @Test
    fun `decodes every known A01 2D and 3D mode`() {
        listOf(1, 5, 10, 11).forEach {
            assertEquals(DisplayMode.MIRROR_2D, XrealXbxA01DisplayModeProtocol.decode(it))
        }
        assertNull(XrealXbxA01DisplayModeProtocol.decode(2))
        listOf(3, 4, 9).forEach {
            val expected = if (it == 9) DisplayMode.HIGH_REFRESH_SBS_3D else DisplayMode.FULL_SBS_3D
            assertEquals(expected, XrealXbxA01DisplayModeProtocol.decode(it))
        }
        assertNull(XrealXbxA01DisplayModeProtocol.decode(0))
    }

    @Test
    fun `A01 plus has its own profile ids`() {
        assertEquals("xreal_xbx_a01_mode_10", XrealXbxA01DisplayModeProtocol.decodeProfile(10)?.id)
        assertEquals("xreal_xbx_a01_plus_mode_10", XrealXbxA01PlusDisplayModeProtocol.decodeProfile(10)?.id)
    }
}
