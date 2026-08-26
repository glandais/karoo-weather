package io.github.glandais.karoo.weather.ui.theme

import android.content.Context
import android.content.res.Configuration

/**
 * True when the OS is in night mode. Canvas-drawn bitmaps must call this to pick a ColorPair side;
 * Glance elements use ColorProvider(day, night) and resolve automatically.
 */
fun isNightMode(context: Context): Boolean =
    (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
        Configuration.UI_MODE_NIGHT_YES
