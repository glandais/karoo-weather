package io.github.glandais.karoo.weather.domain

import kotlinx.serialization.Serializable

enum class TempUnit {
    CELSIUS,
    FAHRENHEIT,
}

/** [perMs] is the multiplier from m/s. BEAUFORT is handled by [Units.beaufort], not by [perMs]. */
enum class WindUnit(val perMs: Double) {
    MS(1.0),
    KMH(3.6),
    MPH(2.236936),
    KNOTS(1.943844),
    BEAUFORT(1.0),
}

enum class DistanceUnit(val perMetre: Double) {
    KM(0.001),
    MILES(0.000621371),
}

/** Wind direction relative to travel. */
enum class WindClass {
    TAIL,
    CROSS,
    HEAD,
}

/** Resolved display units. Built from UserProfile.preferredUnit unless the user overrode them. */
@Serializable
data class Units(
    val temp: TempUnit = TempUnit.CELSIUS,
    val wind: WindUnit = WindUnit.KMH,
    val distance: DistanceUnit = DistanceUnit.KM,
) {
    fun temp(celsius: Double): Double =
        if (temp == TempUnit.FAHRENHEIT) celsius * 9.0 / 5.0 + 32.0 else celsius

    fun wind(ms: Double): Double = if (wind == WindUnit.BEAUFORT) beaufort(ms) else ms * wind.perMs

    fun distance(metres: Double): Double = metres * distance.perMetre

    companion object {
        fun beaufort(ms: Double): Double =
            when {
                ms < 0.3 -> 0.0
                ms < 1.6 -> 1.0
                ms < 3.4 -> 2.0
                ms < 5.5 -> 3.0
                ms < 8.0 -> 4.0
                ms < 10.8 -> 5.0
                ms < 13.9 -> 6.0
                ms < 17.2 -> 7.0
                ms < 20.8 -> 8.0
                ms < 24.5 -> 9.0
                ms < 28.5 -> 10.0
                ms < 32.7 -> 11.0
                else -> 12.0
            }
    }
}
