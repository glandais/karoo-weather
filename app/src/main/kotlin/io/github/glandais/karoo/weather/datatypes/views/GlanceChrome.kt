package io.github.glandais.karoo.weather.datatypes.views

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import io.github.glandais.karoo.weather.ui.theme.ColorPair
import io.hammerhead.karooext.models.ViewConfig
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The Glance-side counterpart of [FieldChrome].
 *
 * It is kept separate so [FieldChrome] stays free of `androidx.glance` and `androidx.compose`
 * imports: `FieldChrome` is also read by the Canvas builders, which cannot resolve a
 * `ColorProvider` at all (ARCHITECTURE §7.5).
 */
object GlanceChrome {

    /** A [ColorPair] as the day/night `ColorProvider` Glance resolves by itself. */
    fun provider(pair: ColorPair): androidx.glance.unit.ColorProvider =
        ColorProvider(day = Color(pair.day), night = Color(pair.night))

    fun sp(value: Float): TextUnit = TextUnit(value, TextUnitType.Sp)

    /** `ViewConfig.alignment` mapped to Glance horizontal alignment (DESIGN §1.5). */
    fun horizontal(alignment: ViewConfig.Alignment): Alignment.Horizontal =
        when (alignment) {
            ViewConfig.Alignment.LEFT -> Alignment.Start
            ViewConfig.Alignment.CENTER -> Alignment.CenterHorizontally
            ViewConfig.Alignment.RIGHT -> Alignment.End
        }

    /** `14h` style label for an hourly outlook column. */
    fun hourLabel(epochSec: Long): String =
        SimpleDateFormat("HH'h'", Locale.getDefault()).format(Date(epochSec * 1000L))

    /** `14:03` style clock label for an ETA. */
    fun clockLabel(epochSec: Long): String =
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(epochSec * 1000L))
}
