package io.github.glandais.karoo.weather.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.glandais.karoo.weather.R
import io.github.glandais.karoo.weather.domain.Units
import io.github.glandais.karoo.weather.domain.WeatherSample
import io.github.glandais.karoo.weather.route.RelativeWind
import io.github.glandais.karoo.weather.ui.asColor
import io.github.glandais.karoo.weather.ui.theme.Wx
import io.github.glandais.karoo.weather.util.Numbers
import io.github.glandais.karoo.weather.util.TimeFormat
import io.github.glandais.karoo.weather.util.compassLabel
import io.github.glandais.karoo.weather.util.tempUnitLabel
import io.github.glandais.karoo.weather.util.windUnitLabel
import io.github.glandais.karoo.weather.weather.WmoIcons

/**
 * The conditions at the rider's own position (DESIGN §5, Now tab).
 *
 * The wind arrow here is drawn against an absolute compass frame — it points where the wind is
 * going — because the Now tab has no travel direction to relate it to. It is therefore deliberately
 * NOT coloured on the head/tail scale: `Wx.windTail` green on this card would claim a tailwind that
 * nothing has computed (DESIGN §1.2, one colour one meaning). The route rows, which do have a
 * travel bearing, are the only place that scale appears.
 */
@Composable
fun CurrentCard(
    sample: WeatherSample,
    units: Units,
    rainNext2hMm: Double,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {

            // Row 1 — icon, temperature, clock.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painter = painterResource(WmoIcons.fieldForCode(sample.wmoCode, sample.isDay)),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(40.dp),
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = Numbers.temp(sample.temp, units),
                    style = MaterialTheme.typography.displaySmall,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = Wx.forTemp(sample.temp).asColor(),
                )
                Text(
                    text = stringResource(tempUnitLabel(units.temp)),
                    style = MaterialTheme.typography.titleMedium,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 2.dp),
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = TimeFormat.clock(sample.time),
                    style = MaterialTheme.typography.titleMedium,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Row 2 — apparent temperature, only when the provider sent one.
            sample.apparentTemp?.let { feels ->
                Spacer(Modifier.height(2.dp))
                Text(
                    text =
                        stringResource(R.string.label_feels_short) +
                            " " +
                            Numbers.temp(feels, units) +
                            stringResource(tempUnitLabel(units.temp)),
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Default,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(10.dp))

            // Row 3 — wind: arrow (blowing towards), mean, gusts, origin.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                WindArrow(
                    angleDeg = sample.windToDir.toFloat(),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(24.dp),
                )
                Text(
                    text = Numbers.wind(sample.windSpeed, units),
                    style = MaterialTheme.typography.titleMedium,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = stringResource(windUnitLabel(units.wind)),
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text =
                        stringResource(R.string.label_gust_short) +
                            " " +
                            Numbers.wind(sample.windGusts, units),
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text =
                        stringResource(R.string.label_from) +
                            " " +
                            stringResource(compassLabel(RelativeWind.compassIndex(sample.windDir))),
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Default,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(8.dp))

            // Row 4 — rain: probability now, accumulation over the nowcast window.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_umbrella),
                    contentDescription = null,
                    tint = Wx.forRain(rainNext2hMm / NOWCAST_BUCKETS_PER_2H).asColor(),
                    modifier = Modifier.size(20.dp),
                )
                sample.precipProb?.let { prob ->
                    Text(
                        text = Numbers.percent(prob) + stringResource(R.string.unit_percent),
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                Text(
                    // Both the nowcast and the Now tab's hourly fallback span two hours here.
                    text =
                        stringResource(
                            R.string.rain_total_window,
                            Numbers.mm(rainNext2hMm),
                            TWO_HOUR_WINDOW,
                        ),
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Default,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** The two-hour nowcast is eight quarter-hours; `Wx.forRain` wants one of them. */
private const val TWO_HOUR_WINDOW = "2"

private const val NOWCAST_BUCKETS_PER_2H = 8.0
