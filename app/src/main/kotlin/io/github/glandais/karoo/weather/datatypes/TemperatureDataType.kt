package io.github.glandais.karoo.weather.datatypes

import android.content.Context
import io.github.glandais.karoo.weather.data.WeatherRepository
import io.github.glandais.karoo.weather.domain.DataTypeIds
import io.github.glandais.karoo.weather.domain.WeatherSnapshot
import io.github.glandais.karoo.weather.weather.Interpolation
import io.hammerhead.karooext.models.DataType

/**
 * Air temperature at the rider's position, in **degrees Celsius**.
 *
 * `graphical="false"`: Karoo renders it with its own numeric chrome and converts to the profile's
 * unit and precision from [DataType.Type.TEMPERATURE] (DESIGN §3.5). We never draw it ourselves.
 */
class TemperatureDataType(context: Context, repo: WeatherRepository) :
    NumericDataType(context, repo, DataTypeIds.TEMPERATURE) {

    override val formatDataTypeId: String = DataType.Type.TEMPERATURE

    override fun value(snapshot: WeatherSnapshot, nowSec: Long): Double? {
        val here = snapshot.bundle?.here ?: return null
        val sample = Interpolation.sampleAt(here.hourly, nowSec) ?: here.current ?: return null
        return sample.temp
    }
}
