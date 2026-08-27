package io.github.glandais.karoo.weather.ui.theme

import io.github.glandais.karoo.weather.domain.WindClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TokensTest {

    @Test
    fun colorPairPicksTheRightSide() {
        val pair = ColorPair(0xFF112233, 0xFFAABBCC)
        assertEquals(0xFF112233.toInt(), pair.pick(false))
        assertEquals(0xFFAABBCC.toInt(), pair.pick(true))
    }

    @Test
    fun colorPairSurvivesTheLongToIntNarrowing() {
        // 0xFFFFFFFF does not fit in a signed Int literal; it must still come back as -1.
        assertEquals(-1, Wx.bg.pick(false))
        assertEquals(0xFF000000.toInt(), Wx.bg.pick(true))
    }

    @Test
    fun tempMildEqualsForegroundOnPurpose() {
        assertEquals(Wx.fg, Wx.tempMild)
    }

    @Test
    fun forTempBoundaries() {
        assertEquals(Wx.tempFreezing, Wx.forTemp(-0.001))
        assertEquals(Wx.tempFreezing, Wx.forTemp(-40.0))
        assertEquals(Wx.tempCold, Wx.forTemp(0.0))
        assertEquals(Wx.tempCold, Wx.forTemp(9.999))
        assertEquals(Wx.tempMild, Wx.forTemp(10.0))
        assertEquals(Wx.tempMild, Wx.forTemp(19.999))
        assertEquals(Wx.tempWarm, Wx.forTemp(20.0))
        assertEquals(Wx.tempWarm, Wx.forTemp(28.0))
        assertEquals(Wx.tempHot, Wx.forTemp(28.001))
        assertEquals(Wx.tempHot, Wx.forTemp(55.0))
    }

    @Test
    fun forTempIsTotalAndMonotonic() {
        val order = listOf(Wx.tempFreezing, Wx.tempCold, Wx.tempMild, Wx.tempWarm, Wx.tempHot)
        var previous = 0
        var c = -60.0
        while (c <= 60.0) {
            val index = order.indexOf(Wx.forTemp(c))
            assertTrue("no bucket for $c", index >= 0)
            assertTrue("ramp went backwards at $c", index >= previous)
            previous = index
            c += 0.1
        }
        assertEquals(order.lastIndex, previous)
    }

    @Test
    fun forHeadwindBoundaries() {
        assertEquals(Wx.windTail, Wx.forHeadwind(-20.0))
        assertEquals(Wx.windTail, Wx.forHeadwind(-2.8))
        assertEquals(Wx.windCalm, Wx.forHeadwind(-2.799))
        assertEquals(Wx.windCalm, Wx.forHeadwind(0.0))
        assertEquals(Wx.windCalm, Wx.forHeadwind(1.4))
        assertEquals(Wx.windCross, Wx.forHeadwind(1.401))
        assertEquals(Wx.windCross, Wx.forHeadwind(4.2))
        assertEquals(Wx.windHead, Wx.forHeadwind(4.201))
        assertEquals(Wx.windHead, Wx.forHeadwind(30.0))
    }

    @Test
    fun forHeadwindIsTotalAndMonotonic() {
        val order = listOf(Wx.windTail, Wx.windCalm, Wx.windCross, Wx.windHead)
        var previous = 0
        var v = -30.0
        while (v <= 30.0) {
            val index = order.indexOf(Wx.forHeadwind(v))
            assertTrue("no bucket for $v", index >= 0)
            assertTrue("scale went backwards at $v", index >= previous)
            previous = index
            v += 0.05
        }
        assertEquals(order.lastIndex, previous)
    }

    @Test
    fun forRainBoundaries() {
        assertEquals(Wx.divider, Wx.forRain(0.0))
        assertEquals(Wx.divider, Wx.forRain(0.099))
        assertEquals(Wx.rainLight, Wx.forRain(0.1))
        assertEquals(Wx.rainLight, Wx.forRain(0.499))
        assertEquals(Wx.rainMed, Wx.forRain(0.5))
        assertEquals(Wx.rainMed, Wx.forRain(2.0))
        assertEquals(Wx.rainHeavy, Wx.forRain(2.001))
        assertEquals(Wx.rainHeavy, Wx.forRain(50.0))
    }

    @Test
    fun forRainIsTotalAndMonotonic() {
        val order = listOf(Wx.divider, Wx.rainLight, Wx.rainMed, Wx.rainHeavy)
        var previous = 0
        var mm = 0.0
        while (mm <= 20.0) {
            val index = order.indexOf(Wx.forRain(mm))
            assertTrue("no bucket for $mm", index >= 0)
            assertTrue("scale went backwards at $mm", index >= previous)
            previous = index
            mm += 0.01
        }
        assertEquals(order.lastIndex, previous)
    }

    @Test
    fun forWindClassIsTotal() {
        WindClass.entries.forEach { assertNotNull(Wx.forWindClass(it)) }
        assertEquals(Wx.windTail, Wx.forWindClass(WindClass.TAIL))
        assertEquals(Wx.windCross, Wx.forWindClass(WindClass.CROSS))
        assertEquals(Wx.windHead, Wx.forWindClass(WindClass.HEAD))
    }

    @Test
    fun greenIsReservedForTailwind() {
        val green = listOf(Wx.windTail)
        val everythingElse =
            listOf(
                Wx.bg,
                Wx.fg,
                Wx.fgMuted,
                Wx.divider,
                Wx.tempFreezing,
                Wx.tempCold,
                Wx.tempMild,
                Wx.tempWarm,
                Wx.tempHot,
                Wx.windCalm,
                Wx.windCross,
                Wx.windHead,
                Wx.rainLight,
                Wx.rainMed,
                Wx.rainHeavy,
            )
        everythingElse.forEach { assertTrue("$it collides with the tailwind green", it !in green) }
    }
}
