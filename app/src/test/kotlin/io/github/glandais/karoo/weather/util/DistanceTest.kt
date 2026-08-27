package io.github.glandais.karoo.weather.util

import io.github.glandais.karoo.weather.domain.DistanceUnit
import io.github.glandais.karoo.weather.domain.TempUnit
import io.github.glandais.karoo.weather.domain.Units
import io.github.glandais.karoo.weather.domain.WindUnit
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class DistanceTest {

    private val us = Locale.US

    @Test
    fun `convert applies the unit factor`() {
        assertEquals(18.0, Distance.convert(18_000.0, DistanceUnit.KM), 1e-9)
        assertEquals(11.184, Distance.convert(18_000.0, DistanceUnit.MILES), 1e-3)
    }

    @Test
    fun `format drops the decimal at and above ten display units`() {
        assertEquals("18", Distance.format(18_000.0, DistanceUnit.KM, us))
        assertEquals("10", Distance.format(10_000.0, DistanceUnit.KM, us))
        assertEquals("9.9", Distance.format(9_900.0, DistanceUnit.KM, us))
        assertEquals("0.0", Distance.format(0.0, DistanceUnit.KM, us))
    }

    @Test
    fun `format works in miles`() {
        assertEquals("6.2", Distance.format(10_000.0, DistanceUnit.MILES, us))
    }

    @Test
    fun `temperature rounds to a whole display degree`() {
        val metric = Units(temp = TempUnit.CELSIUS)
        val imperial = Units(temp = TempUnit.FAHRENHEIT)
        assertEquals("22", Numbers.temp(21.6, metric, us))
        assertEquals("72", Numbers.temp(22.0, imperial, us))
        assertEquals("-3", Numbers.temp(-3.2, metric, us))
    }

    @Test
    fun `wind rounds in the display unit`() {
        assertEquals("14", Numbers.wind(4.0, Units(wind = WindUnit.KMH), us))
        assertEquals("4", Numbers.wind(4.0, Units(wind = WindUnit.MS), us))
        assertEquals("3", Numbers.wind(4.0, Units(wind = WindUnit.BEAUFORT), us))
    }

    @Test
    fun `millimetres always carry one decimal`() {
        assertEquals("0.0", Numbers.mm(0.0, us))
        assertEquals("2.1", Numbers.mm(2.14, us))
    }

    @Test
    fun `percent is clamped`() {
        assertEquals("0", Numbers.percent(-5))
        assertEquals("40", Numbers.percent(40))
        assertEquals("100", Numbers.percent(140))
    }

    @Test
    fun `signedWind converts the magnitude and prepends the sign`() {
        val kmh = Units(wind = WindUnit.KMH)
        assertEquals("+22", Numbers.signedWind(6.0, kmh, us))
        assertEquals("-22", Numbers.signedWind(-6.0, kmh, us))
        assertEquals("+0", Numbers.signedWind(0.0, kmh, us))
    }

    @Test
    fun `signedWind keeps a tailwind meaningful on the Beaufort scale`() {
        // Converting -6 m/s directly would give Beaufort 0: the scale is defined on speed, not on
        // a signed component. The magnitude must be converted first.
        val bft = Units(wind = WindUnit.BEAUFORT)
        assertEquals("-4", Numbers.signedWind(-6.0, bft, us))
        assertEquals("+4", Numbers.signedWind(6.0, bft, us))
    }
}
