package io.github.glandais.karoo.weather.weather

import io.github.glandais.karoo.weather.domain.WmoCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WmoCodesTest {

    @Test
    fun `every documented code maps to its category`() {
        val expected =
            mapOf(
                0 to WmoCategory.CLEAR,
                1 to WmoCategory.MOSTLY_CLEAR,
                2 to WmoCategory.PARTLY_CLOUDY,
                3 to WmoCategory.OVERCAST,
                45 to WmoCategory.FOG,
                48 to WmoCategory.FOG,
                51 to WmoCategory.DRIZZLE,
                53 to WmoCategory.DRIZZLE,
                55 to WmoCategory.DRIZZLE,
                56 to WmoCategory.FREEZING,
                57 to WmoCategory.FREEZING,
                61 to WmoCategory.RAIN,
                63 to WmoCategory.RAIN,
                65 to WmoCategory.HEAVY_RAIN,
                66 to WmoCategory.FREEZING,
                67 to WmoCategory.FREEZING,
                71 to WmoCategory.SNOW,
                73 to WmoCategory.SNOW,
                75 to WmoCategory.HEAVY_SNOW,
                77 to WmoCategory.SNOW,
                80 to WmoCategory.SHOWERS,
                81 to WmoCategory.SHOWERS,
                82 to WmoCategory.SHOWERS,
                85 to WmoCategory.SNOW,
                86 to WmoCategory.HEAVY_SNOW,
                95 to WmoCategory.THUNDER,
                96 to WmoCategory.THUNDER_HAIL,
                99 to WmoCategory.THUNDER_HAIL,
            )
        expected.forEach { (code, category) ->
            assertEquals("code $code", category, WmoCodes.category(code))
        }
    }

    @Test
    fun `fog is fog and is not wet`() {
        assertEquals(WmoCategory.FOG, WmoCodes.category(45))
        assertEquals(WmoCategory.FOG, WmoCodes.category(48))
        assertFalse(WmoCodes.isWet(45))
        assertFalse(WmoCodes.isWet(48))
    }

    @Test
    fun `unknown codes fall back to UNKNOWN and are dry`() {
        listOf(-1, 4, 44, 60, 70, 98, 100, 12345).forEach {
            assertEquals("code $it", WmoCategory.UNKNOWN, WmoCodes.category(it))
            assertFalse(WmoCodes.isWet(it))
        }
    }

    @Test
    fun `precipitating codes are wet and cloud codes are dry`() {
        listOf(51, 55, 56, 61, 65, 66, 71, 75, 80, 82, 85, 86, 95, 96, 99).forEach {
            assertTrue("code $it should be wet", WmoCodes.isWet(it))
        }
        listOf(0, 1, 2, 3).forEach { assertFalse("code $it should be dry", WmoCodes.isWet(it)) }
    }

    @Test
    fun `icon lookup is total for every category and both day flags`() {
        WmoCategory.entries.forEach { category ->
            listOf(true, false).forEach { isDay ->
                assertNotEquals("field $category/$isDay", 0, WmoIcons.field(category, isDay))
                assertNotEquals("map $category/$isDay", 0, WmoIcons.map(category, isDay))
            }
        }
    }

    @Test
    fun `only sun-bearing categories have distinct day and night icons`() {
        val dayNightAware =
            setOf(WmoCategory.CLEAR, WmoCategory.MOSTLY_CLEAR, WmoCategory.PARTLY_CLOUDY)
        WmoCategory.entries.forEach { category ->
            val day = WmoIcons.field(category, true)
            val night = WmoIcons.field(category, false)
            if (category in dayNightAware) {
                assertNotEquals("$category should differ by day/night", day, night)
            } else {
                assertEquals("$category should not differ by day/night", day, night)
            }
        }
    }

    @Test
    fun `fieldForCode composes category and icon lookup`() {
        assertEquals(WmoIcons.field(WmoCategory.OVERCAST, true), WmoIcons.fieldForCode(3, true))
        assertEquals(WmoIcons.field(WmoCategory.CLEAR, false), WmoIcons.fieldForCode(0, false))
    }
}
