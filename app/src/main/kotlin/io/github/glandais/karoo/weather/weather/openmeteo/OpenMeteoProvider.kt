package io.github.glandais.karoo.weather.weather.openmeteo

import io.github.glandais.karoo.weather.domain.GeoPoint
import io.github.glandais.karoo.weather.domain.HttpGateway
import io.github.glandais.karoo.weather.domain.HttpResult
import io.github.glandais.karoo.weather.domain.LocationForecast
import io.github.glandais.karoo.weather.domain.WeatherError
import io.github.glandais.karoo.weather.domain.WeatherProvider
import io.github.glandais.karoo.weather.domain.WeatherRequest

/**
 * Open-Meteo, called directly from the device over [HttpGateway].
 *
 * One cycle issues two requests (ARCHITECTURE §5.4):
 * * **A** — the whole point list in one call, hourly only. Mandatory; its failure fails the cycle.
 * * **B** — `current` + the 15-minute nowcast at `points[0]`. Cheap (~1.8 KB) and always issued
 *   when [WeatherRequest.includeNowcast]. Its failure is **non-fatal**: A's result is returned
 *   as-is, so the route forecast survives a nowcast outage.
 *
 * Nothing here touches Android or karoo-ext, so the whole class is exercised via a fake gateway.
 */
class OpenMeteoProvider(private val http: HttpGateway, private val userAgent: String) :
    WeatherProvider {

    override val id: String = "open-meteo"

    override suspend fun fetch(request: WeatherRequest): Result<List<LocationForecast>> {
        val points = withinSizeBudget(request)
        if (points.isEmpty()) {
            return Result.failure(WeatherErrorException(WeatherError.Parse("no_points")))
        }

        val batchBody =
            when (val outcome = fetchBody(OpenMeteoUrl.routeBatch(points, request.forecastHours))) {
                is Outcome.Failure -> return Result.failure(WeatherErrorException(outcome.error))
                is Outcome.Success -> outcome.body
            }
        val batch =
            try {
                OpenMeteoParser.parseBatch(batchBody, points.size)
            } catch (e: WeatherErrorException) {
                return Result.failure(e)
            }

        if (!request.includeNowcast) return Result.success(batch)

        val detail =
            fetchDetail(points.first(), request.forecastHours) ?: return Result.success(batch)
        val merged = batch.toMutableList()
        merged[0] = OpenMeteoParser.mergeDetailInto(merged[0], detail)
        return Result.success(merged.toList())
    }

    /**
     * Trims the point list until the estimated response fits [OpenMeteoUrl.SIZE_BUDGET_BYTES]; the
     * URL is never sent blind. The tail is dropped, so `forecasts[i]` still matches `points[i]` for
     * every forecast returned. With `WeatherRequest.MAX_POINTS == 25` this never actually trims.
     */
    private fun withinSizeBudget(request: WeatherRequest): List<GeoPoint> {
        val allowed =
            OpenMeteoUrl.maxPointsWithin(
                budgetBytes = OpenMeteoUrl.SIZE_BUDGET_BYTES,
                hourlyVars = OpenMeteoUrl.HOURLY_VARS.size,
                hours = request.forecastHours,
            )
        return request.points.take(allowed)
    }

    /** Request B. Returns null on any failure — the nowcast is optional by design. */
    private suspend fun fetchDetail(point: GeoPoint, forecastHours: Int): LocationForecast? {
        val body =
            when (val outcome = fetchBody(OpenMeteoUrl.hereDetail(point, forecastHours))) {
                is Outcome.Failure -> return null
                is Outcome.Success -> outcome.body
            }
        return try {
            OpenMeteoParser.parseDetail(body)
        } catch (e: WeatherErrorException) {
            null
        }
    }

    /** Performs one GET and normalises transport status, empty bodies and oversize bodies. */
    private suspend fun fetchBody(url: String): Outcome =
        when (val result = http.get(url, mapOf("User-Agent" to userAgent))) {
            is HttpResult.Fail -> Outcome.Failure(result.error)
            is HttpResult.Ok -> {
                val error = statusError(result.status) ?: bodyError(result.body)
                if (error != null) Outcome.Failure(error) else Outcome.Success(result.body)
            }
        }

    private fun statusError(status: Int): WeatherError? =
        when {
            status in 200..299 -> null
            status == 429 -> WeatherError.RateLimited(DEFAULT_RETRY_AFTER_SEC)
            status >= 500 -> WeatherError.Server(status)
            status >= 400 -> WeatherError.Client(status)
            else -> WeatherError.Server(status)
        }

    private fun bodyError(body: String): WeatherError? =
        when {
            body.isBlank() -> WeatherError.EmptyBody
            body.length > MAX_BODY_BYTES -> WeatherError.Oversize(body.length)
            else -> null
        }

    private sealed interface Outcome {
        data class Success(val body: String) : Outcome

        data class Failure(val error: WeatherError) : Outcome
    }

    companion object {
        /**
         * `OnHttpResponse.MAX_REQUEST_SIZE`. Duplicated as a plain Int so this class stays free of
         * karoo-ext and unit-testable on the JVM; the gateway applies the same ceiling.
         */
        const val MAX_BODY_BYTES = 100_000

        /** Used when the transport could not surface a `Retry-After` header. */
        const val DEFAULT_RETRY_AFTER_SEC = 60L
    }
}
