package io.github.glandais.karoo.weather.data

import android.content.Context

/**
 * The one repository instance for the process.
 *
 * `WeatherExtension` (the service) and `MainActivity` run in the same process — the manifest sets
 * no `android:process` — so this really is a singleton, and nothing we own crosses a Binder inside
 * our own app (ARCHITECTURE §4.1).
 */
object WeatherGraph {

    @Volatile private var repo: WeatherRepository? = null

    fun repository(context: Context): WeatherRepository =
        repo
            ?: synchronized(this) {
                repo
                    ?: WeatherRepository(
                            appContext = context.applicationContext,
                            settingsStore = SettingsStore(context.applicationContext),
                            cache = ForecastCache(context.applicationContext),
                        )
                        .also { repo = it }
            }
}
