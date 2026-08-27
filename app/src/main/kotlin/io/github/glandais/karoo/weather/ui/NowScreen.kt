package io.github.glandais.karoo.weather.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.glandais.karoo.weather.R
import io.github.glandais.karoo.weather.domain.WeatherSnapshot
import io.github.glandais.karoo.weather.ui.components.CurrentCard
import io.github.glandais.karoo.weather.ui.components.HourlyStrip
import io.github.glandais.karoo.weather.ui.components.RefreshButton
import io.github.glandais.karoo.weather.ui.components.StateBanner
import io.github.glandais.karoo.weather.util.AppLiterals
import io.github.glandais.karoo.weather.util.TimeFormat
import io.github.glandais.karoo.weather.weather.Interpolation

private const val HOUR_SEC = 3_600L

/** Buckets covering the next two hours: eight quarter-hours, or two hours from the hourly series. */
private const val NOWCAST_BUCKETS = 8
private const val HOURLY_FALLBACK_BUCKETS = 2

/**
 * The Now tab (DESIGN §5).
 *
 * Everything it renders is a pure function of [snapshot] and [nowSec]; it never touches the
 * repository. The "current" sample is the hourly series interpolated to now, falling back to the
 * provider's own `current` observation — the same rule `WeatherRepository.sampleNow` applies, kept
 * here so the composable stays testable by inspection and recomposes with the clock.
 */
@Composable
fun NowScreen(
    snapshot: WeatherSnapshot,
    nowSec: Long,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val here = snapshot.bundle?.here

    val sample =
        remember(here, nowSec) { here?.let { Interpolation.sampleAt(it.hourly, nowSec) ?: it.current } }

    val rainNext2hMm =
        remember(here, nowSec) {
            val nowcast = here?.let { Interpolation.bucketsFrom(it.minutely15, nowSec, NOWCAST_BUCKETS) }
            val buckets =
                if (!nowcast.isNullOrEmpty()) nowcast
                else
                    here?.let {
                        Interpolation.hourlyToBuckets(
                            it.hourly,
                            nowSec - HOUR_SEC,
                            HOURLY_FALLBACK_BUCKETS,
                        )
                    }
            buckets.orEmpty().sumOf { it.mm }
        }

    Column(modifier = modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(R.string.tab_now),
                style = MaterialTheme.typography.headlineSmall,
                fontFamily = FontFamily.Default,
            )
            RefreshButton(onClick = onRefresh, enabled = snapshot.consentAccepted)
        }

        // Loading with cache present is an inline bar under the button; the values stay on screen.
        if (snapshot.loading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
        }

        StateBanner(snapshot = snapshot, nowSec = nowSec, onRetry = onRefresh)

        if (sample == null) {
            EmptyNow(loading = snapshot.loading)
        } else {
            CurrentCard(sample = sample, units = snapshot.units, rainNext2hMm = rainNext2hMm)
            Spacer(Modifier.height(8.dp))
            HourlyStrip(hourly = here?.hourly.orEmpty(), units = snapshot.units, nowSec = nowSec)
        }

        Spacer(Modifier.height(12.dp))
        Footer(snapshot = snapshot, nowSec = nowSec)
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun EmptyNow(loading: Boolean) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_weather),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(48.dp),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(if (loading) R.string.state_loading else R.string.state_no_data),
            style = MaterialTheme.typography.titleMedium,
            fontFamily = FontFamily.Default,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * "Updated 3 min ago · Weather data by Open-Meteo.com (CC BY 4.0)".
 *
 * The attribution is not optional and not only in About: Open-Meteo's CC BY 4.0 terms require it
 * wherever the data is shown (ARCHITECTURE §1.3), so it rides along with the freshness line on the
 * one screen that always displays a value.
 */
@Composable
private fun Footer(snapshot: WeatherSnapshot, nowSec: Long) {
    val updated =
        snapshot.lastSuccessAt?.let {
            stringResource(R.string.app_updated_ago, TimeFormat.ago(nowSec, it)) + AppLiterals.SEPARATOR
        } ?: ""
    Text(
        text = updated + stringResource(R.string.attribution_open_meteo),
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Default,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    )
}
