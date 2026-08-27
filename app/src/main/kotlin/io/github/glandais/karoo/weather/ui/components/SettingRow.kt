package io.github.glandais.karoo.weather.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Section title inside the Settings tab (DESIGN §5). */
@Composable
fun SettingSectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        fontFamily = FontFamily.Default,
        modifier =
            modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp),
    )
}

/**
 * One settings entry: the label on its own line, the control right-aligned underneath.
 *
 * The Karoo panel is 480 px at 300 dpi, i.e. only 256 dp wide — a label and a dropdown cannot share
 * a line without one of them wrapping, so the two are stacked (this is also how Karoo's own
 * settings lists look). Switch rows stay inline via [SettingInlineRow] because a switch is narrow
 * enough.
 */
@Composable
fun SettingRow(
    title: String,
    modifier: Modifier = Modifier,
    control: @Composable RowScope.() -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            fontFamily = FontFamily.Default,
            fontSize = 16.sp,
            modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
        )
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.End,
        ) {
            control()
        }
    }
}

/** Label left, control right on one line — only for controls narrow enough to fit (switches). */
@Composable
fun SettingInlineRow(
    title: String,
    modifier: Modifier = Modifier,
    control: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            fontFamily = FontFamily.Default,
            fontSize = 16.sp,
            modifier = Modifier.weight(1f).padding(end = 8.dp),
        )
        control()
    }
}

/**
 * A switch row. The whole row is the touch target, not just the 32 dp thumb — the switch alone is
 * well under the 48 dp floor of DESIGN §7.
 */
@Composable
fun SettingSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    SettingInlineRow(
        title = title,
        modifier = modifier.clickable(enabled = enabled) { onCheckedChange(!checked) },
    ) {
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}
