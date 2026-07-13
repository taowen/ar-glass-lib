package com.taowen.arglass.driver.luci

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LuciDisplayProtocolTest {
    @Test fun builds3dFeatureReport() {
        val report = LuciDisplayProtocol.powerStateReport(enable3d = true)
        assertEquals(64, report.size)
        assertArrayEquals(byteArrayOf(0, 1, 0x8d.toByte(), 5, 0x83.toByte(), 0x16), report.copyOfRange(0, 6))
        assertTrue(report.drop(6).all { it == 0.toByte() })
    }

    @Test fun builds2dFeatureReport() {
        val report = LuciDisplayProtocol.powerStateReport(enable3d = false)
        assertEquals(64, report.size)
        assertArrayEquals(byteArrayOf(0, 1, 0x8d.toByte(), 5, 0x93.toByte(), 0x26), report.copyOfRange(0, 6))
        assertTrue(report.drop(6).all { it == 0.toByte() })
    }
}
