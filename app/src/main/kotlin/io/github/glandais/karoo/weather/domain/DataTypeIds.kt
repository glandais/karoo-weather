package io.github.glandais.karoo.weather.domain

object DataTypeIds {
    const val EXTENSION = "karoo-weather"

    const val WEATHER_NOW = "weather-now"
    const val TEMPERATURE = "temperature"
    const val WIND = "wind"
    const val RAIN_NEXT_HOUR = "rain-next-hour"
    const val ROUTE_FORECAST = "route-forecast"

    val ALL = listOf(WEATHER_NOW, TEMPERATURE, WIND, RAIN_NEXT_HOUR, ROUTE_FORECAST)

    fun full(typeId: String) = "TYPE_EXT::$EXTENSION::$typeId"
}
