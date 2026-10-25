package com.formcoach.app.coach.exercise

import com.formcoach.app.coach.Body
import com.formcoach.app.coach.BodySide
import com.formcoach.app.coach.CameraView
import com.formcoach.app.coach.Exercise
import com.formcoach.app.coach.ExerciseConfig
import com.formcoach.app.coach.FaultCue
import com.formcoach.app.coach.Geometry
import com.formcoach.app.coach.Joint
import com.formcoach.app.coach.PartialKind
import com.formcoach.app.coach.SetupIssue
import com.formcoach.app.coach.Stats
import java.util.Locale
import kotlin.math.roundToInt

/** What an analyzer reports to the session for one frame. */
sealed interface AnalyzerResult {
    val atMs: Long

    /** A valid, counted rep. [faults] are most important first. */
    data class Rep(override val atMs: Long, val faults: List<FaultCue>) : AnalyzerResult

    data class Partial(
        override val atMs: Long,
        val kind: PartialKind,
        val firstCue: String,
        val cue: String,
    ) : AnalyzerResult
}

/** Cue texts for partial reps of one exercise. */
data class PartialTexts(
    val shallowFirst: String,
    val shallow: String,
    val noLockoutFirst: String,
    val noLockout: String,
    /** End-of-set advice when NO_LOCKOUT partials were frequent. */
    val noLockoutAdvice: String,
) {
    fun partial(atMs: Long, kind: PartialKind) = when (kind) {
        PartialKind.TOO_SHALLOW -> AnalyzerResult.Partial(atMs, kind, shallowFirst, shallow)
        PartialKind.NO_LOCKOUT -> AnalyzerResult.Partial(atMs, kind, noLockoutFirst, noLockout)
    }
}

/**
 * Per-exercise logic: setup checks (POSITIONING), calibration, primary signals, rep evaluation with
 * movement gates and faults. One instance per set; the session creates a fresh one on Start.
 *
 * Bodies passed in are gated and normalised by image height (see [Body]); [process] gets smoothed bodies.
 */
abstract class ExerciseAnalyzer(val exercise: Exercise, protected val config: ExerciseConfig) {
    abstract val allowedViews: Set<CameraView>

    /** Joints the exercise needs on the tracked side (both sides in a front view). */
    abstract val requiredJoints: List<Joint>

    /** Joints whose mean z decides the near side in a side view. */
    abstract val sideJoints: List<Joint>

    abstract val visibilityIssue: SetupIssue
    abstract val viewIssue: SetupIssue
    abstract val placementHint: String
    abstract val partialTexts: PartialTexts

    /** Push-ups end the set automatically after leaving the plank. */
    open val usesPlankExit: Boolean = false

    /** Locked at calibration. */
    var view: CameraView = CameraView.SIDE
        protected set
    var side: BodySide? = null
        protected set
    var calibrated = false
        protected set

    // ---- tracking -----------------------------------------------------------------------------

    /** True when every landmark needed for [view] / [side] is present. A null side accepts either side. */
    fun isTracked(body: Body, view: CameraView, side: BodySide?): Boolean = when {
        view == CameraView.FRONT -> body.hasBoth(requiredJoints)
        side != null -> body.has(requiredJoints, side)
        else -> body.has(requiredJoints, BodySide.LEFT) || body.has(requiredJoints, BodySide.RIGHT)
    }

    fun isTrackedLocked(body: Body) = isTracked(body, view, side)

    /** Near side among complete sides, by z. */
    fun chooseSide(body: Body): BodySide? {
        val complete = listOf(BodySide.LEFT, BodySide.RIGHT).filter { body.has(requiredJoints, it) }
        return when (complete.size) {
            0 -> null
            1 -> complete[0]
            else -> Geometry.nearSide(body, sideJoints)
        }
    }

    // ---- display ------------------------------------------------------------------------------

    abstract fun primaryLabel(view: CameraView): String

    /** Text for the metric card; [body] is smoothed. */
    abstract fun primaryValue(body: Body, view: CameraView, side: BodySide?): String

    // ---- positioning --------------------------------------------------------------------------

    /** Primary signal(s) before calibration, used for the stillness check. */
    abstract fun positioningSignals(body: Body, view: CameraView, side: BodySide?): DoubleArray?

    /** True when the positioning signals are angles (degrees), false for ratios. */
    abstract fun signalIsAngle(view: CameraView): Boolean

    /** Setup checks 3-5 (start posture, stance, hand placement); checks 1, 2 and 6 belong to the session. */
    abstract fun setupIssue(body: Body, view: CameraView, side: BodySide?): SetupIssue?

    /** Calibrates on the smoothed bodies of the 1.5 s hold. [side] is the locked side (side views). */
    fun calibrate(window: List<Body>, view: CameraView, side: BodySide?) {
        this.view = view
        this.side = if (view == CameraView.FRONT) null else side
        calibrateLocked(window)
        calibrated = true
    }

    protected abstract fun calibrateLocked(window: List<Body>)

    // ---- in set -------------------------------------------------------------------------------

    /** Calibrated primary signal(s), one per counter. Null if a needed landmark is missing. */
    abstract fun signals(body: Body): DoubleArray?

    /** Outlier rejection limit for each signal, units per second. */
    abstract val maxRatePerSec: Double

    /** Feeds one smoothed, tracked frame. */
    abstract fun process(body: Body): List<AnalyzerResult>

    /** Abandons any rep in progress (tracking lost). */
    abstract fun abandon()

    /** Time since which every counter has rested in the top zone, or null. */
    abstract fun restSinceMs(): Long?

    abstract fun inTopZone(body: Body): Boolean

    open fun inPlank(body: Body): Boolean = true

    /** Per-arm counts (left, right) for front-view curls, else null. */
    open fun armCounts(): Pair<Int, Int>? = null

    // ---- helpers ------------------------------------------------------------------------------

    protected fun robustMax(v: List<Double>) = Stats.percentile(v, config.session.robustPercentile)

    protected fun robustMin(v: List<Double>) = Stats.percentile(v, 1.0 - config.session.robustPercentile)

    protected fun formatAngle(v: Double) = "${v.roundToInt()}°"

    protected fun formatRatio(v: Double) = String.format(Locale.US, "%.2f", v)
}

/** Time-ordered per-frame records, kept for [maxAgeMs]. */
class FrameBuffer<T>(private val maxAgeMs: Long) {
    private val times = ArrayDeque<Long>()
    private val items = ArrayDeque<T>()

    fun add(t: Long, item: T) {
        times.addLast(t)
        items.addLast(item)
        while (times.isNotEmpty() && t - times.first() > maxAgeMs) {
            times.removeFirst()
            items.removeFirst()
        }
    }

    fun clear() {
        times.clear()
        items.clear()
    }

    /** Records with start <= t <= end. */
    fun between(start: Long, end: Long): List<T> {
        val out = ArrayList<T>()
        for (i in times.indices) if (times[i] in start..end) out.add(items[i])
        return out
    }

    /** Record closest in time to [t]. */
    fun nearest(t: Long): T? {
        var best: T? = null
        var bestD = Long.MAX_VALUE
        for (i in times.indices) {
            val d = kotlin.math.abs(times[i] - t)
            if (d < bestD) {
                bestD = d
                best = items[i]
            }
        }
        return best
    }

    /** Records within the last [ms] of the newest record. */
    fun last(ms: Long): List<T> {
        if (times.isEmpty()) return emptyList()
        return between(times.last() - ms, times.last())
    }

    val size: Int get() = times.size
}

/** Window of a rep's frames for evaluation; the bottom window falls back to the frame nearest the middle. */
internal fun <T> bottomFrames(buffer: FrameBuffer<T>, start: Long, end: Long): List<T> {
    val frames = buffer.between(start, end)
    if (frames.isNotEmpty()) return frames
    return listOfNotNull(buffer.nearest((start + end) / 2))
}
