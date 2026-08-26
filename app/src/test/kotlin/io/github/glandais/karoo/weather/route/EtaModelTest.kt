package io.github.glandais.karoo.weather.route

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EtaModelTest {

    /** 22 km/h, the default assumed speed. */
    private val assumed = 22 / 3.6

    private fun model(assumedMs: Double = assumed, tau: Double = 300.0) = EtaModel(assumedMs, tau)

    private fun EtaModel.feed(speedMs: Double, count: Int, fromSec: Long = 0L, stepSec: Long = 1L) {
        for (i in 0 until count) onSpeedSample(speedMs, fromSec + i * stepSec)
    }

    @Test
    fun fallsBackToTheAssumedSpeedBeforeAnySample() {
        assertEquals(assumed, model().effectiveSpeedMs(useMeasured = true), 1e-9)
    }

    @Test
    fun fallsBackToTheAssumedSpeedBelowTheSampleFloor() {
        val m = model()
        m.feed(10.0, count = 2)
        assertEquals(assumed, m.effectiveSpeedMs(useMeasured = true), 1e-9)
    }

    @Test
    fun usesTheMeasuredSpeedOnceEnoughSamplesArrived() {
        val m = model()
        m.feed(10.0, count = 3)
        assertEquals(10.0, m.effectiveSpeedMs(useMeasured = true), 1e-6)
    }

    @Test
    fun ignoresTheMeasuredSpeedWhenUseMeasuredIsFalse() {
        val m = model()
        m.feed(10.0, count = 50)
        assertEquals(assumed, m.effectiveSpeedMs(useMeasured = false), 1e-9)
    }

    @Test
    fun samplesBelowTwoMetresPerSecondAreIgnored() {
        val m = model()
        m.feed(1.9, count = 100)
        assertEquals(assumed, m.effectiveSpeedMs(useMeasured = true), 1e-9)
        // A single stop does not poison an established average either.
        m.feed(10.0, count = 5, fromSec = 100L)
        m.onSpeedSample(0.0, 200L)
        assertEquals(10.0, m.effectiveSpeedMs(useMeasured = true), 0.1)
    }

    @Test
    fun theAverageConvergesOnTheNewSpeed() {
        val m = model()
        m.feed(6.0, count = 10)
        assertEquals(6.0, m.effectiveSpeedMs(useMeasured = true), 1e-6)
        // 1800 s of riding at 10 m/s is six time constants: within 1 % of the new value.
        m.feed(10.0, count = 1_800, fromSec = 100L)
        assertEquals(10.0, m.effectiveSpeedMs(useMeasured = true), 0.05)
    }

    @Test
    fun theAverageLagsBehindAStepChange() {
        val m = model()
        m.feed(6.0, count = 10)
        // One time constant later it has covered ~63 % of the step, not all of it.
        m.feed(10.0, count = 300, fromSec = 100L)
        val speed = m.effectiveSpeedMs(useMeasured = true)
        assertTrue("expected a lagging average, got $speed", speed > 8.0 && speed < 9.5)
    }

    @Test
    fun aLongGapSnapsToTheNewSpeed() {
        val m = model()
        m.feed(6.0, count = 5)
        m.onSpeedSample(12.0, 100_000L)
        assertEquals(12.0, m.effectiveSpeedMs(useMeasured = true), 1e-6)
    }

    @Test
    fun resetDropsTheMeasuredSpeed() {
        val m = model()
        m.feed(10.0, count = 20)
        m.reset()
        assertEquals(assumed, m.effectiveSpeedMs(useMeasured = true), 1e-9)
    }

    @Test
    fun theEffectiveSpeedNeverFallsBelowOneMetrePerSecond() {
        val m = EtaModel(assumedSpeedMs = 0.0)
        assertEquals(EtaModel.MIN_EFFECTIVE_MS, m.effectiveSpeedMs(useMeasured = false), 1e-9)
        assertEquals(EtaModel.MIN_EFFECTIVE_MS, m.effectiveSpeedMs(useMeasured = true), 1e-9)
    }

    @Test
    fun etaUsesTheAssumedSpeedWhenStopped() {
        val m = model()
        val eta =
            m.eta(nowSec = 1_000L, progress = 0.0, distanceAlong = 22_000.0, useMeasured = true)
        // 22 km at 22 km/h is exactly one hour.
        assertEquals(1_000L + 3_600L, eta)
    }

    @Test
    fun etaUsesTheMeasuredSpeedWhenRecording() {
        val m = model()
        m.feed(10.0, count = 5)
        val eta =
            m.eta(nowSec = 0L, progress = 1_000.0, distanceAlong = 11_000.0, useMeasured = true)
        assertEquals(1_000L, eta)
    }

    @Test
    fun etaIsMonotonicInDistance() {
        val m = model()
        m.feed(8.0, count = 10)
        var previous = Long.MIN_VALUE
        for (d in 0..50) {
            val eta = m.eta(0L, 0.0, d * 1_000.0, useMeasured = true)
            assertTrue("ETA went backwards at ${d}km", eta >= previous)
            previous = eta
        }
    }

    @Test
    fun etaOfAPointBehindTheRiderIsNow() {
        val m = model()
        assertEquals(
            500L,
            m.eta(500L, progress = 10_000.0, distanceAlong = 0.0, useMeasured = true),
        )
        assertEquals(
            500L,
            m.eta(500L, progress = 10_000.0, distanceAlong = 10_000.0, useMeasured = true),
        )
    }
}
