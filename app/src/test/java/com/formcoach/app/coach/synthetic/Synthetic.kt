package com.formcoach.app.coach.synthetic

import com.formcoach.app.coach.Landmark
import com.formcoach.app.coach.LandmarkId
import com.formcoach.app.coach.PoseFrame
import java.util.Random

/**
 * Entry point of the synthetic pose generator (test sources only).
 *
 * ```
 * val clip = Synthetic.squat(view = View.SIDE) {
 *     reps = 10
 *     repDurationMs = 2000
 *     override(4, 7) { bottomKneeDeg = 125.0 }
 * }.withNoise(0.01, seed = 1)
 * clip.frames        // List<PoseFrame> at 30 fps, timestamps from 0
 * clip.truth         // expectedReps = 8, expectedPartialReps = 2, ...
 * ```
 *
 * All builders take either `view`/`nearSide` (portrait 480x640 camera, 640x480 landscape for push-ups) or a full
 * [CameraSpec] in `camera`, which then wins. [fps] is 30 by default; [startMs] is the first timestamp.
 */
object Synthetic {
    private fun cam(camera: CameraSpec?, view: View, nearSide: Side, landscape: Boolean = false) =
        camera ?: if (landscape) CameraSpec.landscape(view, nearSide) else CameraSpec.portrait(view, nearSide)

    /** Squats, side view (default) or front view. */
    fun squat(
        view: View = View.SIDE, nearSide: Side = Side.RIGHT, camera: CameraSpec? = null, fps: Int = 30, startMs: Long = 0,
        configure: SquatSpec.() -> Unit = {},
    ): SyntheticClip = buildSquat(SquatSpec().apply(configure), cam(camera, view, nearSide), fps, startMs)

    /** Push-ups, side view in a 640x480 landscape image. */
    fun pushUp(
        nearSide: Side = Side.RIGHT, camera: CameraSpec? = null, fps: Int = 30, startMs: Long = 0,
        configure: PushUpSpec.() -> Unit = {},
    ): SyntheticClip = buildPushUp(PushUpSpec().apply(configure), cam(camera, View.SIDE, nearSide, landscape = true), fps, startMs)

    /** Biceps curls, front view (default) or side view. [CurlSpec.mode] picks both arms together or alternating. */
    fun curl(
        view: View = View.FRONT, nearSide: Side = Side.RIGHT, camera: CameraSpec? = null, fps: Int = 30, startMs: Long = 0,
        configure: CurlSpec.() -> Unit = {},
    ): SyntheticClip = buildCurl(CurlSpec().apply(configure), cam(camera, view, nearSide), fps, startMs)

    /** Standing still with natural sway: knee and torso wobble by up to [swayDeg] degrees. */
    fun standStill(
        durationMs: Int = 5000, swayDeg: Double = 1.0, seed: Long = 1,
        view: View = View.SIDE, nearSide: Side = Side.RIGHT, camera: CameraSpec? = null, fps: Int = 30, startMs: Long = 0,
    ) = buildStandStill(durationMs, swayDeg, seed, cam(camera, view, nearSide), fps, startMs)

    /** Walk into the frame from behind the person (side view) or from their left (front view), then stand. */
    fun walkIn(
        durationMs: Int = 2500, view: View = View.SIDE, nearSide: Side = Side.RIGHT, camera: CameraSpec? = null, fps: Int = 30, startMs: Long = 0,
    ) = buildWalkIn(durationMs, cam(camera, view, nearSide), fps, startMs)

    /** Walk out of the frame, continuing in the walking direction of [walkIn]. */
    fun stepOut(
        durationMs: Int = 2500, view: View = View.SIDE, nearSide: Side = Side.RIGHT, camera: CameraSpec? = null, fps: Int = 30, startMs: Long = 0,
    ) = buildStepOut(durationMs, cam(camera, view, nearSide), fps, startMs)

    /** Bend over (torso near horizontal, knees slightly bent) to touch the phone on the floor, then stand up. */
    fun bendOverToTouchPhone(
        view: View = View.SIDE, nearSide: Side = Side.RIGHT, camera: CameraSpec? = null, fps: Int = 30, startMs: Long = 0,
    ) = buildBendOver(cam(camera, view, nearSide), fps, startMs)

    /** Raise one arm overhead and scratch the head, then lower it. */
    fun scratchHead(
        arm: Side = Side.RIGHT, view: View = View.SIDE, nearSide: Side = Side.RIGHT, camera: CameraSpec? = null, fps: Int = 30, startMs: Long = 0,
    ) = buildScratchHead(arm, cam(camera, view, nearSide), fps, startMs)

    /** Reach toward the camera with the arm on the near side (side view) or straight ahead (front view) to adjust the phone. */
    fun reachToPhone(
        view: View = View.SIDE, nearSide: Side = Side.RIGHT, camera: CameraSpec? = null, fps: Int = 30, startMs: Long = 0,
    ) = buildReachToPhone(cam(camera, view, nearSide), fps, startMs)

    /** From the top of a push-up: knees down, kneel upright, stand up. Defaults to the push-up landscape camera. */
    fun kneelAfterPushUps(
        nearSide: Side = Side.RIGHT, camera: CameraSpec? = null, fps: Int = 30, startMs: Long = 0,
    ) = buildKneelAndStand(cam(camera, View.SIDE, nearSide, landscape = true), fps, startMs)

    /**
     * Join clips into one continuous recording: timestamps continue without gaps and the end pose of each clip is
     * blended into the start pose of the next over [transitionMs]. Truth is merged with times shifted accordingly.
     * The clips must come straight from builders (no modifiers applied yet) and share camera and fps. Apply
     * modifiers to the result.
     */
    fun sequence(vararg clips: SyntheticClip, transitionMs: Int = 600): SyntheticClip {
        require(clips.isNotEmpty()) { "no clips" }
        val sources = clips.map { it.source ?: throw IllegalArgumentException("clip has modifiers applied; compose first, then add noise/dropouts") }
        val first = sources.first()
        require(sources.all { it.camera == first.camera && it.fps == first.fps }) { "clips must share camera and fps" }
        val track = SequenceTrack(sources.map { it.track }, transitionMs.toDouble())
        var truth: Truth? = null
        for ((i, c) in clips.withIndex()) {
            val shifted = c.truth.shifted(first.startMs + Math.round(track.partStarts[i]) - sources[i].startMs)
            truth = truth?.plus(shifted) ?: shifted
        }
        return finishClip(track, first.camera, first.fps, first.startMs, truth!!)
    }
}

// --- Modifiers --------------------------------------------------------------------------------------------------

/** Handy landmark groups for [SyntheticClip.withDropout]. */
object Parts {
    val LEFT_LEG = setOf(LandmarkId.LEFT_HIP, LandmarkId.LEFT_KNEE, LandmarkId.LEFT_ANKLE, LandmarkId.LEFT_HEEL, LandmarkId.LEFT_FOOT_INDEX)
    val RIGHT_LEG = setOf(LandmarkId.RIGHT_HIP, LandmarkId.RIGHT_KNEE, LandmarkId.RIGHT_ANKLE, LandmarkId.RIGHT_HEEL, LandmarkId.RIGHT_FOOT_INDEX)
    val LEFT_ARM = setOf(
        LandmarkId.LEFT_SHOULDER, LandmarkId.LEFT_ELBOW, LandmarkId.LEFT_WRIST,
        LandmarkId.LEFT_PINKY, LandmarkId.LEFT_INDEX, LandmarkId.LEFT_THUMB,
    )
    val RIGHT_ARM = setOf(
        LandmarkId.RIGHT_SHOULDER, LandmarkId.RIGHT_ELBOW, LandmarkId.RIGHT_WRIST,
        LandmarkId.RIGHT_PINKY, LandmarkId.RIGHT_INDEX, LandmarkId.RIGHT_THUMB,
    )
    val LEGS = LEFT_LEG + RIGHT_LEG
    val ARMS = LEFT_ARM + RIGHT_ARM
    val HEAD = (LandmarkId.NOSE..LandmarkId.RIGHT_MOUTH).toSet()
    val TORSO = setOf(LandmarkId.LEFT_SHOULDER, LandmarkId.RIGHT_SHOULDER, LandmarkId.LEFT_HIP, LandmarkId.RIGHT_HIP)
}

/** Left/right landmark pairs exchanged by [SyntheticClip.withSwaps]. */
internal val SWAP_PAIRS: List<Pair<Int, Int>> = listOf(
    1 to 4, 2 to 5, 3 to 6, 7 to 8, 9 to 10, 11 to 12, 13 to 14, 15 to 16, 17 to 18, 19 to 20, 21 to 22,
    23 to 24, 25 to 26, 27 to 28, 29 to 30, 31 to 32,
)

private fun SyntheticClip.withFrames(frames: List<PoseFrame>) = copy(frames = frames, source = null)

private fun PoseFrame.withLandmarks(f: (Int, Landmark?) -> Landmark?) =
    PoseFrame(timestampMs, imageWidth, imageHeight, Array(LandmarkId.COUNT) { f(it, landmarks[it]) })

/**
 * Add Gaussian noise to x, y and z of every landmark. Sigma is [sigmaFrac] times the standing body height in
 * pixels (default 1%). Deterministic for a given [seed].
 */
fun SyntheticClip.withNoise(sigmaFrac: Double = 0.01, seed: Long = 0): SyntheticClip {
    val rnd = Random(seed)
    val s = sigmaFrac * bodyHeightPx
    return withFrames(frames.map { fr ->
        fr.withLandmarks { _, l ->
            l?.let { Landmark((it.x + rnd.nextGaussian() * s).toFloat(), (it.y + rnd.nextGaussian() * s).toFloat(), (it.z + rnd.nextGaussian() * s).toFloat(), it.inFrame) }
        }
    })
}

/**
 * Remove landmarks from every frame whose timestamp is in [fromMs, toMs): all landmarks, or only the ids in
 * [landmarks] (see [Parts]). Times are frame timestamps, so they include the clip's start time.
 */
fun SyntheticClip.withDropout(fromMs: Long, toMs: Long, landmarks: Set<Int>? = null): SyntheticClip =
    withFrames(frames.map { fr ->
        if (fr.timestampMs < fromMs || fr.timestampMs >= toMs) fr
        else if (landmarks == null) PoseFrame.empty(fr.timestampMs, fr.imageWidth, fr.imageHeight)
        else fr.withLandmarks { i, l -> if (i in landmarks) null else l }
    })

/**
 * Random dropouts. With [perLandmark] false each frame loses all landmarks with probability [rate]; with true each
 * landmark of each frame is dropped independently with probability [rate].
 */
fun SyntheticClip.withRandomDropout(rate: Double, seed: Long = 0, perLandmark: Boolean = false): SyntheticClip {
    val rnd = Random(seed)
    return withFrames(frames.map { fr ->
        if (perLandmark) fr.withLandmarks { _, l -> if (rnd.nextDouble() < rate) null else l }
        else if (rnd.nextDouble() < rate) PoseFrame.empty(fr.timestampMs, fr.imageWidth, fr.imageHeight)
        else fr
    })
}

/** On each frame, with probability [rate], exchange every left/right landmark pair (as a confused detector would). */
fun SyntheticClip.withSwaps(rate: Double, seed: Long = 0): SyntheticClip {
    val rnd = Random(seed)
    return withFrames(frames.map { fr ->
        if (rnd.nextDouble() >= rate) fr
        else {
            val lm = fr.landmarks.copyOf()
            for ((a, b) in SWAP_PAIRS) { val t = lm[a]; lm[a] = lm[b]; lm[b] = t }
            PoseFrame(fr.timestampMs, fr.imageWidth, fr.imageHeight, lm)
        }
    })
}

/** Add Gaussian noise (std [sigma]) to every inFrame likelihood, clamped to 0..1. */
fun SyntheticClip.withInFrameJitter(sigma: Double = 0.05, seed: Long = 0): SyntheticClip {
    val rnd = Random(seed)
    return withFrames(frames.map { fr ->
        fr.withLandmarks { _, l -> l?.let { it.copy(inFrame = (it.inFrame + rnd.nextGaussian() * sigma).toFloat().coerceIn(0f, 1f)) } }
    })
}
