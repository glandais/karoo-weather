package io.github.glandais.karoo.weather.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.glandais.karoo.weather.BuildConfig
import io.github.glandais.karoo.weather.R

/**
 * Attribution and version (DESIGN §5).
 *
 * `attribution_open_meteo` is a licence obligation, not a courtesy: Open-Meteo's free tier is CC BY
 * 4.0 and requires the credit wherever the data appears (ARCHITECTURE §1.3). It is rendered here in
 * full and, in short form, on the Now and Route tabs.
 */
@Composable
fun AboutSection(modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            text = stringResource(R.string.attribution_open_meteo),
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Default,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.icon_credits),
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Default,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.version_label, BuildConfig.VERSION_NAME),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
