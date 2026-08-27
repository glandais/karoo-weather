package io.github.glandais.karoo.weather.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.glandais.karoo.weather.R
import io.github.glandais.karoo.weather.domain.WeatherSnapshot
import io.github.glandais.karoo.weather.util.TimeFormat

/**
 * The one banner the Now tab shows above the data, per DESIGN §6.
 *
 * Rules encoded here:
 * - A rider is never shown an exception or an HTTP status; every branch is one short sentence.
 * - A permanent error (`retryable == false`) outranks staleness: retrying will not fix it, but the
 *   rider still gets the button, because the fix may be "wait until you have signal again".
 * - With cached data present the banner is informational and the data stays on screen; the app
 *   never blanks out values it already has (DESIGN §6, "Loading, cache present").
 */
@Composable
fun StateBanner(
    snapshot: WeatherSnapshot,
    nowSec: Long,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val error = snapshot.error
    val fetchedAt = snapshot.bundle?.fetchedAt

    when {
        error != null && !error.retryable ->
            Banner(
                text = stringResource(R.string.state_no_data),
                tone = BannerTone.ERROR,
                onRetry = onRetry,
                modifier = modifier,
            )
        error != null && fetchedAt != null ->
            Banner(
                text = stringResource(R.string.app_offline_banner, TimeFormat.clock(fetchedAt)),
                tone = BannerTone.WARNING,
                onRetry = onRetry,
                modifier = modifier,
            )
        error != null ->
            Banner(
                text = stringResource(R.string.state_no_data),
                tone = BannerTone.WARNING,
                onRetry = onRetry,
                modifier = modifier,
            )
        snapshot.position == null && !snapshot.hasData ->
            Banner(
                text = stringResource(R.string.state_no_gps),
                tone = BannerTone.NEUTRAL,
                onRetry = null,
                modifier = modifier,
            )
        fetchedAt != null && snapshot.isStale(nowSec) ->
            Banner(
                text = stringResource(R.string.app_offline_banner, TimeFormat.clock(fetchedAt)),
                tone = BannerTone.WARNING,
                onRetry = onRetry,
                modifier = modifier,
            )
        else -> Unit
    }
}

private enum class BannerTone {
    NEUTRAL,
    WARNING,
    ERROR,
}

@Composable
private fun Banner(
    text: String,
    tone: BannerTone,
    onRetry: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val container: Color =
        when (tone) {
            BannerTone.NEUTRAL -> MaterialTheme.colorScheme.surfaceVariant
            BannerTone.WARNING -> MaterialTheme.colorScheme.surfaceVariant
            BannerTone.ERROR -> MaterialTheme.colorScheme.errorContainer
        }
    val content: Color =
        when (tone) {
            BannerTone.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
            BannerTone.WARNING -> MaterialTheme.colorScheme.onSurfaceVariant
            BannerTone.ERROR -> MaterialTheme.colorScheme.onErrorContainer
        }

    Card(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = container, contentColor = content),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Default,
                modifier = Modifier.weight(1f).padding(vertical = 8.dp),
            )
            if (onRetry != null) {
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.app_retry))
                }
            }
        }
    }
}

/**
 * The full-panel empty state used by the Route tab and by the Now tab before the first fetch
 * (DESIGN §6): one icon, one title, one sentence. No spinner — a spinner over a screen that may
 * stay empty for minutes reads as a hang.
 */
@Composable
fun InfoState(@DrawableRes iconRes: Int, title: String, body: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(48.dp),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontFamily = FontFamily.Default,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Default,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
