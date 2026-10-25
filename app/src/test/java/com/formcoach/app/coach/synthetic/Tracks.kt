package com.formcoach.app.coach.synthetic

import com.formcoach.app.coach.Landmark
import com.formcoach.app.coach.LandmarkId
import com.formcoach.app.coach.PoseFrame
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToLong

/** A pose that changes over time. Time is milliseconds from the start of the clip. */
internal interface PoseTrack {
    val durationMs: Double
    fun poseAt(tMs: Double): Pose
}

internal class FnTrack(override val durationMs: Double, private val fn: (Double) -> Pose) : PoseTrack {
    override fun poseAt(tMs: Double): Pose = fn(tMs.coerceIn(0.0, durationMs))
}

/** Pose keyframes with cosine interpolation between neighbours. Times must be ascending. */
internal class KeyframeTrack(private val keys: List<Pair<Double, Pose>>) : PoseTrack {
    init { require(keys.isNotEmpty()) }
    override val durationMs: Double = keys.last().first

    override fun poseAt(tMs: Double): Pose {
        if (tMs <= keys.first().first) return keys.first().second.copy()
        for (i in 1 until keys.size) {
            val (t1, p1) = keys[i]
            if (tMs <= t1) {
                val (t0, p0) = keys[i - 1]
                val span = t1 - t0
                return if (span <= 0.0) p1.copy() else Pose.blend(p0, p1, (tMs - t0) / span)
            }
        }
        return keys.last().second.copy()
    }
}

/**
 * Tracks played one after another. Between two parts the end pose of one is blended into the start pose of the next
 * over [transitionMs]. [partStarts] are the part start times on the combined timeline.
 */
internal class SequenceTrack(private val parts: List<PoseTrack>, private val transitionMs: Double) : PoseTrack {
    val partStarts: List<Double>
    override val durationMs: Double

    init {
        require(parts.isNotEmpty())
        val starts = ArrayList<Double>()
        var t = 0.0
        for ((i, part) in parts.withIndex()) {
            starts += t
            t += part.durationMs
            if (i < parts.lastIndex) t += transitionMs
        }
        partStarts = starts
        durationMs = t
    }

    override fun poseAt(tMs: Double): Pose {
        val t = tMs.coerceIn(0.0, durationMs)
        for (i in parts.indices) {
            val s = partStarts[i]
            val e = s + parts[i].durationMs
            if (t <= e) return parts[i].poseAt(t - s)
            if (i < parts.lastIndex && t < partStarts[i + 1]) {
                val u = (t - e) / transitionMs
                return Pose.blend(parts[i].poseAt(parts[i].durationMs), parts[i + 1].poseAt(0.0), u)
            }
        }
        return parts.last().poseAt(parts.last().durationMs)
    }
}

internal fun cosEase(u: Double): Double = 0.5 - 0.5 * cos(PI * u.coerceIn(0.0, 1.0))

// --- Repetition schedule ----------------------------------------------------------------------------------------

internal enum class Stage { LEAD, DOWN, HOLD, UP, REST }

internal class RepSlot(
    val startMs: Double,
    val downMs: Double,
    val holdMs: Double,
    val upMs: Double,
    val pauseMs: Double,
) {
    val extremeMs get() = startMs + downMs + holdMs / 2
    val endMs get() = startMs + downMs + holdMs + upMs
}

/** [rep] is the 0-based slot; [p] is 0 at the top, 1 at the extreme, already eased. */
internal class Located(val rep: Int, val stage: Stage, val p: Double)

internal class RepSchedule(val slots: List<RepSlot>, val tailMs: Double) {
    /** End of the last rep plus the tail. */
    val totalMs: Double get() = (slots.lastOrNull()?.endMs ?: 0.0) + tailMs

    fun locate(t: Double): Located {
        if (slots.isEmpty()) return Located(0, Stage.LEAD, 0.0)
        for (i in slots.indices) {
            val s = slots[i]
            if (t < s.startMs) return if (i == 0) Located(0, Stage.LEAD, 0.0) else Located(i - 1, Stage.REST, 0.0)
            val tDown = s.startMs + s.downMs
            val tHold = tDown + s.holdMs
            val tUp = tHold + s.upMs
            if (t < tDown) return Located(i, Stage.DOWN, cosEase((t - s.startMs) / s.downMs))
            if (t < tHold) return Located(i, Stage.HOLD, 1.0)
            if (t < tUp) return Located(i, Stage.UP, 1.0 - cosEase((t - tHold) / s.upMs))
        }
        return Located(slots.lastIndex, Stage.REST, 0.0)
    }

    companion object {
        /** Slots laid out one after another after [leadInMs]. Each timing is (downMs, holdMs, upMs, pauseAfterMs). */
        fun build(leadInMs: Double, timings: List<DoubleArray>, tailMs: Double): RepSchedule {
            val slots = ArrayList<RepSlot>()
            var t = leadInMs
            for (x in timings) {
                require(x[0] >= 1.0 && x[2] >= 1.0) { "rep phases must last at least 1 ms" }
                val slot = RepSlot(t, x[0], x[1], x[2], x[3])
                slots += slot
                t = slot.endMs + slot.pauseMs
            }
            return RepSchedule(slots, tailMs)
        }
    }
}

/** The value of a "top / rest" parameter in effect at [loc]: a descent starts from the previous rep's top. */
internal fun topFor(loc: Located, tops: List<Double>): Double =
    if (loc.stage == Stage.DOWN && loc.rep > 0) tops[loc.rep - 1] else tops[loc.rep]

// --- Rendering --------------------------------------------------------------------------------------------------

/** Render a track to frames at [fps], starting at timestamp [startMs]. */
internal fun renderFrames(track: PoseTrack, camera: CameraSpec, fps: Int, startMs: Long): List<PoseFrame> {
    require(fps > 0) { "fps must be positive" }
    val n = floor(track.durationMs / 1000.0 * fps + 1e-6).toInt() + 1
    return List(n) { i ->
        val tMs = i * 1000.0 / fps
        project(track.poseAt(tMs), camera, startMs + (i * 1000.0 / fps).roundToLong())
    }
}

/**
 * Project a pose into the image. Landmarks outside the image keep their coordinates but get inFrame 0.08. When no
 * landmark is inside the image the frame is empty (the detector would find nobody).
 */
internal fun project(pose: Pose, cam: CameraSpec, timestampMs: Long): PoseFrame {
    val pts = pose.points()
    val h = cam.bodyHeightPx
    val dir = if (cam.nearSide == Side.RIGHT) 1.0 else -1.0
    val n = cam.nearSide.sign.toDouble()
    val out = arrayOfNulls<Landmark>(LandmarkId.COUNT)
    var anyInside = false
    for (i in 0 until LandmarkId.COUNT) {
        val p = pts[i]
        val x: Double
        val z: Double
        var far = false
        if (cam.view == View.FRONT) {
            x = cam.centerX + p.lat * h
            z = -p.fwd * h
        } else {
            val nearness = p.lat * n // positive = toward the camera
            far = nearness < -0.03
            x = cam.centerX + dir * p.fwd * h + if (nearness < 0) -dir * 0.12 * (-nearness) * h else 0.0
            z = -nearness * h
        }
        val y = cam.floorY - p.up * h
        val inside = x >= 0 && x <= cam.width && y >= 0 && y <= cam.height
        if (inside) anyInside = true
        val inFrame = if (!inside) 0.08f else if (far) 0.94f else 0.99f
        out[i] = Landmark(x.toFloat(), y.toFloat(), z.toFloat(), inFrame)
    }
    return if (anyInside) PoseFrame(timestampMs, cam.width, cam.height, out)
    else PoseFrame.empty(timestampMs, cam.width, cam.height)
}

/** Finish a clip: render [track], attach [truth] and keep the script for composition. */
internal fun finishClip(track: PoseTrack, camera: CameraSpec, fps: Int, startMs: Long, truth: Truth): SyntheticClip =
    SyntheticClip(
        frames = renderFrames(track, camera, fps, startMs),
        truth = truth,
        bodyHeightPx = camera.bodyHeightPx,
        source = ClipSource(track, camera, fps, startMs),
    )
