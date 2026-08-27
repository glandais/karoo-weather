package io.github.glandais.karoo.weather.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.glandais.karoo.weather.R
import io.github.glandais.karoo.weather.domain.RouteForecast
import io.github.glandais.karoo.weather.domain.Units
import io.github.glandais.karoo.weather.domain.WeatherSnapshot
import io.github.glandais.karoo.weather.ui.components.InfoState
import io.github.glandais.karoo.weather.ui.components.RouteRow
import io.github.glandais.karoo.weather.util.AppLiterals
import io.github.glandais.karoo.weather.util.Distance
import io.github.glandais.karoo.weather.util.Numbers
import io.github.glandais.karoo.weather.util.TimeFormat
import io.github.glandais.karoo.weather.util.distanceAheadLabel

/**
 * The Route tab (DESIGN §5): one row per sampled point, newest first in travel order, with a header
 * that summarises the ride.
 *
 * There is no map here and never will be one: ARCHITECTURE ADR-0 rules out MapLibre and any raster
 * overlay, and the Karoo already draws the route on its own map page. A list is also the only form
 * of this data a rider can read at a glance while stopped at a junction.
 */
@Composable
fun RouteScreen(snapshot: WeatherSnapshot, modifier: Modifier = Modifier) {
    val route = snapshot.bundle?.route

    if (route == null || route.points.isEmpty()) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            InfoState(
                iconRes = R.drawable.ic_route,
                title = stringResource(R.string.app_no_route_title),
                body = stringResource(R.string.app_no_route_body),
            )
        }
        return
    }

    LazyColumn(modifier = modifier.fillMaxSize()) {
        item(key = "header") { RouteHeader(route = route, units = snapshot.units) }
        item(key = "header-divider") { HorizontalDivider() }
        items(items = route.points, key = { it.distanceAlong }) { point ->
            RouteRow(point = point, progress = route.progress, units = snapshot.units)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
        item(key = "footer") {
            Text(
                text = stringResource(R.string.attribution_open_meteo),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Default,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            )
        }
    }
}

@Composable
private fun RouteHeader(route: RouteForecast, units: Units) {
    val remaining = (route.routeDistance - route.progress).coerceAtLeast(0.0)
    val arrival = route.points.lastOrNull()?.eta
    val aheadRes = distanceAheadLabel(units.distance)

    // Every string resource is resolved before the builders run: `stringResource` is a composable
    // call and does not belong inside a string-building lambda.
    val remainingLabel = stringResource(aheadRes, Distance.format(remaining, units.distance))
    val mmLabel = stringResource(R.string.unit_mm)

    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Text(
            text = route.routeName,
            style = MaterialTheme.typography.titleMedium,
            fontFamily = FontFamily.Default,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text =
                if (arrival == null) remainingLabel
                else remainingLabel + AppLiterals.SEPARATOR + TimeFormat.clock(arrival),
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        val wetEta = route.firstWetEta
        if (wetEta != null) {
            val wetLabel = stringResource(R.string.rain_starts_at, TimeFormat.clock(wetEta))
            val wetDistanceLabel =
                route.firstWetDistance?.let { wetAt ->
                    stringResource(
                        aheadRes,
                        Distance.format((wetAt - route.progress).coerceAtLeast(0.0), units.distance),
                    )
                }
            val summary =
                listOfNotNull(
                        wetLabel,
                        wetDistanceLabel,
                        Numbers.mm(route.totalPrecipMm) + " " + mmLabel,
                    )
                    .joinToString(AppLiterals.SEPARATOR)

            Spacer(Modifier.height(6.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_umbrella),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Default,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}
