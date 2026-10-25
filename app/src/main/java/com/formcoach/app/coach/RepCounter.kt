package com.formcoach.app.coach

import kotlin.math.max
import kotlin.math.min

enum class RepPhase {
    /** Not tracking reps until the signal has been in the top zone for [RepCounterConfig.topConfirmFrames]. */
    WAIT_TOP,
    TOP,
    /** Left the top zone, depth not reached yet. */
    DOWN,
    /** Depth reached and held (>= dwell frames and ms). */
    BOTTOM,
    /** Coming back up after the bottom. */
    UP,
}

sealed interface RepCounterEvent {
    /**
     * A movement from the top zone through depth and back to the top zone, within the duration limits.
     * Movement gates are not checked here: the analyzer checks them over [startMs]..[endMs].
     */
    data class Completed(
        /** Last frame in the top zone before the rep. */
        val startMs: Long,
        /** First frame back in the top zone. */
        val endMs: Long,
        /** Frame at which the top zone was confirmed (the count happens here). */
        val confirmMs: Long,
        /** Deepest value reached, in natural units. */
        val extreme: Double,
        /** Start and end of the bottom window (signal past [RepCounterConfig.bottomFraction] of the excursion). */
        val bottomStartMs: Long,
        val bottomEndMs: Long,
        /** Estimated duration of the whole down phase and the whole up phase (top value <-> bottom). */
        val downMs: Long,
        val upMs: Long,
    ) : RepCounterEvent {
        val durationMs: Long get() = endMs - startMs
    }

    data class Partial(
        val kind: PartialKind,
        val startMs: Long,
        val endMs: Long,
        val confirmMs: Long,
        val extreme: Double,
    ) : RepCounterEvent
}

/**
 * Generic rep phase machine over one signal:
 * TOP -> DOWN (left the top zone) -> BOTTOM (depth held) -> UP -> TOP (count once the top zone is
 * confirmed for [RepCounterConfig.topConfirmFrames] frames).
 *
 * Internally the signal is converted to "deep" units (larger = deeper) so one machine serves angles
 * (which fall as you go down) and ratios (which rise).
 */
class RepCounter(val config: RepCounterConfig) {
    private val dir = if (config.deeperIsLower) -1.0 else 1.0
    private fun deep(v: Double) = v * dir
    private fun natural(d: Double) = d * dir

    private val depthD = deep(config.depth)
    private val attemptD = deep(config.attempt)
    private var topD = deep(config.topFloor)
    private var calD = topD

    var phase: RepPhase = RepPhase.WAIT_TOP
        private set

    /** Time since which the signal has been continuously in the top zone (TOP phase), else null. */
    var topSinceMs: Long? = null
        private set

    /** Boundary of the top zone in natural units. */
    val topThreshold: Double get() = natural(topD)

    private var topRun = 0
    private var firstTopMs = 0L
    private var lastTopMs = 0L
    private var lastTopD = 0.0

    private var startMs = 0L
    private var extreme = 0.0
    private var depthRun = 0
    private var depthStartMs: Long? = null
    private var minSinceBottom = Double.MAX_VALUE
    private var minSinceBottomAt = 0L
    private var expired = false
    private val sampleT = ArrayList<Long>()
    private val sampleD = ArrayList<Double>()

    /**
     * Sets the top zone from the calibrated top value: relative boundary when configured, but never
     * easier than [RepCounterConfig.topFloor].
     */
    fun calibrate(calibratedTop: Double) {
        val floorD = deep(config.topFloor)
        val c = deep(calibratedTop)
        topD = if (config.topRelative != null) min(floorD, c + config.topRelative) else floorD
        calD = min(c, topD)
    }

    fun isInTopZone(value: Double) = deep(value) <= topD

    /** Abandons any rep in progress; reps resume once the top zone is confirmed again. */
    fun abandon() {
        phase = RepPhase.WAIT_TOP
        topRun = 0
        topSinceMs = null
        sampleT.clear()
        sampleD.clear()
    }

    private var lastD = 0.0

    /**
     * While coming back up after the bottom (UP phase): how far the signal has returned from the extreme
     * toward the top zone, 0 at the extreme and 1 at the top-zone boundary. Null in any other phase.
     */
    fun returnProgress(): Double? {
        if (phase != RepPhase.UP) return null
        val span = extreme - topD
        if (span <= 1e-9) return null
        return ((extreme - lastD) / span).coerceIn(0.0, 1.0)
    }

    private var lastSampleMs = 0L

    fun update(timestampMs: Long, value: Double): RepCounterEvent? {
        val t = timestampMs
        val d = deep(value)
        lastD = d
        val event = step(t, d)
        lastSampleMs = t
        return event
    }

    private fun step(t: Long, d: Double): RepCounterEvent? {
        val inTop = d <= topD
        when (phase) {
            RepPhase.WAIT_TOP -> {
                if (inTop) {
                    topRun++
                    if (topRun == 1) firstTopMs = t
                    if (topRun >= config.topConfirmFrames) {
                        phase = RepPhase.TOP
                        topSinceMs = firstTopMs
                        lastTopMs = t
                        lastTopD = d
                        topRun = 0
                    }
                } else {
                    topRun = 0
                }
                return null
            }
            RepPhase.TOP -> {
                if (inTop) {
                    lastTopMs = t
                    lastTopD = d
                    return null
                }
                startRep(lastTopMs, lastTopD)
                return inRep(t, d, inTop)
            }
            RepPhase.DOWN, RepPhase.BOTTOM, RepPhase.UP -> return inRep(t, d, inTop)
        }
    }

    private fun startRep(t: Long, d: Double) {
        phase = RepPhase.DOWN
        startMs = t
        extreme = d
        depthRun = 0
        depthStartMs = null
        minSinceBottom = Double.MAX_VALUE
        topRun = 0
        topSinceMs = null
        expired = false
        sampleT.clear()
        sampleD.clear()
        sampleT.add(t)
        sampleD.add(d)
    }

    private fun inRep(t: Long, d: Double, inTop: Boolean): RepCounterEvent? {
        if (!expired) {
            sampleT.add(t)
            sampleD.add(d)
            if (t - startMs > config.maxDurationMs + 1000) {
                // Far too slow to be a rep: stop collecting, it will be discarded at the top.
                expired = true
                sampleT.clear()
                sampleD.clear()
            }
        }
        if (d > extreme) extreme = d

        if (inTop) {
            topRun++
            if (topRun == 1) firstTopMs = t
            return if (topRun >= config.topConfirmFrames) finish(t, d) else null
        }
        topRun = 0

        when (phase) {
            RepPhase.DOWN -> {
                if (d >= depthD) {
                    val since = depthStartMs ?: t.also { depthStartMs = it; depthRun = 0 }
                    depthRun++
                    // Each frame stands for one frame interval, so the dwell is measured from the start of the
                    // first frame at depth: 2 frames at 30 fps hold ~67 ms. Without that, 2 frames at 30 fps
                    // span only 33 ms and fast reps (0.7 s squats) miss the plan's 60 ms dwell.
                    val dwell = t - since + (t - lastSampleMs).coerceIn(0L, 100L)
                    if (depthRun >= config.bottomDwellFrames && dwell >= config.bottomDwellMs) {
                        phase = RepPhase.BOTTOM
                        minSinceBottom = d
                        minSinceBottomAt = t
                    }
                } else {
                    depthStartMs = null
                    depthRun = 0
                }
            }
            RepPhase.BOTTOM, RepPhase.UP -> {
                if (d < minSinceBottom) {
                    minSinceBottom = d
                    minSinceBottomAt = t
                }
                phase = if (d >= depthD) RepPhase.BOTTOM else RepPhase.UP
                // Came up past the attempt level, then turned back down before the top zone.
                if (minSinceBottom <= attemptD && d - minSinceBottom >= config.turnBack) {
                    val turnAt = minSinceBottomAt
                    val duration = turnAt - startMs
                    val event = if (!expired && duration in config.minDurationMs..config.maxDurationMs) {
                        RepCounterEvent.Partial(PartialKind.NO_LOCKOUT, startMs, turnAt, t, natural(extreme))
                    } else {
                        null
                    }
                    restartFrom(turnAt, minSinceBottom)
                    return event
                }
            }
            else -> Unit
        }
        return null
    }

    /** Starts a new attempt at the turning point of a NO_LOCKOUT, keeping the samples since then. */
    private fun restartFrom(t: Long, d: Double) {
        val keepT = ArrayList<Long>()
        val keepD = ArrayList<Double>()
        for (i in sampleT.indices) {
            if (sampleT[i] >= t) {
                keepT.add(sampleT[i])
                keepD.add(sampleD[i])
            }
        }
        startRep(t, d)
        sampleT.clear()
        sampleD.clear()
        sampleT.addAll(keepT)
        sampleD.addAll(keepD)
        extreme = keepD.maxOrNull() ?: d
    }

    private fun finish(t: Long, d: Double): RepCounterEvent? {
        val endMs = firstTopMs
        val duration = endMs - startMs
        var reachedBottom = phase == RepPhase.BOTTOM || phase == RepPhase.UP
        if (!reachedBottom && !expired) {
            // The bottom may have been hidden by a tracking gap (dropout within the grace period).
            val est = bridgedExtreme()
            if (est != null && est >= depthD) {
                reachedBottom = true
                if (extreme < depthD) extreme = depthD
            }
        }
        val event: RepCounterEvent? = when {
            expired || duration > config.maxDurationMs -> null
            reachedBottom -> completed(endMs, t).takeIf {
                // The measured span excludes the movement inside the top zone, so the minimum is checked
                // on the estimated full rep (down + bottom + up); a 0.7 s squat measures only ~0.58 s.
                it.downMs + (it.bottomEndMs - it.bottomStartMs) + it.upMs >= config.minDurationMs
            }
            duration < config.minDurationMs -> null
            extreme >= attemptD -> RepCounterEvent.Partial(PartialKind.TOO_SHALLOW, startMs, endMs, t, natural(extreme))
            else -> null // never a rep attempt: ignore silently
        }
        phase = RepPhase.TOP
        topSinceMs = endMs
        lastTopMs = t
        lastTopD = d
        topRun = 0
        sampleT.clear()
        sampleD.clear()
        return event
    }

    /**
     * Depth estimated across the longest tracking gap of the current attempt, or null without such a gap.
     * The signal's slope is fitted over the frames just before the gap (going deeper) and just after it
     * (coming back). The two slopes are averaged and the signal is extended from both edges as a
     * symmetric V; where the lines meet is the estimate. Averaging matters because the One Euro filter
     * restarts after a long gap, so the first slope after it is noisy. A real bottom is round, so the
     * straight lines overestimate the depth a little: a shallow 125° squat hidden by a 500 ms gap still
     * estimates ~119°, a 90° squat ~89°.
     */
    private fun bridgedExtreme(): Double? {
        var gi = -1
        var gap = 0L
        for (i in 0 until sampleT.size - 1) {
            val g = sampleT[i + 1] - sampleT[i]
            if (g > gap) {
                gap = g
                gi = i
            }
        }
        if (gi < 1 || gap < config.bridgeMinGapMs || gi + 2 >= sampleT.size) return null
        val t1 = sampleT[gi]
        val t2 = sampleT[gi + 1]
        val d1 = sampleD[gi]
        val d2 = sampleD[gi + 1]
        val window = config.bridgeSlopeWindowMs
        val a = slope((0..gi).filter { t1 - sampleT[it] <= window }) ?: return null // per ms, + = deeper
        val b = slope((gi + 1 until sampleT.size).filter { sampleT[it] - t2 <= window }) ?: return null
        val maxRate = config.maxRatePerSec / 1000.0
        val m = ((a.coerceIn(0.0, maxRate) + (-b).coerceIn(0.0, maxRate)) / 2)
        if (m <= 0.0) return max(d1, d2) // no turn inside the gap
        // Lines d1 + m (t - t1) and d2 - m (t - t2) meet at tm; clamp to the gap.
        val tm = ((d2 - d1 + m * (t1 + t2)) / (2 * m)).coerceIn(t1.toDouble(), t2.toDouble())
        return d1 + m * (tm - t1)
    }

    /** Least-squares slope (deep units per ms) over the samples at [idx]; null with fewer than 2. */
    private fun slope(idx: List<Int>): Double? {
        if (idx.size < 2) return null
        val mt = idx.sumOf { sampleT[it].toDouble() } / idx.size
        val md = idx.sumOf { sampleD[it] } / idx.size
        var num = 0.0
        var den = 0.0
        for (i in idx) {
            val dt = sampleT[i] - mt
            num += dt * (sampleD[i] - md)
            den += dt * dt
        }
        return if (den < 1e-9) null else num / den
    }

    private fun completed(endMs: Long, confirmMs: Long): RepCounterEvent.Completed {
        val ref = calD
        val ex = max(extreme - ref, 1e-9)
        val frac = config.bottomFraction
        val level90 = ref + frac * ex
        var t90Down = startMs
        var t90Up = endMs
        var found = false
        for (i in sampleT.indices) {
            if (sampleT[i] > endMs) break
            if (sampleD[i] >= level90) {
                if (!found) t90Down = sampleT[i]
                found = true
                t90Up = sampleT[i]
            }
        }
        if (!found) {
            t90Down = startMs
            t90Up = endMs
        }
        val fTop = ((topD - ref) / ex).coerceIn(0.0, frac)
        val span = frac - fTop
        val downRaw = t90Down - startMs
        val upRaw = endMs - t90Up
        val downMs = if (span >= 0.3) (downRaw / span).toLong() else downRaw
        val upMs = if (span >= 0.3) (upRaw / span).toLong() else upRaw
        return RepCounterEvent.Completed(
            startMs = startMs,
            endMs = endMs,
            confirmMs = confirmMs,
            extreme = natural(extreme),
            bottomStartMs = t90Down,
            bottomEndMs = t90Up,
            downMs = downMs,
            upMs = upMs,
        )
    }
}
