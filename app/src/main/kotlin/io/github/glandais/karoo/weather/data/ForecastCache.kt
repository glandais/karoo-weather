package io.github.glandais.karoo.weather.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.glandais.karoo.weather.domain.ForecastBundle
import io.github.glandais.karoo.weather.domain.GeoPoint
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

private val Context.cacheDataStore: DataStore<Preferences> by
    preferencesDataStore(name = "karoo_weather_cache")

/**
 * Last successful fetch and last known position, persisted so that after process death the fields
 * repaint from cache within one frame instead of showing "searching" (ARCHITECTURE §5.6).
 */
class ForecastCache(context: Context) {

    private val store = context.applicationContext.cacheDataStore

    val bundle: Flow<ForecastBundle?> =
        store.data
            .catch { e ->
                if (e is IOException) emit(emptyPreferences()) else throw e
            }
            .map { decode<ForecastBundle>(it[BUNDLE]) }
            .distinctUntilChanged()

    val lastPosition: Flow<GeoPoint?> =
        store.data
            .catch { e ->
                if (e is IOException) emit(emptyPreferences()) else throw e
            }
            .map { decode<GeoPoint>(it[POSITION]) }
            .distinctUntilChanged()

    suspend fun save(bundle: ForecastBundle) {
        store.edit { it[BUNDLE] = json.encodeToString(bundle) }
    }

    suspend fun savePosition(point: GeoPoint) {
        store.edit { it[POSITION] = json.encodeToString(point) }
    }

    private inline fun <reified T> decode(raw: String?): T? {
        if (raw.isNullOrBlank()) return null
        return try {
            json.decodeFromString<T>(raw)
        } catch (e: Exception) {
            null
        }
    }

    private companion object {
        val BUNDLE = stringPreferencesKey("bundle")
        val POSITION = stringPreferencesKey("position")
        val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }
}
