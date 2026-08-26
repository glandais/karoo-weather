package io.github.glandais.karoo.weather.domain

import kotlinx.serialization.Serializable

@Serializable data class GeoPoint(val lat: Double, val lon: Double)
