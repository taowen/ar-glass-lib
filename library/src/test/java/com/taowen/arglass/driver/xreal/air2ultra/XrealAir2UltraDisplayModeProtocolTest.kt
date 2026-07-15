package com.taowen.arglass.driver.xreal.air2ultra

import com.taowen.arglass.DisplayMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class XrealAir2UltraDisplayModeProtocolTest {
    @Test
    fun `encodes ARLauncher Flora defaults and high refresh mode`() {
        assertEquals(10, XrealAir2UltraDisplayModeProtocol.encode(DisplayMode.MIRROR_2D))
        assertEquals(4, XrealAir2UltraDisplayModeProtocol.encode(DisplayMode.FULL_SBS_3D))
        assertEquals(2, XrealAir2UltraDisplayModeProtocol.encode(DisplayMode.HIGH_REFRESH_SBS_3D))
        assertThrows(IllegalStateException::class.java) {
            XrealAir2UltraDisplayModeProtocol.encode(DisplayMode.HALF_SBS_3D)
        }
    }

    @Test
    fun `decodes every known Flora 2D and 3D mode`() {
        listOf(1, 5, 10, 11).forEach {
            assertEquals(DisplayMode.MIRROR_2D, XrealAir2UltraDisplayModeProtocol.decode(it))
        }
        listOf(3, 4, 9).forEach {
            assertEquals(DisplayMode.FULL_SBS_3D, XrealAir2UltraDisplayModeProtocol.decode(it))
        }
        assertEquals(DisplayMode.HIGH_REFRESH_SBS_3D, XrealAir2UltraDisplayModeProtocol.decode(2))
        assertNull(XrealAir2UltraDisplayModeProtocol.decode(0))
    }
}
