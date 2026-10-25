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

object PushUpFaults {
    val HIPS_SAG = FaultCue("hips_sag", "Hips up.", "squeeze your glutes")
    val HIPS_PIKE = FaultCue("hips_pike", "Hips down.", "keep a straight line")
    val FAST_DESCENT = FaultCue("fast_descent", "Slow down.", "control the way down")
}

/** Push-up, side view only. Primary signal: elbow angle on the locked side. */
class PushUpAnalyzer(config: ExerciseConfig) : ExerciseAnalyzer(Exercise.PUSH_UP, config) {
    private val c = config.pushUp

    override val allowedViews = setOf(CameraView.SIDE)
    override val requiredJoints = listOf(Joint.SHOULDER, Joint.ELBOW, Joint.WRIST, Joint.HIP, Joint.ANKLE)
    override val sideJoints = listOf(Joint.SHOULDER, Joint.ELBOW, Joint.WRIST, Joint.HIP, Joint.ANKLE)
    override val visibilityIssue = SetupIssue("visibility", "Step back so I can see your whole body.")
    override val viewIssue = SetupIssue("view", "Turn sideways to the camera.")
    override val placementHint = "Phone on the floor, landscape, side-on, 1.5-2 m away."
    override val partialTexts = PartialTexts(
        shallowFirst = "Not counted. Go lower.",
        shallow = "Lower.",
        noLockoutFirst = "Lock out.",
        noLockout = "Lock out.",
        noLockoutAdvice = "straighten your arms at the top",
    )
    override val usesPlankExit = true

    private val startPosture = SetupIssue("start_posture", "Get into a high plank.")
    private val handsForward = SetupIssue("hands_forward", "Hands under your shoulders.")

    private var armLength = 0.3
    private var bodyLength = 0.8
    private var counter: RepCounter? = null

    private class F(
        val t: Long,
        val line: Double,
        val hipOffset: Double,
        val wrist: Pt,
        val shoulderY: Double,
    )

    private val frames = FrameBuffer<F>(c.counter.maxDurationMs + 3000)

    override val maxRatePerSec: Double get() = c.counter.maxRatePerSec
    override val outlierSlack: Double get() = c.counter.outlierSlack

    override fun primaryLabel(view: CameraView) = "ELBOW ANGLE"

    override fun primaryValue(body: Body, view: CameraView, side: BodySide?): String {
        val s = side ?: chooseSide(body) ?: return "--"
        return elbowAngle(body, s)?.let { formatAngle(it) } ?: "--"
    }

    override fun signalIsAngle(view: CameraView) = true

    private fun elbowAngle(body: Body, s: BodySide): Double? {
        val sh = body.j(Joint.SHOULDER, s) ?: return null
        val el = body.j(Joint.ELBOW, s) ?: return null
        val wr = body.j(Joint.WRIST, s) ?: return null
        return Geometry.angleDeg(sh, el, wr)
    }

    private fun lineAngle(body: Body, s: BodySide): Double? {
        val sh = body.j(Joint.SHOULDER, s) ?: return null
        val an = body.j(Joint.ANKLE, s) ?: return null
        return Geometry.fromHorizontalDeg(sh, an)
    }

    private fun armLen(body: Body, s: BodySide): Double? {
        val sh = body.j(Joint.SHOULDER, s) ?: return null
        val el = body.j(Joint.ELBOW, s) ?: return null
        val wr = body.j(Joint.WRIST, s) ?: return null
        return Geometry.dist(sh, el) + Geometry.dist(el, wr)
    }

    override fun positioningSignals(body: Body, view: CameraView, side: BodySide?): DoubleArray? {
        val s = side ?: return null
        return elbowAngle(body, s)?.let { doubleArrayOf(it) }
    }

    override fun setupIssue(body: Body, view: CameraView, side: BodySide?): SetupIssue? {
        val s = side ?: return startPosture
        val elbow = elbowAngle(body, s)
        val line = lineAngle(body, s)
        if (elbow == null || line == null || elbow < c.startElbowMinDeg || line > c.gatePlankLineDeg) return startPosture
        val sh = body.j(Joint.SHOULDER, s) ?: return null
        val wr = body.j(Joint.WRIST, s) ?: return null
        val arm = armLen(body, s) ?: return null
        if (abs(sh.x - wr.x) > c.handsForwardArm * arm) return handsForward
        return null
    }

    override fun calibrateLocked(window: List<Body>) {
        val s = side ?: BodySide.LEFT
        side = s
        armLength = Stats.median(window.mapNotNull { armLen(it, s) }).coerceAtLeast(1e-3)
        bodyLength = Stats.median(window.mapNotNull { b ->
            val sh = b.j(Joint.SHOULDER, s) ?: return@mapNotNull null
            val an = b.j(Joint.ANKLE, s) ?: return@mapNotNull null
            Geometry.dist(sh, an)
        }).coerceAtLeast(1e-3)
        val rc = RepCounter(c.counter)
        rc.calibrate(Stats.median(window.mapNotNull { elbowAngle(it, s) }))
        counter = rc
        frames.clear()
    }

    override fun signals(body: Body): DoubleArray? {
        val s = side ?: return null
        return elbowAngle(body, s)?.let { doubleArrayOf(it) }
    }

    override fun process(body: Body): List<AnalyzerResult> {
        val rc = counter ?: return emptyList()
        val s = side ?: return emptyList()
        val sig = signals(body)?.get(0) ?: return emptyList()
        val sh = body.j(Joint.SHOULDER, s) ?: return emptyList()
        val an = body.j(Joint.ANKLE, s) ?: return emptyList()
        val hip = body.j(Joint.HIP, s) ?: return emptyList()
        val wr = body.j(Joint.WRIST, s) ?: return emptyList()
        val f = F(
            t = body.timestampMs,
            line = Geometry.fromHorizontalDeg(sh, an),
            hipOffset = Geometry.signedBelowLine(sh, an, hip) / bodyLength,
            wrist = wr,
            shoulderY = sh.y,
        )
        frames.add(f.t, f)
        return when (val e = rc.update(f.t, sig)) {
            is RepCounterEvent.Completed -> evaluate(e)
            is RepCounterEvent.Partial -> {
                val rep = frames.between(e.startMs, e.confirmMs)
                if (patternGatesPass(rep)) listOf(partialTexts.partial(e.confirmMs, e.kind)) else emptyList()
            }
            null -> emptyList()
        }
    }

    /** In a plank throughout, hands planted. */
    private fun patternGatesPass(rep: List<F>): Boolean {
        if (rep.isEmpty()) return false
        if (robustMax(rep.map { it.line }) > c.gatePlankLineDeg) return false
        val w0 = rep.first().wrist
        if (robustMax(rep.map { Geometry.dist(it.wrist, w0) }) >= c.gateWristMoveArm * armLength) return false
        return true
    }

    private fun evaluate(e: RepCounterEvent.Completed): List<AnalyzerResult> {
        val rep = frames.between(e.startMs, e.endMs)
        if (!patternGatesPass(rep)) return emptyList()
        val drop = rep.maxOf { it.shoulderY } - rep.first().shoulderY
        if (!e.bridged && drop < c.gateShoulderDropArm * armLength) return emptyList()
        val faults = ArrayList<FaultCue>()
        val offsets = rep.map { it.hipOffset }
        if (robustMax(offsets) > c.hipSagBody) faults.add(PushUpFaults.HIPS_SAG)
        else if (-robustMin(offsets) > c.hipPikeBody) faults.add(PushUpFaults.HIPS_PIKE)
        if (e.downMs < c.fastDescentMs) faults.add(PushUpFaults.FAST_DESCENT)
        return listOf(AnalyzerResult.Rep(e.confirmMs, faults))
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

    override fun inPlank(body: Body): Boolean {
        val s = side ?: return false
        val line = lineAngle(body, s) ?: return false
        return line <= c.gatePlankLineDeg
    }
}
