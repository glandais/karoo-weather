package io.github.glandais.karoo.weather.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.glandais.karoo.weather.domain.WeatherSettings
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

private val Context.settingsDataStore: DataStore<Preferences> by
    preferencesDataStore(name = "karoo_weather_settings")

/**
 * The user's settings, stored as one JSON string.
 *
 * Storing the whole object under a single key makes adding a field a free migration: unknown keys
 * are ignored on read and a missing key falls back to the data class default (ARCHITECTURE §10).
 */
class SettingsStore(context: Context) {

    private val store = context.applicationContext.settingsDataStore

    val settings: Flow<WeatherSettings> =
        store.data
            .catch { e ->
                if (e is IOException) emit(emptyPreferences()) else throw e
            }
            .map { decode(it[KEY]) }
            .distinctUntilChanged()

    suspend fun update(transform: (WeatherSettings) -> WeatherSettings) {
        store.edit { prefs ->
            prefs[KEY] = SettingsCodec.encode(transform(decode(prefs[KEY])))
        }
    }

    /**
     * Manual-refresh poke. The value is strictly increasing so that two pokes inside the same
     * millisecond still produce two distinct `RefreshKey`s.
     */
    suspend fun pokeRefresh() {
        val now = System.currentTimeMillis()
        update { current ->
            val previous = current.lastRefreshRequestedAt ?: 0L
            current.copy(lastRefreshRequestedAt = if (now > previous) now else previous + 1)
        }
    }

    private fun decode(raw: String?): WeatherSettings = SettingsCodec.decode(raw)

    private companion object {
        val KEY = stringPreferencesKey("settings")
    }
}

/**
 * JSON encoding of [WeatherSettings], extracted from [SettingsStore] so the serialization contract
 * is unit-testable without an Android `Context`.
 */
object SettingsCodec {

    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(settings: WeatherSettings): String = json.encodeToString(settings)

    /** Never throws: a corrupt or absent record falls back to the defaults. */
    fun decode(raw: String?): WeatherSettings {
        if (raw.isNullOrBlank()) return WeatherSettings()
        return try {
            json.decodeFromString<WeatherSettings>(raw)
        } catch (e: Exception) {
            WeatherSettings()
        }
    }
}
