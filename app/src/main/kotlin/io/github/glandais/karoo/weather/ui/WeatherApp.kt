package io.github.glandais.karoo.weather.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.glandais.karoo.weather.R
import io.github.glandais.karoo.weather.data.WeatherGraph
import kotlinx.coroutines.delay

/** How often the clock-derived parts of the UI (freshness, interpolated "now") are recomputed. */
private const val CLOCK_TICK_MS = 30_000L

private enum class AppTab(val titleRes: Int) {
    NOW(R.string.tab_now),
    ROUTE(R.string.tab_route),
    SETTINGS(R.string.tab_settings),
}

/**
 * The whole companion app. `MainActivity` calls exactly this and nothing else (PLAN WP5).
 *
 * It never constructs a `KarooSystemService`: [WeatherGraph] owns the single repository and the
 * repository owns the single service (ARCHITECTURE §4.2). All this composable contributes is one
 * ref count, held for exactly as long as the composition lives — which is also what makes the
 * extension service's own `attach` independent of whether the app is open.
 */
@Composable
fun WeatherApp(onClose: () -> Unit) {
    val context = LocalContext.current
    val repo = remember(context) { WeatherGraph.repository(context) }

    DisposableEffect(repo) {
        repo.attach()
        onDispose { repo.detach() }
    }

    val factory = remember(context) { WeatherViewModel.factory(context) }
    val viewModel: WeatherViewModel = viewModel(factory = factory)

    AppTheme { WeatherAppContent(viewModel = viewModel, onClose = onClose) }
}

@Composable
private fun WeatherAppContent(viewModel: WeatherViewModel, onClose: () -> Unit) {
    val snapshot by viewModel.state.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val nowSec by rememberNowSec()
    var selected by rememberSaveable { mutableIntStateOf(0) }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            TabRow(selectedTabIndex = selected, modifier = Modifier.height(56.dp)) {
                AppTab.entries.forEachIndexed { index, tab ->
                    Tab(
                        selected = index == selected,
                        onClick = { selected = index },
                        text = {
                            Text(
                                text = stringResource(tab.titleRes),
                                fontFamily = FontFamily.Default,
                            )
                        },
                    )
                }
            }

            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                when (AppTab.entries[selected.coerceIn(0, AppTab.entries.lastIndex)]) {
                    AppTab.NOW ->
                        NowScreen(
                            snapshot = snapshot,
                            nowSec = nowSec,
                            onRefresh = viewModel::refresh,
                        )
                    AppTab.ROUTE ->
                        RouteScreen(
                            snapshot = snapshot,
                            nowSec = nowSec,
                            onRefresh = viewModel::refresh,
                        )
                    AppTab.SETTINGS ->
                        SettingsScreen(settings = settings, onUpdate = viewModel::update)
                }
            }

            // Karoo has no system back bar, so the app carries its own (DESIGN §5/§7).
            Row(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onClose, modifier = Modifier.size(48.dp)) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.app_back),
                    )
                }
            }
        }
    }

    if (!settings.consentAccepted) {
        ConsentDialog(
            onAccept = { viewModel.update { it.copy(consentAccepted = true) } },
            onDecline = onClose,
        )
    }
}

/**
 * A coarse wall clock as composition state.
 *
 * The Now tab interpolates the hourly series to "now" and prints how long ago the last fetch
 * succeeded; without a tick both would freeze at whatever second the screen happened to open. 30 s
 * is far below the resolution of anything displayed and costs one recomposition of two text nodes.
 */
@Composable
private fun rememberNowSec(periodMs: Long = CLOCK_TICK_MS): State<Long> {
    val now = remember { mutableLongStateOf(System.currentTimeMillis() / 1000) }
    LaunchedEffect(periodMs) {
        while (true) {
            now.longValue = System.currentTimeMillis() / 1000
            delay(periodMs)
        }
    }
    return now
}
