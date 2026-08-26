package io.github.glandais.karoo.weather.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class WeatherSettings(
    val consentAccepted: Boolean = false,
    /** null = follow UserProfile. */
    val tempUnit: TempUnit? = null,
    /** null = follow UserProfile. */
    val windUnit: WindUnit? = null,
    val assumedSpeedKmh: Int = 22,
    val useMeasuredSpeed: Boolean = true,
    val refreshMinutes: Int = 30,
    val roundLocationKm: Double = 3.0,
    val mapLayerEnabled: Boolean = true,
    val rainAlertEnabled: Boolean = false,
    /**
     * User preference only. The EFFECTIVE interval is KarooSystemService.viewRefreshMs(settings).
     */
    val viewRefreshMs: Long = 2_000L,
    val lastRefreshRequestedAt: Long? = null,
) {
    fun assumedSpeedMs(): Double = assumedSpeedKmh.coerceIn(5, 60) / 3.6

    companion object {
        val DEFAULT_JSON: String = Json.encodeToString(WeatherSettings())
    }
}
