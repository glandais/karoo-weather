package io.github.glandais.karoo.weather.route

import kotlin.math.exp
import kotlin.math.roundToLong

/**
 * Time-aware exponential moving average of the rider's speed, and the ETA projection built on it.
 *
 * The average is irregularly sampled (the SPEED stream is not isochronous), so the smoothing factor
 * is derived from the elapsed time between samples: `alpha = 1 - exp(-dt / tau)`. A long gap
 * therefore snaps to the new value instead of dragging a stale one forward.
 *
 * Not thread-safe: feed it from one collector.
 */
class EtaModel(private val assumedSpeedMs: Double, private val tauSeconds: Double = 300.0) {

    private var ema: Double? = null
    private var lastAtSec: Long? = null
    private var sampleCount: Int = 0

    /** Feed the SPEED stream. Samples below [MIN_SAMPLE_MS] are ignored (stopped at a light). */
    fun onSpeedSample(speedMs: Double, atEpochSec: Long) {
        if (!speedMs.isFinite() || speedMs < MIN_SAMPLE_MS) return
        val previous = ema
        val previousAt = lastAtSec
        ema =
            if (previous == null || previousAt == null) {
                speedMs
            } else {
                val dt = (atEpochSec - previousAt).toDouble().coerceAtLeast(0.0)
                val alpha = (1.0 - exp(-dt / tauSeconds.coerceAtLeast(1e-3))).coerceIn(0.0, 1.0)
                previous + alpha * (speedMs - previous)
            }
        lastAtSec = atEpochSec
        sampleCount++
    }

    fun reset() {
        ema = null
        lastAtSec = null
        sampleCount = 0
    }

    /**
     * EMA when [useMeasured] and at least [MIN_SAMPLES] samples have been fed, else
     * [assumedSpeedMs]. Never below [MIN_EFFECTIVE_MS], so an ETA can never divide by ~zero.
     */
    fun effectiveSpeedMs(useMeasured: Boolean): Double {
        val measured = ema
        val chosen =
            if (useMeasured && measured != null && sampleCount >= MIN_SAMPLES) measured
            else assumedSpeedMs
        return if (chosen.isFinite()) chosen.coerceAtLeast(MIN_EFFECTIVE_MS) else MIN_EFFECTIVE_MS
    }

    /**
     * Epoch seconds at which the rider reaches [distanceAlong], given they are at [progress] now. A
     * point behind the rider resolves to [nowSec] rather than to the past.
     */
    fun eta(nowSec: Long, progress: Double, distanceAlong: Double, useMeasured: Boolean): Long {
        val ahead = (distanceAlong - progress).coerceAtLeast(0.0)
        if (!ahead.isFinite()) return nowSec
        return nowSec + (ahead / effectiveSpeedMs(useMeasured)).roundToLong()
    }

    companion object {
        /** Speeds below this are coasting/stopped noise and never enter the average. */
        const val MIN_SAMPLE_MS = 2.0

        /** Below this many accepted samples the EMA is not trusted yet. */
        const val MIN_SAMPLES = 3

        const val MIN_EFFECTIVE_MS = 1.0
    }
}
