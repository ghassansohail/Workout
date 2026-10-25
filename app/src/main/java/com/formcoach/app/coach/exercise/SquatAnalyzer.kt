package com.formcoach.app.coach.exercise

import com.formcoach.app.coach.Body
import com.formcoach.app.coach.BodySide
import com.formcoach.app.coach.CameraView
import com.formcoach.app.coach.Exercise
import com.formcoach.app.coach.ExerciseConfig
import com.formcoach.app.coach.FaultCue
import com.formcoach.app.coach.Geometry
import com.formcoach.app.coach.Joint
import com.formcoach.app.coach.Pt
import com.formcoach.app.coach.RepCounter
import com.formcoach.app.coach.RepCounterEvent
import com.formcoach.app.coach.SetupIssue
import com.formcoach.app.coach.Stats
import kotlin.math.abs

object SquatFaults {
    val FORWARD_LEAN = FaultCue("forward_lean", "Chest up.", "keep your chest up")
    val HEELS_LIFT = FaultCue("heels_lift", "Heels down.", "keep your heels down")
    val KNEES_CAVE = FaultCue("knees_cave", "Knees out.", "push your knees out")
    val WEIGHT_SHIFT = FaultCue("weight_shift", "Stay centred.", "keep your weight even")
    val FAST_DESCENT = FaultCue("fast_descent", "Slow down.", "control the way down")
}

/**
 * Squat. Side view: knee angle on the locked side. Front view: hip drop ratio
 * (hipY - calibrated hipY) / calibrated hip-to-ankle length.
 */
class SquatAnalyzer(config: ExerciseConfig) : ExerciseAnalyzer(Exercise.SQUAT, config) {
    private val c = config.squat

    override val allowedViews = setOf(CameraView.SIDE, CameraView.FRONT)
    override val requiredJoints = listOf(Joint.SHOULDER, Joint.HIP, Joint.KNEE, Joint.ANKLE)
    override val sideJoints = listOf(Joint.HIP, Joint.KNEE, Joint.ANKLE)
    override val visibilityIssue = SetupIssue("visibility", "Step back so I can see your whole body.")
    override val viewIssue = SetupIssue("view", "Turn fully sideways, or face the camera.")
    override val placementHint = "Phone at hip height, 2-3 m away, side-on or facing you."
    override val partialTexts = PartialTexts(
        shallowFirst = "Not counted. Go lower.",
        shallow = "Lower.",
        noLockoutFirst = "Stand all the way up.",
        noLockout = "Stand tall.",
        noLockoutAdvice = "stand all the way up",
    )

    private val startPosture = SetupIssue("start_posture", "Stand tall.")
    private val stanceNarrow = SetupIssue("stance_narrow", "Feet a little wider.")
    private val stanceWide = SetupIssue("stance_wide", "Feet a little closer.")

    // Calibration.
    private var legLength = 0.4
    private var hipAnkleLength = 0.4
    private var calHipY = 0.0
    private var hipWidth = 0.1
    private var ankleRef: Pt? = null
    private var counter: RepCounter? = null

    private class F(
        val t: Long,
        val body: Body,
        val signal: Double,
        val lean: Double,
        val shin: Double?,
        val heelY: Double?,
        val ankles: List<Pt>,
        val hipY: Double,
        val kneeGap: Double?,
        val ankleGap: Double?,
        val hipMidX: Double?,
        val ankleMidX: Double?,
    )

    private val frames = FrameBuffer<F>(config.squat.sideCounter.maxDurationMs + 3000)

    override val maxRatePerSec: Double
        get() = if (view == CameraView.FRONT) c.frontCounter.maxRatePerSec else c.sideCounter.maxRatePerSec
    override val outlierSlack: Double
        get() = if (view == CameraView.FRONT) c.frontCounter.outlierSlack else c.sideCounter.outlierSlack

    override fun primaryLabel(view: CameraView) = if (view == CameraView.FRONT) "HIP DROP" else "KNEE ANGLE"

    override fun primaryValue(body: Body, view: CameraView, side: BodySide?): String {
        if (view == CameraView.FRONT) {
            if (!calibrated) return "--"
            return signals(body)?.let { formatRatio(it[0]) } ?: "--"
        }
        val s = side ?: chooseSide(body) ?: return "--"
        return kneeAngle(body, s)?.let { formatAngle(it) } ?: "--"
    }

    override fun signalIsAngle(view: CameraView) = view != CameraView.FRONT

    private fun kneeAngle(body: Body, s: BodySide): Double? {
        val hip = body.j(Joint.HIP, s) ?: return null
        val knee = body.j(Joint.KNEE, s) ?: return null
        val ankle = body.j(Joint.ANKLE, s) ?: return null
        return Geometry.angleDeg(hip, knee, ankle)
    }

    private fun torsoLean(body: Body, view: CameraView, s: BodySide?): Double? {
        return if (view == CameraView.FRONT || s == null) {
            val hip = body.mid(Joint.HIP) ?: return null
            val sh = body.mid(Joint.SHOULDER) ?: return null
            Geometry.fromUpDeg(hip, sh)
        } else {
            val hip = body.j(Joint.HIP, s) ?: return null
            val sh = body.j(Joint.SHOULDER, s) ?: return null
            Geometry.fromUpDeg(hip, sh)
        }
    }

    override fun positioningSignals(body: Body, view: CameraView, side: BodySide?): DoubleArray? {
        if (view == CameraView.FRONT) {
            val hip = body.mid(Joint.HIP) ?: return null
            val ankle = body.mid(Joint.ANKLE) ?: return null
            val len = Geometry.dist(hip, ankle)
            if (len < 1e-6) return null
            return doubleArrayOf(hip.y / len)
        }
        val s = side ?: return null
        return kneeAngle(body, s)?.let { doubleArrayOf(it) }
    }

    override fun setupIssue(body: Body, view: CameraView, side: BodySide?): SetupIssue? {
        val knees = if (view == CameraView.FRONT) {
            listOfNotNull(kneeAngle(body, BodySide.LEFT), kneeAngle(body, BodySide.RIGHT))
        } else {
            listOfNotNull(side?.let { kneeAngle(body, it) })
        }
        val lean = torsoLean(body, view, side)
        if (knees.isEmpty() || knees.any { it < c.startKneeMinDeg } || lean == null || lean > c.startTorsoLeanMaxDeg) {
            return startPosture
        }
        if (view == CameraView.FRONT) {
            val la = body.j(Joint.ANKLE, BodySide.LEFT) ?: return null
            val ra = body.j(Joint.ANKLE, BodySide.RIGHT) ?: return null
            val ls = body.j(Joint.SHOULDER, BodySide.LEFT) ?: return null
            val rs = body.j(Joint.SHOULDER, BodySide.RIGHT) ?: return null
            val shoulderWidth = Geometry.dist(ls, rs)
            if (shoulderWidth > 1e-6) {
                val ratio = Geometry.dist(la, ra) / shoulderWidth
                if (ratio < c.stanceMinRatio) return stanceNarrow
                if (ratio > c.stanceMaxRatio) return stanceWide
            }
        }
        return null
    }

    override fun calibrateLocked(window: List<Body>) {
        calibrateLengths(window)
        val cfg = if (view == CameraView.FRONT) c.frontCounter else c.sideCounter
        val rc = RepCounter(cfg)
        val top = Stats.median(window.mapNotNull { signals(it)?.get(0) })
        rc.calibrate(top)
        counter = rc
        frames.clear()
    }

    private fun calibrateLengths(window: List<Body>) {
        val sides = if (view == CameraView.FRONT) listOf(BodySide.LEFT, BodySide.RIGHT) else listOfNotNull(side)
        legLength = Stats.median(window.flatMap { b ->
            sides.mapNotNull { s ->
                val hip = b.j(Joint.HIP, s) ?: return@mapNotNull null
                val knee = b.j(Joint.KNEE, s) ?: return@mapNotNull null
                val ankle = b.j(Joint.ANKLE, s) ?: return@mapNotNull null
                Geometry.dist(hip, knee) + Geometry.dist(knee, ankle)
            }
        }).coerceAtLeast(1e-3)
        if (view == CameraView.FRONT) {
            hipAnkleLength = Stats.median(window.mapNotNull { b ->
                val h = b.mid(Joint.HIP) ?: return@mapNotNull null
                val a = b.mid(Joint.ANKLE) ?: return@mapNotNull null
                Geometry.dist(h, a)
            }).coerceAtLeast(1e-3)
            calHipY = Stats.median(window.mapNotNull { it.mid(Joint.HIP)?.y })
            hipWidth = Stats.median(window.mapNotNull { b ->
                val l = b.j(Joint.HIP, BodySide.LEFT) ?: return@mapNotNull null
                val r = b.j(Joint.HIP, BodySide.RIGHT) ?: return@mapNotNull null
                Geometry.dist(l, r)
            }).coerceAtLeast(1e-3)
        }
        ankleRef = ankleMid(window)
    }

    private fun ankleMid(window: List<Body>): Pt? {
        val pts = window.mapNotNull { b ->
            if (view == CameraView.FRONT) b.mid(Joint.ANKLE) else side?.let { b.j(Joint.ANKLE, it) }
        }
        if (pts.isEmpty()) return null
        return Pt(Stats.median(pts.map { it.x }), Stats.median(pts.map { it.y }))
    }

    override fun signals(body: Body): DoubleArray? {
        return if (view == CameraView.FRONT) {
            val hip = body.mid(Joint.HIP) ?: return null
            doubleArrayOf((hip.y - calHipY) / hipAnkleLength)
        } else {
            val s = side ?: return null
            kneeAngle(body, s)?.let { doubleArrayOf(it) }
        }
    }

    override fun process(body: Body): List<AnalyzerResult> {
        val rc = counter ?: return emptyList()
        val sig = signals(body)?.get(0) ?: return emptyList()
        val f = record(body, sig) ?: return emptyList()
        frames.add(f.t, f)
        val event = rc.update(f.t, sig)
        maybeRecalibrate(rc, f.t)
        return when (event) {
            is RepCounterEvent.Completed -> evaluate(event)
            is RepCounterEvent.Partial -> {
                val rep = frames.between(event.startMs, event.confirmMs)
                if (patternGatesPass(rep)) listOf(partialTexts.partial(event.confirmMs, event.kind)) else emptyList()
            }
            null -> emptyList()
        }
    }

    private fun record(body: Body, sig: Double): F? {
        val s = side
        val lean = torsoLean(body, view, s) ?: return null
        return if (view == CameraView.FRONT) {
            val la = body.j(Joint.ANKLE, BodySide.LEFT)
            val ra = body.j(Joint.ANKLE, BodySide.RIGHT)
            val lk = body.j(Joint.KNEE, BodySide.LEFT)
            val rk = body.j(Joint.KNEE, BodySide.RIGHT)
            val hipMid = body.mid(Joint.HIP) ?: return null
            val ankleMid = body.mid(Joint.ANKLE)
            F(
                t = body.timestampMs, body = body, signal = sig, lean = lean, shin = null, heelY = null,
                ankles = listOfNotNull(la, ra), hipY = hipMid.y,
                kneeGap = if (lk != null && rk != null) abs(lk.x - rk.x) else null,
                ankleGap = if (la != null && ra != null) abs(la.x - ra.x) else null,
                hipMidX = hipMid.x, ankleMidX = ankleMid?.x,
            )
        } else {
            s ?: return null
            val knee = body.j(Joint.KNEE, s) ?: return null
            val ankle = body.j(Joint.ANKLE, s) ?: return null
            val hip = body.j(Joint.HIP, s) ?: return null
            F(
                t = body.timestampMs, body = body, signal = sig, lean = lean,
                shin = Geometry.fromUpDeg(ankle, knee), heelY = body.j(Joint.HEEL, s)?.y,
                ankles = listOf(ankle), hipY = hip.y,
                kneeGap = null, ankleGap = null, hipMidX = null, ankleMidX = null,
            )
        }
    }

    /** Gates that apply to every movement, partial or counted: feet planted and torso not folded over. */
    private fun patternGatesPass(rep: List<F>): Boolean {
        if (rep.isEmpty()) return false
        val first = rep.first()
        for (k in first.ankles.indices) {
            val start = first.ankles[k]
            val moves = rep.mapNotNull { it.ankles.getOrNull(k)?.let { a -> Geometry.dist(a, start) } }
            if (robustMax(moves) >= c.gateAnkleMoveLeg * legLength) return false
        }
        if (robustMax(rep.map { it.lean }) >= c.gateTorsoLeanDeg) return false
        return true
    }

    private fun evaluate(e: RepCounterEvent.Completed): List<AnalyzerResult> {
        val rep = frames.between(e.startMs, e.endMs)
        if (!patternGatesPass(rep)) return emptyList()
        if (view == CameraView.SIDE && !e.bridged) {
            val startHip = rep.first().hipY
            val drop = rep.maxOf { it.hipY } - startHip
            if (drop < c.gateHipDropLeg * legLength) return emptyList()
        }
        val bottom = bottomFrames(frames, e.bottomStartMs, e.bottomEndMs)
        val faults = ArrayList<FaultCue>()
        if (view == CameraView.SIDE) {
            val lean = Stats.mean(bottom.map { it.lean })
            val shin = Stats.mean(bottom.mapNotNull { it.shin })
            if (lean > c.forwardLeanDeg && lean > shin + c.forwardLeanOverShinDeg) faults.add(SquatFaults.FORWARD_LEAN)
            val startHeel = rep.first().heelY
            if (startHeel != null) {
                val rises = rep.mapNotNull { f -> f.heelY?.let { startHeel - it } }
                if (rises.isNotEmpty() && robustMax(rises) > c.heelLiftLeg * legLength) faults.add(SquatFaults.HEELS_LIFT)
            }
        } else {
            val ratios = bottom.mapNotNull { f ->
                val k = f.kneeGap ?: return@mapNotNull null
                val a = f.ankleGap ?: return@mapNotNull null
                if (a < 1e-6) null else k / a
            }
            if (ratios.isNotEmpty() && Stats.mean(ratios) < c.kneeCaveRatio) faults.add(SquatFaults.KNEES_CAVE)
            val offsets = bottom.mapNotNull { f ->
                val h = f.hipMidX ?: return@mapNotNull null
                val a = f.ankleMidX ?: return@mapNotNull null
                abs(h - a)
            }
            if (offsets.isNotEmpty() && Stats.mean(offsets) > c.weightShiftHipWidth * hipWidth) faults.add(SquatFaults.WEIGHT_SHIFT)
        }
        if (e.downMs < c.fastDescentMs) faults.add(SquatFaults.FAST_DESCENT)
        return listOf(AnalyzerResult.Rep(e.confirmMs, faults))
    }

    /** Plan Phase 4: if the ankles drifted (the user stepped), recalibrate at the next still hold at the top. */
    private fun maybeRecalibrate(rc: RepCounter, t: Long) {
        val since = rc.topSinceMs ?: return
        val hold = config.session.holdMs
        if (t - since < hold) return
        val ref = ankleRef ?: return
        val recent = frames.last(hold)
        if (recent.size < 3) return
        val nowAnkle = ankleMid(recent.map { it.body }) ?: return
        if (Geometry.dist(nowAnkle, ref) < config.session.recalibrateAnkleDrift * legLength) return
        val still = if (signalIsAngle(view)) config.session.stillAngleSdDeg else config.session.stillRatioSd
        if (Stats.sd(recent.map { it.signal }) >= still) return
        calibrateLengths(recent.map { it.body })
        val top = Stats.median(recent.mapNotNull { signals(it.body)?.get(0) })
        rc.calibrate(top)
    }

    override fun abandon() {
        counter?.abandon()
    }

    override fun restSinceMs(): Long? = counter?.topSinceMs

    override fun inTopZone(body: Body): Boolean {
        val rc = counter ?: return false
        val s = signals(body) ?: return false
        return rc.isInTopZone(s[0])
    }
}
