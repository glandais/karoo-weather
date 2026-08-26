package io.github.glandais.karoo.weather.ui.theme

import io.github.glandais.karoo.weather.domain.WindClass

/**
 * A (day, night) colour pair as plain ARGB longs.
 *
 * Deliberately free of `androidx.glance` and `androidx.compose` imports so that both renderers —
 * Glance elements via `ColorProvider(Color(day), Color(night))` and Canvas bitmaps via [pick] —
 * consume the same source of truth (DESIGN §1.1).
 */
data class ColorPair(val day: Long, val night: Long) {
    fun pick(night: Boolean): Int = (if (night) this.night else day).toInt()
}

/**
 * The single source of colour truth for the extension.
 *
 * Alert colours are NOT here: `InRideAlert.backgroundColor` / `textColor` are `@ColorRes`, so they
 * live only in `res/values/colors.xml` and `res/values-night/colors.xml`.
 */
object Wx {
    // Base
    val bg = ColorPair(0xFFFFFFFF, 0xFF000000)
    val fg = ColorPair(0xFF000000, 0xFFFFFFFF)
    /** Labels, units, axes, probability line. */
    val fgMuted = ColorPair(0xFF5A5A5A, 0xFFB0B0B0)
    val divider = ColorPair(0xFFD0D0D0, 0xFF3A3A3A)

    // Temperature ramp — blue -> neutral -> amber -> red. NO GREEN: green is reserved for tailwind.
    /** < 0 °C */
    val tempFreezing = ColorPair(0xFF2E63B8, 0xFF6FA8FF)
    /** 0..10 °C */
    val tempCold = ColorPair(0xFF3E86C4, 0xFF7FC4FF)
    /** 10..20 °C — equals [fg] on purpose: mild weather needs no colour. */
    val tempMild = ColorPair(0xFF000000, 0xFFFFFFFF)
    /** 20..28 °C */
    val tempWarm = ColorPair(0xFFB07200, 0xFFFFC048)
    /** 28 °C */
    val tempHot = ColorPair(0xFFB3341F, 0xFFFF7A5C)

    // Wind / headwind semantics. GREEN IS RESERVED FOR THIS SCALE AND NOTHING ELSE.
    val windTail = ColorPair(0xFF008000, 0xFF00E000)
    val windCalm = ColorPair(0xFF5A5A5A, 0xFFB0B0B0)
    val windCross = ColorPair(0xFFBB4300, 0xFFFF9930)
    val windHead = ColorPair(0xFFA30000, 0xFFFF5454)

    // Rain (bars, route wet cells)
    val rainLight = ColorPair(0xFF7FB3DC, 0xFF4E86B8)
    val rainMed = ColorPair(0xFF3D7FB5, 0xFF6FB5EA)
    val rainHeavy = ColorPair(0xFF1B4F7A, 0xFF9FD4FF)

    /** Temperature ramp. Buckets: `< 0` · `0..10` · `10..20` · `20..28` · `> 28` (DESIGN §1.1). */
    fun forTemp(celsius: Double): ColorPair =
        when {
            celsius < 0.0 -> tempFreezing
            celsius < 10.0 -> tempCold
            celsius < 20.0 -> tempMild
            celsius <= 28.0 -> tempWarm
            else -> tempHot
        }

    /**
     * Signed headwind component, m/s, positive = headwind. Buckets: `<= -2.8` tail · `-2.8..1.4`
     * calm · `1.4..4.2` cross · `> 4.2` head (DESIGN §1.1).
     */
    fun forHeadwind(headwindMs: Double): ColorPair =
        when {
            headwindMs <= -2.8 -> windTail
            headwindMs <= 1.4 -> windCalm
            headwindMs <= 4.2 -> windCross
            else -> windHead
        }

    /**
     * Rain intensity in mm per 15 minutes. Buckets: `< 0.1` none · `0.1..0.5` light · `0.5..2.0`
     * med · `> 2.0` heavy (DESIGN §1.1). "None" is drawn in [fgMuted]: there is no rain colour for
     * no rain.
     */
    fun forRain(mmPerQuarterHour: Double): ColorPair =
        when {
            mmPerQuarterHour < 0.1 -> fgMuted
            mmPerQuarterHour < 0.5 -> rainLight
            mmPerQuarterHour <= 2.0 -> rainMed
            else -> rainHeavy
        }

    fun forWindClass(cls: WindClass): ColorPair =
        when (cls) {
            WindClass.TAIL -> windTail
            WindClass.CROSS -> windCross
            WindClass.HEAD -> windHead
        }
}
