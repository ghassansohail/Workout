package com.formcoach.app.coach

/**
 * One landmark in image pixel coordinates of the upright (already rotated) analysis image.
 * [z] is ML Kit's depth estimate: smaller means closer to the camera, roughly the same scale as x.
 * [inFrame] is ML Kit's inFrameLikelihood (0..1). It says "inside the image", not "visible".
 */
data class Landmark(val x: Float, val y: Float, val z: Float, val inFrame: Float)

/** One analysed camera frame. Pure data: no Android or ML Kit types, so it can be used in JVM tests. */
class PoseFrame(
    /** Frame clock in milliseconds. Monotonic within a session. Not necessarily SystemClock time. */
    val timestampMs: Long,
    val imageWidth: Int,
    val imageHeight: Int,
    /** Size [LandmarkId.COUNT], indexed by [LandmarkId]. All null when no person was found. */
    val landmarks: Array<Landmark?>,
) {
    init {
        require(landmarks.size == LandmarkId.COUNT) { "Expected ${LandmarkId.COUNT} landmarks, got ${landmarks.size}" }
    }

    operator fun get(id: Int): Landmark? = landmarks[id]

    val hasPerson: Boolean get() = landmarks.any { it != null }

    companion object {
        fun empty(timestampMs: Long, imageWidth: Int, imageHeight: Int) =
            PoseFrame(timestampMs, imageWidth, imageHeight, arrayOfNulls(LandmarkId.COUNT))
    }
}

/** Same numbering as com.google.mlkit.vision.pose.PoseLandmark. */
object LandmarkId {
    const val NOSE = 0
    const val LEFT_EYE_INNER = 1
    const val LEFT_EYE = 2
    const val LEFT_EYE_OUTER = 3
    const val RIGHT_EYE_INNER = 4
    const val RIGHT_EYE = 5
    const val RIGHT_EYE_OUTER = 6
    const val LEFT_EAR = 7
    const val RIGHT_EAR = 8
    const val LEFT_MOUTH = 9
    const val RIGHT_MOUTH = 10
    const val LEFT_SHOULDER = 11
    const val RIGHT_SHOULDER = 12
    const val LEFT_ELBOW = 13
    const val RIGHT_ELBOW = 14
    const val LEFT_WRIST = 15
    const val RIGHT_WRIST = 16
    const val LEFT_PINKY = 17
    const val RIGHT_PINKY = 18
    const val LEFT_INDEX = 19
    const val RIGHT_INDEX = 20
    const val LEFT_THUMB = 21
    const val RIGHT_THUMB = 22
    const val LEFT_HIP = 23
    const val RIGHT_HIP = 24
    const val LEFT_KNEE = 25
    const val RIGHT_KNEE = 26
    const val LEFT_ANKLE = 27
    const val RIGHT_ANKLE = 28
    const val LEFT_HEEL = 29
    const val RIGHT_HEEL = 30
    const val LEFT_FOOT_INDEX = 31
    const val RIGHT_FOOT_INDEX = 32
    const val COUNT = 33
}
