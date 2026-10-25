package com.formcoach.app.coach

import com.formcoach.app.coach.exercise.AnalyzerResult
import com.formcoach.app.coach.exercise.CurlAnalyzer
import com.formcoach.app.coach.exercise.ExerciseAnalyzer
import com.formcoach.app.coach.exercise.PushUpAnalyzer
import com.formcoach.app.coach.exercise.SquatAnalyzer

/**
 * The coaching engine (plan sections 3.1, Phases 2-5). Pure Kotlin, deterministic, single-threaded.
 *
 * IDLE -> (StartSet) POSITIONING -> (start posture held still 1.5 s; calibrate; Ready) IN_SET
 * IN_SET -> (tracking lost > 1.5 s) PAUSED -> (tracked again and in the top zone) IN_SET
 * IN_SET/PAUSED -> (StopSet, or auto end after >= 1 rep) SET_COMPLETE
 *
 * COUNTDOWN is instantaneous: the Ready event and the switch to IN_SET happen on the same frame.
 */
class WorkoutSession(private val config: ExerciseConfig = ExerciseConfig()) : WorkoutEngine {

    private var exercise = Exercise.SQUAT
    private var phase = SessionPhase.IDLE
    private var analyzer: ExerciseAnalyzer = newAnalyzer(exercise)

    private val smoother = LandmarkSmoother(config.filter)
    private val swapper = SwapCorrector(config.tracking)
    private val viewDetector = ViewDetector(config.session)

    private var reps = 0
    private var partials = 0
    private val faultCounts = LinkedHashMap<String, Pair<FaultCue, Int>>()
    private var noLockoutCount = 0
    private var summary: SetSummary? = null

    private var instruction: String? = null
    private var lastIssue: SetupIssue? = null
    private var tracking = false
    private var overlay: PoseFrame? = null
    private var primaryValue = "--"
    private var displayView: CameraView? = null
    private var displaySide: BodySide? = null

    // POSITIONING hold.
    private class HoldSample(val t: Long, val signals: DoubleArray, val body: Body)

    private val hold = ArrayList<HoldSample>()
    private var holdView: CameraView? = null

    // IN_SET tracking.
    private var lastGoodMs: Long? = null
    private var lastRawSignals: DoubleArray? = null
    private var lastRawMs = 0L
    private var resumeRun = 0
    private var outOfPlankSinceMs: Long? = null
    private var pausedSinceMs: Long? = null
    private var lastFrameMs = Long.MIN_VALUE

    /** Snapshot of the current UI state. */
    val state: WorkoutUiState get() = buildState()

    override fun onFrame(frame: PoseFrame): EngineOutput {
        val t = frame.timestampMs
        if (t < lastFrameMs) return EngineOutput(buildState(), emptyList()) // out of order: ignore
        lastFrameMs = t
        val events = ArrayList<CoachEvent>()
        when (phase) {
            SessionPhase.IDLE, SessionPhase.SET_COMPLETE -> passiveFrame(frame)
            SessionPhase.POSITIONING -> positioningFrame(frame, events)
            SessionPhase.COUNTDOWN, SessionPhase.IN_SET, SessionPhase.PAUSED -> inSetFrame(frame, events)
        }
        return EngineOutput(buildState(), events)
    }

    override fun onCommand(command: SessionCommand, timestampMs: Long): EngineOutput {
        val events = ArrayList<CoachEvent>()
        when (command) {
            SessionCommand.StartSet -> if (phase == SessionPhase.IDLE || phase == SessionPhase.SET_COMPLETE) {
                resetForSet()
                phase = SessionPhase.POSITIONING
                events.add(CoachEvent.SetStarted(timestampMs, exercise))
            }
            SessionCommand.StopSet -> when (phase) {
                SessionPhase.POSITIONING -> {
                    // No set has begun yet: back to IDLE without a summary.
                    clearIssue(timestampMs, events)
                    phase = SessionPhase.IDLE
                    instruction = null
                }
                SessionPhase.COUNTDOWN, SessionPhase.IN_SET, SessionPhase.PAUSED -> {
                    handleResults(analyzer.flushPending(), timestampMs, events)
                    completeSet(timestampMs, events)
                }
                else -> Unit
            }
            is SessionCommand.SelectExercise -> if (phase == SessionPhase.IDLE || phase == SessionPhase.SET_COMPLETE) {
                exercise = command.exercise
                analyzer = newAnalyzer(exercise)
                phase = SessionPhase.IDLE
                summary = null
                instruction = null
                reps = 0
                partials = 0
                primaryValue = "--"
                displayView = null
                displaySide = null
            }
        }
        return EngineOutput(buildState(), events)
    }

    // ---- phases -------------------------------------------------------------------------------

    private fun passiveFrame(frame: PoseFrame) {
        val raw = Body.fromFrame(frame, config.tracking.minInFrame)
        val smoothed = smoother.smooth(raw)
        overlay = toOverlay(frame, frame.landmarks, smoothed)
        if (phase == SessionPhase.SET_COMPLETE && analyzer.calibrated) {
            tracking = analyzer.isTrackedLocked(raw)
            primaryValue = if (tracking) analyzer.primaryValue(smoothed, analyzer.view, analyzer.side) else "--"
        } else {
            tracking = analyzer.isTracked(raw, CameraView.SIDE, null)
            primaryValue = if (tracking) analyzer.primaryValue(smoothed, CameraView.SIDE, null) else "--"
        }
    }

    private fun positioningFrame(frame: PoseFrame, events: MutableList<CoachEvent>) {
        val t = frame.timestampMs
        // No calibrated reference yet: undo left/right swaps against the recent majority, before smoothing.
        val corrected = swapper.correctProvisional(frame, viewDetector.stable ?: viewDetector.candidate)
        val raw = Body.fromLandmarks(t, frame.imageHeight, corrected, config.tracking.minInFrame)
        val body = smoother.smooth(raw)
        overlay = toOverlay(frame, corrected, body)

        if (raw.points.any { it != null }) viewDetector.update(Geometry.viewRatio(body), t)
        val seenView = viewDetector.stable ?: viewDetector.candidate
        val viewGuess = seenView ?: CameraView.SIDE
        val side = if (viewGuess == CameraView.FRONT) null else analyzer.chooseSide(body)
        tracking = analyzer.isTracked(body, viewGuess, side)
        displayView = viewDetector.stable
        displaySide = side

        // Setup checks in priority order: visibility, view, start posture / stance / hands.
        val issue = when {
            !tracking -> analyzer.visibilityIssue
            seenView != null && seenView !in analyzer.allowedViews -> analyzer.viewIssue
            else -> analyzer.setupIssue(body, viewGuess, side)
        }
        if (issue != lastIssue) {
            events.add(CoachEvent.SetupIssueChanged(t, issue))
            lastIssue = issue
        }
        instruction = issue?.text ?: HOLD_STILL
        primaryValue = if (tracking) analyzer.primaryValue(body, viewGuess, side) else "--"

        // Stillness hold (check 6).
        if (issue != null || seenView == null || seenView !in analyzer.allowedViews) {
            resetHold()
            return
        }
        if (holdView != null && holdView != seenView) hold.clear()
        holdView = seenView
        val signals = analyzer.positioningSignals(body, seenView, side)
        if (signals == null) {
            resetHold()
            return
        }
        hold.add(HoldSample(t, signals, body))
        while (hold.size >= 2 && t - hold[1].t >= config.session.holdMs) hold.removeAt(0)
        if (t - hold.first().t < config.session.holdMs) return
        if (viewDetector.stable != seenView) return

        val maxSd = if (analyzer.signalIsAngle(seenView)) config.session.stillAngleSdDeg else config.session.stillRatioSd
        val still = signals.indices.all { i -> Stats.sd(hold.map { it.signals.getOrElse(i) { 0.0 } }) < maxSd }
        if (!still) return

        calibrate(t, seenView, events)
    }

    private fun calibrate(t: Long, view: CameraView, events: MutableList<CoachEvent>) {
        val bodies = hold.map { it.body }
        val side = if (view == CameraView.FRONT) null else {
            val complete = bodies.filter { b -> listOf(BodySide.LEFT, BodySide.RIGHT).any { b.has(analyzer.requiredJoints, it) } }
            Geometry.nearSide(complete, analyzer.sideJoints) ?: BodySide.LEFT
        }
        analyzer.calibrate(bodies, view, side)
        if (view == CameraView.FRONT) swapper.lockFront(bodies) else swapper.lockSide(analyzer.side ?: BodySide.LEFT)
        resetHold()
        lastGoodMs = t
        lastRawSignals = null
        resumeRun = 0
        outOfPlankSinceMs = null
        displayView = view
        displaySide = analyzer.side
        events.add(CoachEvent.Ready(t))
        phase = SessionPhase.IN_SET // COUNTDOWN is instantaneous
        instruction = READY_TEXT
    }

    private fun inSetFrame(frame: PoseFrame, events: MutableList<CoachEvent>) {
        val t = frame.timestampMs
        val corrected = swapper.correct(frame)
        val raw = Body.fromLandmarks(t, frame.imageHeight, corrected, config.tracking.minInFrame)
        val present = analyzer.isTrackedLocked(raw)
        tracking = present

        var signals: DoubleArray? = null
        if (present) {
            val s = analyzer.signals(raw)
            if (s != null && !isOutlier(s, t)) signals = s
        }
        if (signals == null) {
            // Show what the model sees (unsmoothed) so the UI can grey out the missing joints.
            overlay = if (frame.hasPerson) PoseFrame(t, frame.imageWidth, frame.imageHeight, corrected) else null
            onDropout(t, events)
            return
        }
        lastRawSignals = signals
        lastRawMs = t
        lastGoodMs = t

        val body = smoother.smooth(raw)
        overlay = toOverlay(frame, corrected, body)
        primaryValue = analyzer.primaryValue(body, analyzer.view, analyzer.side)

        if (phase == SessionPhase.PAUSED) {
            if (pausedTooLong(t, events)) return
            resumeRun = if (analyzer.inTopZone(body)) resumeRun + 1 else 0
            if (resumeRun < config.session.resumeTopFrames) return
            phase = SessionPhase.IN_SET
            resumeRun = 0
            pausedSinceMs = null
            outOfPlankSinceMs = null
            instruction = null
            events.add(CoachEvent.TrackingRestored(t))
        }

        handleResults(analyzer.process(body), t, events)

        // Auto set end, only once at least one rep was counted.
        if (reps >= 1) {
            val rest = analyzer.restSinceMs()
            if (rest != null && t - rest >= config.session.restEndMs) {
                endSetAutomatically(t, events)
                return
            }
            if (analyzer.usesPlankExit) {
                if (analyzer.inPlank(body)) {
                    outOfPlankSinceMs = null
                } else {
                    val since = outOfPlankSinceMs ?: t.also { outOfPlankSinceMs = it }
                    if (t - since >= config.session.plankExitEndMs) endSetAutomatically(t, events)
                }
            }
        }
    }

    /** Flushes a rep the analyzer is still holding (curl merge window), then completes the set. */
    private fun endSetAutomatically(t: Long, events: MutableList<CoachEvent>) {
        handleResults(analyzer.flushPending(), t, events)
        completeSet(t, events)
    }

    /** PAUSED for [SessionConfig.pausedEndMs] after at least one rep ends the set. */
    private fun pausedTooLong(t: Long, events: MutableList<CoachEvent>): Boolean {
        val since = pausedSinceMs ?: return false
        if (reps < 1 || t - since < config.session.pausedEndMs) return false
        endSetAutomatically(t, events)
        return true
    }

    private fun handleResults(results: List<AnalyzerResult>, t: Long, events: MutableList<CoachEvent>) {
        for (r in results) {
            when (r) {
                is AnalyzerResult.Rep -> {
                    reps++
                    for (f in r.faults) {
                        val prev = faultCounts[f.id]?.second ?: 0
                        faultCounts[f.id] = f to prev + 1
                    }
                    events.add(CoachEvent.RepCounted(t, reps, r.faults))
                    instruction = r.faults.firstOrNull()?.cue
                }
                is AnalyzerResult.Partial -> {
                    partials++
                    if (r.kind == PartialKind.NO_LOCKOUT) noLockoutCount++
                    events.add(CoachEvent.PartialRep(t, partials, r.kind, r.firstCue, r.cue))
                    instruction = if (partials == 1) r.firstCue else r.cue
                }
            }
        }
    }

    private fun isOutlier(signals: DoubleArray, t: Long): Boolean {
        val prev = lastRawSignals ?: return false
        if (prev.size != signals.size) return false
        val dt = (t - lastRawMs) / 1000.0
        if (dt <= 0.0) return false
        val maxJump = analyzer.maxRatePerSec * dt + analyzer.outlierSlack
        return signals.indices.any { kotlin.math.abs(signals[it] - prev[it]) > maxJump }
    }

    private fun onDropout(t: Long, events: MutableList<CoachEvent>) {
        if (phase == SessionPhase.PAUSED) {
            resumeRun = 0
            pausedTooLong(t, events)
            return
        }
        val last = lastGoodMs ?: t.also { lastGoodMs = it }
        if (t - last > config.tracking.dropoutGraceMs) {
            // A rep held back by the analyzer (curl merge window) was complete: count it before pausing.
            handleResults(analyzer.flushPending(), t, events)
            phase = SessionPhase.PAUSED
            pausedSinceMs = t
            analyzer.abandon()
            resumeRun = 0
            lastRawSignals = null
            outOfPlankSinceMs = null
            instruction = PAUSED_TEXT
            events.add(CoachEvent.TrackingLost(t))
        }
        // Within the grace period: hold every state and say nothing.
    }

    private fun completeSet(t: Long, events: MutableList<CoachEvent>) {
        clearIssue(t, events)
        val s = buildSummary()
        summary = s
        phase = SessionPhase.SET_COMPLETE
        instruction = "Set done. $reps reps."
        events.add(CoachEvent.SetCompleted(t, s))
    }

    private fun buildSummary(): SetSummary {
        val minFraction = config.session.faultAdviceMinFraction
        var bestAdvice: String? = null
        var bestFraction = 0.0
        if (reps > 0) {
            for ((cue, count) in faultCounts.values) {
                val fraction = count.toDouble() / reps
                if (fraction >= minFraction && fraction > bestFraction) {
                    bestFraction = fraction
                    bestAdvice = cue.advice
                }
            }
        }
        val attempts = reps + partials
        if (attempts > 0 && noLockoutCount > 0) {
            val fraction = noLockoutCount.toDouble() / attempts
            if (fraction >= minFraction && fraction > bestFraction) bestAdvice = analyzer.partialTexts.noLockoutAdvice
        }
        return SetSummary(reps, partials, bestAdvice)
    }

    private fun clearIssue(t: Long, events: MutableList<CoachEvent>) {
        if (lastIssue != null) {
            lastIssue = null
            events.add(CoachEvent.SetupIssueChanged(t, null))
        }
    }

    private fun resetHold() {
        hold.clear()
        holdView = null
    }

    private fun resetForSet() {
        analyzer = newAnalyzer(exercise)
        smoother.reset()
        swapper.reset()
        viewDetector.reset()
        reps = 0
        partials = 0
        faultCounts.clear()
        noLockoutCount = 0
        summary = null
        instruction = null
        lastIssue = null
        resetHold()
        lastGoodMs = null
        lastRawSignals = null
        resumeRun = 0
        outOfPlankSinceMs = null
        pausedSinceMs = null
        displayView = null
        displaySide = null
        primaryValue = "--"
    }

    private fun newAnalyzer(e: Exercise): ExerciseAnalyzer = when (e) {
        Exercise.SQUAT -> SquatAnalyzer(config)
        Exercise.PUSH_UP -> PushUpAnalyzer(config)
        Exercise.CURL -> CurlAnalyzer(config)
    }

    // ---- output -------------------------------------------------------------------------------

    /** Smoothed landmarks back in image pixels; gated landmarks keep their raw position and likelihood. */
    private fun toOverlay(frame: PoseFrame, landmarks: Array<Landmark?>, smoothed: Body): PoseFrame {
        val h = frame.imageHeight.coerceAtLeast(1).toDouble()
        val out = arrayOfNulls<Landmark>(LandmarkId.COUNT)
        for (i in 0 until LandmarkId.COUNT) {
            val l = landmarks[i] ?: continue
            val p = smoothed[i]
            out[i] = if (p == null) l else Landmark((p.x * h).toFloat(), (p.y * h).toFloat(), (p.z * h).toFloat(), l.inFrame)
        }
        return PoseFrame(frame.timestampMs, frame.imageWidth, frame.imageHeight, out)
    }

    private fun buildState(): WorkoutUiState {
        val inSet = phase == SessionPhase.IN_SET || phase == SessionPhase.PAUSED ||
            phase == SessionPhase.COUNTDOWN || phase == SessionPhase.SET_COMPLETE
        val arms = if (inSet) analyzer.armCounts() else null
        val view = if (analyzer.calibrated && inSet) analyzer.view else displayView
        val side = when {
            view == CameraView.FRONT -> null
            analyzer.calibrated && inSet -> analyzer.side
            else -> null
        }
        val showHint = phase == SessionPhase.IDLE || phase == SessionPhase.POSITIONING
        return WorkoutUiState(
            exercise = exercise,
            phase = phase,
            reps = reps,
            partialReps = partials,
            leftReps = arms?.first,
            rightReps = arms?.second,
            instruction = instruction,
            placementHint = if (showHint) analyzer.placementHint else null,
            view = view,
            lockedSide = side,
            primaryLabel = analyzer.primaryLabel(view ?: displayView ?: CameraView.SIDE),
            primaryValue = primaryValue,
            tracking = tracking,
            overlay = overlay,
            summary = summary,
        )
    }

    companion object {
        const val HOLD_STILL = "Hold still."
        const val READY_TEXT = "Ready. Go."
        const val PAUSED_TEXT = "Step back into view."
    }
}
