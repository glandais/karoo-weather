package io.github.glandais.karoo.weather.karoo

import io.github.glandais.karoo.weather.domain.DistanceUnit
import io.github.glandais.karoo.weather.domain.TempUnit
import io.github.glandais.karoo.weather.domain.Units
import io.github.glandais.karoo.weather.domain.WeatherSettings
import io.github.glandais.karoo.weather.domain.WindUnit
import io.hammerhead.karooext.models.UserProfile

/**
 * Display units for the rider: the Karoo profile decides, unless the user overrode a unit in our
 * own settings (`null` in [WeatherSettings] means "follow the profile", ARCHITECTURE §10).
 */
fun UserProfile.toUnits(settings: WeatherSettings): Units {
    val imperialTemp = preferredUnit.temperature == UserProfile.PreferredUnit.UnitType.IMPERIAL
    val imperialDistance = preferredUnit.distance == UserProfile.PreferredUnit.UnitType.IMPERIAL
    return Units(
        temp = settings.tempUnit ?: if (imperialTemp) TempUnit.FAHRENHEIT else TempUnit.CELSIUS,
        wind = settings.windUnit ?: if (imperialDistance) WindUnit.MPH else WindUnit.KMH,
        distance = if (imperialDistance) DistanceUnit.MILES else DistanceUnit.KM,
    )
}
