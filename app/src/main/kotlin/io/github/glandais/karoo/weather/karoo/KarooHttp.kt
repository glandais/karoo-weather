package io.github.glandais.karoo.weather.karoo

import io.github.glandais.karoo.weather.domain.HttpGateway
import io.github.glandais.karoo.weather.domain.HttpResult
import io.github.glandais.karoo.weather.domain.WeatherError
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.HttpResponseState
import io.hammerhead.karooext.models.OnHttpResponse
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.timeout

/**
 * [HttpGateway] over Karoo's own transport (`OnHttpResponse.MakeHttpRequest`).
 *
 * `waitForConnection = false` everywhere: the repository's retry loop *is* the queue, and a queued
 * request that lands forty minutes later would deliver a stale forecast anyway (ARCHITECTURE §5.2).
 *
 * This class never returns [WeatherError.Parse] — only the parser can decide a body is malformed.
 */
class KarooHttpGateway(
    private val karoo: KarooSystemService,
    private val userAgent: String,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
) : HttpGateway {

    @OptIn(FlowPreview::class)
    override suspend fun get(url: String, headers: Map<String, String>): HttpResult {
        if (!karoo.connected) return HttpResult.Fail(WeatherError.NoConnection)

        val complete =
            try {
                requestFlow(url, headers).timeout(timeoutMs.milliseconds).first()
            } catch (e: TimeoutCancellationException) {
                return HttpResult.Fail(WeatherError.Timeout)
            } catch (e: TransportException) {
                return HttpResult.Fail(e.error)
            } catch (e: NoSuchElementException) {
                // The consumer completed without ever delivering a Complete state.
                return HttpResult.Fail(WeatherError.NoConnection)
            }

        return map(complete)
    }

    private fun requestFlow(url: String, headers: Map<String, String>) = callbackFlow {
        val request =
            OnHttpResponse.MakeHttpRequest(
                method = "GET",
                url = url,
                headers = headers + ("User-Agent" to userAgent),
                waitForConnection = false,
            )
        val listenerId =
            karoo.addConsumer(
                params = request,
                onError = { message -> close(TransportException(transportError(message))) },
                // Without this the SDK only logs "Unhandled complete" and drops the consumer: the
                // channel would stay open and `first()` would wait out the whole 20 s timeout for a
                // request the transport has already given up on.
                onComplete = { close() },
            ) { event: OnHttpResponse ->
                val state = event.state
                if (state is HttpResponseState.Complete) {
                    trySend(state)
                    close()
                }
            }
        awaitClose { karoo.removeConsumer(listenerId) }
    }

    private fun map(complete: HttpResponseState.Complete): HttpResult {
        statusError(complete)?.let {
            return HttpResult.Fail(it)
        }
        val body = complete.body
        if (complete.error != null) return HttpResult.Fail(transportError(complete.error))
        if (body == null || body.isEmpty()) return HttpResult.Fail(WeatherError.EmptyBody)
        if (body.size > OnHttpResponse.MAX_REQUEST_SIZE) {
            return HttpResult.Fail(WeatherError.Oversize(body.size))
        }
        return HttpResult.Ok(complete.statusCode, String(body, Charsets.UTF_8))
    }

    private fun statusError(complete: HttpResponseState.Complete): WeatherError? {
        val status = complete.statusCode
        return when {
            // The transport never reached a server: no status line was produced.
            status <= 0 -> WeatherError.NoConnection
            status == 429 -> WeatherError.RateLimited(retryAfterSec(complete.headers))
            status >= 500 -> WeatherError.Server(status)
            status >= 400 -> WeatherError.Client(status)
            status !in 200..299 -> WeatherError.Server(status)
            else -> null
        }
    }

    private fun retryAfterSec(headers: Map<String, String>): Long {
        val raw = headers.entries.firstOrNull { it.key.equals("Retry-After", ignoreCase = true) }
        val seconds = raw?.value?.trim()?.toLongOrNull()
        return if (seconds != null && seconds > 0) seconds else DEFAULT_RETRY_AFTER_SEC
    }

    private fun transportError(message: String?): WeatherError =
        if (message != null && message.contains("timeout", ignoreCase = true)) WeatherError.Timeout
        else WeatherError.NoConnection

    /** Carries a transport-level failure out of the `callbackFlow`. */
    private class TransportException(val error: WeatherError) : Exception(error.message)

    companion object {
        /**
         * Our own ceiling, deliberately longer than ktor-client-karoo's 10 s (ARCHITECTURE §5.2).
         */
        const val DEFAULT_TIMEOUT_MS = 20_000L

        /** Used when a 429 carries no usable `Retry-After` header. */
        const val DEFAULT_RETRY_AFTER_SEC = 60L
    }
}
