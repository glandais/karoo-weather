package io.github.glandais.karoo.weather

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import io.github.glandais.karoo.weather.ui.WeatherApp

/**
 * The companion app. `WeatherApp` applies `AppTheme` itself and holds its own repository
 * attach/detach for the composition's lifetime, so this activity carries no state of its own and
 * never constructs a `KarooSystemService`.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { WeatherApp(onClose = { finish() }) }
    }
}
