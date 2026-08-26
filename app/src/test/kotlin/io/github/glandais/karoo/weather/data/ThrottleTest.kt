package io.github.glandais.karoo.weather.data

import io.github.glandais.karoo.weather.karoo.throttle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `updateView` is dropped when two calls land less than 900 ms apart, so every view flow is
 * throttled before it repaints. The contract is "at most one value per period, and the value kept
 * is the most recent one".
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ThrottleTest {

    @Test
    fun `a burst collapses to the latest value per period`() = runTest {
        val source = MutableSharedFlow<Int>(extraBufferCapacity = 64)
        val seen = mutableListOf<Int>()
        val job = launch { source.throttle(1_000L).collect { seen.add(it) } }

        runCurrent()
        source.emit(1)
        runCurrent()
        assertEquals(listOf(1), seen)

        // Everything inside the period is conflated; only the last one survives.
        source.emit(2)
        source.emit(3)
        source.emit(4)
        runCurrent()
        assertEquals(listOf(1), seen)

        advanceTimeBy(1_001L)
        runCurrent()
        assertEquals(listOf(1, 4), seen)

        job.cancel()
    }

    @Test
    fun `a period of zero never delays and always ends on the latest value`() = runTest {
        val seen = flowOf(1, 2, 3).throttle(0L).toList()

        // conflate() may still drop intermediate values, but the newest one is never lost and the
        // order is never disturbed.
        assertEquals(3, seen.last())
        assertEquals(seen.sorted(), seen)
        assertEquals(0L, testScheduler.currentTime)
    }
}
