package io.github.glandais.karoo.weather.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.glandais.karoo.weather.R
import io.github.glandais.karoo.weather.domain.TempUnit
import io.github.glandais.karoo.weather.domain.WeatherSettings
import io.github.glandais.karoo.weather.domain.WindUnit
import io.github.glandais.karoo.weather.ui.components.Dropdown
import io.github.glandais.karoo.weather.ui.components.DropdownOption
import io.github.glandais.karoo.weather.ui.components.SettingRow
import io.github.glandais.karoo.weather.ui.components.SettingSectionHeader
import io.github.glandais.karoo.weather.ui.components.SettingSwitchRow
import io.github.glandais.karoo.weather.util.AppLiterals
import io.github.glandais.karoo.weather.util.tempUnitLabel
import io.github.glandais.karoo.weather.util.windUnitLabel

/** Refresh interval options, minutes. */
private val REFRESH_MINUTES = listOf(15, 30, 60, 120)

/** Privacy-grid options, km. `roundLocationKm` is a Double in the settings record. */
private val PRIVACY_KM = listOf(1.0, 3.0, 5.0, 10.0)

/** Field-repaint options, ms. The effective value is still floored by `viewRefreshMs(settings)`. */
private val VIEW_REFRESH_MS = listOf(1_000L, 2_000L, 5_000L)

private const val SPEED_MIN_KMH = 5
private const val SPEED_MAX_KMH = 60

/**
 * The Settings tab (DESIGN §5).
 *
 * Switches and dropdowns persist on change; the one text field persists on focus loss and on the
 * IME "done" action, clamped on save. Nothing here blocks: [onUpdate] hands the transform to the
 * view model, which launches it — `«src»/ui` contains no `runBlocking` (PLAN WP5).
 */
@Composable
fun SettingsScreen(
    settings: WeatherSettings,
    onUpdate: ((WeatherSettings) -> WeatherSettings) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState())) {

        SettingSectionHeader(stringResource(R.string.settings_units))

        val followKaroo = stringResource(R.string.settings_follow_karoo)

        // The option lists are spelled out rather than mapped over `entries` so that every
        // `stringResource` call sits directly in the composable body. `null` means "follow the
        // Karoo UserProfile", which is the default and the first entry.
        val tempOptions =
            listOf(
                DropdownOption<TempUnit?>(null, followKaroo),
                DropdownOption<TempUnit?>(
                    TempUnit.CELSIUS,
                    stringResource(tempUnitLabel(TempUnit.CELSIUS)),
                ),
                DropdownOption<TempUnit?>(
                    TempUnit.FAHRENHEIT,
                    stringResource(tempUnitLabel(TempUnit.FAHRENHEIT)),
                ),
            )
        val windOptions =
            listOf(
                DropdownOption<WindUnit?>(null, followKaroo),
                DropdownOption<WindUnit?>(WindUnit.KMH, stringResource(windUnitLabel(WindUnit.KMH))),
                DropdownOption<WindUnit?>(WindUnit.MPH, stringResource(windUnitLabel(WindUnit.MPH))),
                DropdownOption<WindUnit?>(WindUnit.MS, stringResource(windUnitLabel(WindUnit.MS))),
                DropdownOption<WindUnit?>(
                    WindUnit.KNOTS,
                    stringResource(windUnitLabel(WindUnit.KNOTS)),
                ),
                DropdownOption<WindUnit?>(
                    WindUnit.BEAUFORT,
                    stringResource(windUnitLabel(WindUnit.BEAUFORT)),
                ),
            )

        SettingRow(title = stringResource(R.string.settings_temp_unit)) {
            Dropdown(
                options = tempOptions,
                selected = settings.tempUnit,
                onSelect = { value -> onUpdate { it.copy(tempUnit = value) } },
            )
        }

        SettingRow(title = stringResource(R.string.settings_wind_unit)) {
            Dropdown(
                options = windOptions,
                selected = settings.windUnit,
                onSelect = { value -> onUpdate { it.copy(windUnit = value) } },
            )
        }

        SettingSectionHeader(stringResource(R.string.settings_route))

        SettingSwitchRow(
            title = stringResource(R.string.settings_use_measured_speed),
            checked = settings.useMeasuredSpeed,
            onCheckedChange = { value -> onUpdate { it.copy(useMeasuredSpeed = value) } },
        )

        AssumedSpeedRow(
            assumedSpeedKmh = settings.assumedSpeedKmh,
            onCommit = { value -> onUpdate { it.copy(assumedSpeedKmh = value) } },
        )

        SettingSectionHeader(stringResource(R.string.settings_updates))

        SettingRow(title = stringResource(R.string.settings_refresh_every)) {
            Dropdown(
                options = REFRESH_MINUTES.map { DropdownOption(it, "$it ${AppLiterals.MINUTES}") },
                selected = settings.refreshMinutes,
                onSelect = { value -> onUpdate { it.copy(refreshMinutes = value) } },
            )
        }

        SettingRow(title = stringResource(R.string.settings_location_privacy)) {
            Dropdown(
                options =
                    PRIVACY_KM.map {
                        DropdownOption(it, "${it.toInt()} ${AppLiterals.KILOMETRES}")
                    },
                selected = settings.roundLocationKm,
                onSelect = { value -> onUpdate { it.copy(roundLocationKm = value) } },
            )
        }

        SettingRow(title = stringResource(R.string.settings_view_refresh)) {
            Dropdown(
                options =
                    VIEW_REFRESH_MS.map {
                        DropdownOption(it, "${it / 1_000} ${AppLiterals.SECONDS}")
                    },
                selected = settings.viewRefreshMs,
                onSelect = { value -> onUpdate { it.copy(viewRefreshMs = value) } },
            )
        }

        SettingSectionHeader(stringResource(R.string.settings_on_bike))

        SettingSwitchRow(
            title = stringResource(R.string.settings_map_layer),
            checked = settings.mapLayerEnabled,
            onCheckedChange = { value -> onUpdate { it.copy(mapLayerEnabled = value) } },
        )

        SettingSwitchRow(
            title = stringResource(R.string.settings_rain_alert),
            checked = settings.rainAlertEnabled,
            onCheckedChange = { value -> onUpdate { it.copy(rainAlertEnabled = value) } },
        )

        SettingSectionHeader(stringResource(R.string.settings_about))
        AboutSection()
        Spacer(Modifier.height(24.dp))
    }
}

/**
 * The one free-text setting.
 *
 * The edit buffer is local so a half-typed "2" never reaches the store as 2 km/h; it is committed —
 * and clamped to the same 5..60 range `WeatherSettings.assumedSpeedMs()` enforces — when the field
 * loses focus or the rider presses Done. `hadFocus` guards against the initial `onFocusChanged`
 * callback, which fires with `isFocused == false` before the field is ever touched.
 */
@Composable
private fun AssumedSpeedRow(assumedSpeedKmh: Int, onCommit: (Int) -> Unit) {
    val focusManager = LocalFocusManager.current
    var text by remember(assumedSpeedKmh) { mutableStateOf(assumedSpeedKmh.toString()) }
    var hadFocus by remember { mutableStateOf(false) }

    fun commit() {
        val parsed = text.toIntOrNull() ?: assumedSpeedKmh
        val clamped = parsed.coerceIn(SPEED_MIN_KMH, SPEED_MAX_KMH)
        text = clamped.toString()
        if (clamped != assumedSpeedKmh) onCommit(clamped)
    }

    SettingRow(title = stringResource(R.string.settings_assumed_speed)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = { raw -> text = raw.filter { it.isDigit() }.take(3) },
                singleLine = true,
                textStyle =
                    MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                keyboardOptions =
                    KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                keyboardActions =
                    KeyboardActions(
                        onDone = {
                            commit()
                            focusManager.clearFocus()
                        }
                    ),
                modifier =
                    Modifier.width(96.dp).onFocusChanged { focusState ->
                        if (focusState.isFocused) {
                            hadFocus = true
                        } else if (hadFocus) {
                            hadFocus = false
                            commit()
                        }
                    },
            )
            Text(
                text = stringResource(R.string.unit_kmh),
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
