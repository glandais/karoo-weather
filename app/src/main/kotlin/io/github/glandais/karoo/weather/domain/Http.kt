package io.github.glandais.karoo.weather.domain

sealed class HttpResult {
    data class Ok(val status: Int, val body: String) : HttpResult()

    data class Fail(val error: WeatherError) : HttpResult()
}

/** Abstraction over Karoo's HTTP so providers stay unit-testable. */
interface HttpGateway {
    suspend fun get(url: String, headers: Map<String, String> = emptyMap()): HttpResult
}
