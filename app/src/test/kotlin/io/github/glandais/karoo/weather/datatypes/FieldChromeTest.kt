package io.github.glandais.karoo.weather.datatypes

import io.github.glandais.karoo.weather.datatypes.views.FieldChrome
import io.github.glandais.karoo.weather.domain.TempUnit
import io.github.glandais.karoo.weather.domain.WindUnit
import io.hammerhead.karooext.models.ViewConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Layout decision functions only; rendering is not unit tested. */
class FieldChromeTest {

    private fun config(
        gridSize: Pair<Int, Int>,
        viewSize: Pair<Int, Int>,
        textSize: Int = 32,
        boundaries: Boolean = false,
    ) =
        ViewConfig(
            gridSize = gridSize,
            viewSize = viewSize,
            textSize = textSize,
            boundariesEnabled = boundaries,
        )

    @Test
    fun `columnsFor yields five at the design geometry`() {
        assertEquals(5, FieldChrome.columnsFor(480 to 400, maxColumns = 5))
    }

    @Test
    fun `columnsFor never exceeds maxColumns`() {
        assertEquals(3, FieldChrome.columnsFor(480 to 200, maxColumns = 3))
        assertEquals(1, FieldChrome.columnsFor(480 to 400, maxColumns = 1))
        assertEquals(5, FieldChrome.columnsFor(4000 to 400, maxColumns = 5))
    }

    @Test
    fun `columnsFor never returns fewer than one`() {
        assertEquals(1, FieldChrome.columnsFor(0 to 0, maxColumns = 5))
        assertEquals(1, FieldChrome.columnsFor(87 to 400, maxColumns = 5))
        assertEquals(1, FieldChrome.columnsFor(240 to 400, maxColumns = 0))
        assertEquals(1, FieldChrome.columnsFor(240 to 400, maxColumns = -3))
    }

    @Test
    fun `columnsFor degrades on a narrower panel`() {
        assertEquals(2, FieldChrome.columnsFor(200 to 400, maxColumns = 5))
        assertEquals(4, FieldChrome.columnsFor(400 to 400, maxColumns = 5))
        // Exactly at the cell boundary.
        assertEquals(1, FieldChrome.columnsFor(FieldChrome.MIN_CELL_PX to 400, maxColumns = 5))
    }

    @Test
    fun `arrowBucket10 rounds to the nearest ten degrees`() {
        assertEquals(0, FieldChrome.arrowBucket10(0.0))
        assertEquals(0, FieldChrome.arrowBucket10(4.9))
        assertEquals(1, FieldChrome.arrowBucket10(5.0))
        assertEquals(1, FieldChrome.arrowBucket10(14.9))
        assertEquals(2, FieldChrome.arrowBucket10(15.0))
        assertEquals(9, FieldChrome.arrowBucket10(90.0))
        assertEquals(35, FieldChrome.arrowBucket10(350.0))
    }

    @Test
    fun `arrowBucket10 wraps instead of producing a thirty-sixth bucket`() {
        assertEquals(0, FieldChrome.arrowBucket10(355.0))
        assertEquals(0, FieldChrome.arrowBucket10(360.0))
        assertEquals(1, FieldChrome.arrowBucket10(365.0))
    }

    @Test
    fun `arrowBucket10 normalises negative and non-finite bearings`() {
        assertEquals(35, FieldChrome.arrowBucket10(-10.0))
        assertEquals(27, FieldChrome.arrowBucket10(-90.0))
        assertEquals(9, FieldChrome.arrowBucket10(450.0))
        assertEquals(0, FieldChrome.arrowBucket10(Double.NaN))
        assertEquals(0, FieldChrome.arrowBucket10(Double.POSITIVE_INFINITY))
    }

    @Test
    fun `arrowBucket10 is total over a full turn`() {
        var degrees = -720.0
        while (degrees <= 720.0) {
            val bucket = FieldChrome.arrowBucket10(degrees)
            assertTrue("bucket $bucket out of range at $degrees", bucket in 0..35)
            degrees += 0.5
        }
    }

    @Test
    fun `arrowBucketDegrees is the bucket in degrees`() {
        assertEquals(0f, FieldChrome.arrowBucketDegrees(2.0), 0f)
        assertEquals(90f, FieldChrome.arrowBucketDegrees(88.0), 0f)
        assertEquals(350f, FieldChrome.arrowBucketDegrees(348.0), 0f)
    }

    @Test
    fun `padding follows the boundaries setting`() {
        assertEquals(2, FieldChrome.paddingDp(config(60 to 30, 480 to 400)))
        assertEquals(4, FieldChrome.paddingDp(config(60 to 30, 480 to 400, boundaries = true)))
    }

    @Test
    fun `arrow grows only on a tall field`() {
        assertEquals(48, FieldChrome.arrowSizePx(config(60 to 15, 480 to 200)))
        assertEquals(48, FieldChrome.arrowSizePx(config(60 to 30, 480 to 300)))
        assertEquals(56, FieldChrome.arrowSizePx(config(60 to 30, 480 to 400)))
    }

    @Test
    fun `type sizes derive from textSize and respect the legibility floors`() {
        val big = config(60 to 60, 480 to 800, textSize = 48)
        assertEquals(48f, FieldChrome.primarySp(big), 1e-4f)
        assertEquals(26.4f, FieldChrome.secondarySp(big), 1e-4f)
        assertEquals(19.2f, FieldChrome.unitSp(big), 1e-4f)
        assertEquals(17.28f, FieldChrome.labelSp(big), 1e-4f)

        val tiny = config(30 to 30, 240 to 400, textSize = 12)
        assertEquals(FieldChrome.MIN_LABEL_SP, FieldChrome.labelSp(tiny), 1e-4f)
        assertEquals(FieldChrome.MIN_MICRO_SP, FieldChrome.microSp(tiny), 1e-4f)
        assertFalse(FieldChrome.labelFits(tiny))
        assertTrue(FieldChrome.labelFits(big))
    }

    @Test
    fun `icon box is clamped to the 24 to 56 dp band`() {
        assertEquals(56, FieldChrome.iconBoxDp(config(60 to 60, 480 to 800), density = 2f))
        assertEquals(24, FieldChrome.iconBoxDp(config(30 to 30, 60 to 60), density = 2f))
        assertEquals(50, FieldChrome.iconBoxDp(config(60 to 30, 480 to 240), density = 2f))
        // A nonsense density must not divide by zero.
        assertEquals(56, FieldChrome.iconBoxDp(config(60 to 60, 480 to 800), density = 0f))
    }

    @Test
    fun `compass labels cover all sixteen points and wrap`() {
        val labels = (0..15).map { FieldChrome.compassLabel(it) }
        assertEquals(16, labels.toSet().size)
        assertEquals(labels[0], FieldChrome.compassLabel(16))
        assertEquals(labels[15], FieldChrome.compassLabel(-1))
        assertEquals(labels[3], FieldChrome.compassLabel(-13))
    }

    @Test
    fun `unit labels are total and distinct`() {
        val wind = WindUnit.entries.map { FieldChrome.windUnitLabel(it) }
        assertEquals(WindUnit.entries.size, wind.toSet().size)
        assertNotEquals(
            FieldChrome.tempUnitLabel(TempUnit.CELSIUS),
            FieldChrome.tempUnitLabel(TempUnit.FAHRENHEIT),
        )
    }

    // ---- row width budgeting -------------------------------------------------------------------

    @Test
    fun `rowWidthPx sums fixed dp and every measured run`() {
        assertEquals(38, FieldChrome.rowWidthPx(density = 1.875f, fixedDp = 20))
        assertEquals(38 + 120 + 40, FieldChrome.rowWidthPx(1.875f, 20, 120, 40))
    }

    @Test
    fun `rowWidthPx ignores a negative run and a negative fixed width`() {
        assertEquals(120, FieldChrome.rowWidthPx(1.875f, -8, 120, -40))
    }

    @Test
    fun `rowWidthPx treats a non-positive density as one`() {
        assertEquals(20 + 120, FieldChrome.rowWidthPx(density = 0f, fixedDp = 20, 120))
    }

    @Test
    fun `rowBudgetPx subtracts both paddings`() {
        val plain = observed()
        assertEquals(478 - 8, FieldChrome.rowBudgetPx(plain, DENSITY))
        val bounded = config(60 to 15, 478 to 148, textSize = 69, boundaries = true)
        assertEquals(478 - 15, FieldChrome.rowBudgetPx(bounded, DENSITY))
    }

    @Test
    fun `rowBudgetPx never goes negative on a degenerate viewSize`() {
        assertEquals(0, FieldChrome.rowBudgetPx(config(30 to 30, 0 to 0), DENSITY))
    }

    /**
     * A row that overruns its budget by a single pixel must be reported as not fitting: the caller
     * sheds an element on `false`, and Glance wraps or clips on the pixel we let through.
     */
    @Test
    fun `rowFits is exact at the budget boundary`() {
        val observed = observed()
        val budget = FieldChrome.rowBudgetPx(observed, DENSITY)
        assertTrue(FieldChrome.rowFits(observed, DENSITY, budget))
        assertFalse(FieldChrome.rowFits(observed, DENSITY, budget + 1))
    }

    /**
     * The geometry the two overrunning rows were observed at: the `weather-now` icon box and the
     * `wind` arrow have to leave a usable remainder of the 478 px panel for text.
     */
    @Test
    fun `the observed field leaves most of its width to text`() {
        val observed = observed()
        assertEquals(33, FieldChrome.iconBoxDp(observed, DENSITY))
        assertEquals(48, FieldChrome.arrowSizePx(observed))
        val chrome = FieldChrome.rowWidthPx(DENSITY, 33 + 8 + 12 + 15 + 4 + 2)
        assertTrue(chrome < FieldChrome.rowBudgetPx(observed, DENSITY) / 2)
    }

    /** The `startView` geometry the Karoo 3 reports for a full-width, quarter-height field. */
    private fun observed() = config(60 to 15, 478 to 148, textSize = 69)

    private companion object {
        /** 300 dpi, as `dumpsys display` reports for the Karoo 3. */
        const val DENSITY = 1.875f
    }
}
