package com.formcoach.app.coach

import kotlin.math.PI
import kotlin.math.abs

/**
 * One Euro filter (Casiez et al. 2012) for one scalar. Time comes from the caller in milliseconds,
 * so the filter is deterministic and independent of any wall clock.
 *
 * Low speed -> cutoff near [minCutoffHz] (strong smoothing, removes jitter while still).
 * High speed -> cutoff rises with [beta] (less lag during fast reps).
 */
class OneEuroFilter(
    private val minCutoffHz: Double,
    private val beta: Double,
    private val dCutoffHz: Double,
) {
    private var hasValue = false
    private var lastValue = 0.0
    private var lastDerivative = 0.0
    private var lastTimeMs = 0L

    val initialized: Boolean get() = hasValue

    fun reset() {
        hasValue = false
        lastDerivative = 0.0
    }

    fun filter(value: Double, timestampMs: Long): Double {
        if (!hasValue) {
            hasValue = true
            lastValue = value
            lastDerivative = 0.0
            lastTimeMs = timestampMs
            return value
        }
        val dtMs = timestampMs - lastTimeMs
        if (dtMs <= 0L) {
            // Same or older timestamp: nothing to integrate over. Keep the current estimate.
            return lastValue
        }
        val dt = dtMs / 1000.0
        val rawDerivative = (value - lastValue) / dt
        val derivative = lowPass(lastDerivative, rawDerivative, alpha(dCutoffHz, dt))
        val cutoff = minCutoffHz + beta * abs(derivative)
        val filtered = lowPass(lastValue, value, alpha(cutoff, dt))
        lastValue = filtered
        lastDerivative = derivative
        lastTimeMs = timestampMs
        return filtered
    }

    private fun lowPass(previous: Double, current: Double, a: Double) = previous + a * (current - previous)

    private fun alpha(cutoffHz: Double, dt: Double): Double {
        val tau = 1.0 / (2.0 * PI * cutoffHz)
        return 1.0 / (1.0 + tau / dt)
    }
}

/**
 * Smooths all 33 landmarks of a [Body] (x, y and z each get their own [OneEuroFilter]).
 * A landmark that has been missing for longer than [FilterConfig.resetGapMs] restarts its filters
 * so that it does not glide in from a stale position.
 */
class LandmarkSmoother(private val config: FilterConfig) {
    private val filters = Array(LandmarkId.COUNT) {
        Array(3) { OneEuroFilter(config.minCutoffHz, config.beta, config.dCutoffHz) }
    }
    private val lastSeenMs = LongArray(LandmarkId.COUNT) { Long.MIN_VALUE }

    fun reset() {
        filters.forEach { group -> group.forEach { it.reset() } }
        lastSeenMs.fill(Long.MIN_VALUE)
    }

    fun smooth(body: Body): Body {
        val t = body.timestampMs
        val out = arrayOfNulls<Pt>(LandmarkId.COUNT)
        for (i in 0 until LandmarkId.COUNT) {
            val p = body[i] ?: continue
            val f = filters[i]
            if (lastSeenMs[i] == Long.MIN_VALUE || t - lastSeenMs[i] > config.resetGapMs) {
                f.forEach { it.reset() }
            }
            lastSeenMs[i] = t
            out[i] = Pt(f[0].filter(p.x, t), f[1].filter(p.y, t), f[2].filter(p.z, t))
        }
        return Body(t, body.imageHeight, out)
    }
}
