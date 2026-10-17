package com.animationworkshop.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Batch02CoreTest {
    @Test
    fun brightestPixelStackingKeepsTheBrightestValuePerChannel() {
        val result = LightTrailStacker.brightestChannelColor(0xff204080.toInt(), 0xff805020.toInt())
        assertEquals(0xff805080.toInt(), result)
    }

    @Test
    fun shutterChoicesOnlyIncludeValuesSupportedByTheCameraRange() {
        assertEquals(listOf(125_000_000L, 250_000_000L, 500_000_000L), supportedShutterChoices(100_000_000L, 600_000_000L))
        assertEquals(listOf(700_000_000L, 800_000_000L), supportedShutterChoices(700_000_000L, 800_000_000L))
        assertTrue(supportedShutterChoices(0L, 0L).isEmpty())
    }
}
