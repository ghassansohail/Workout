package com.formcoach.app.camera

import com.formcoach.app.coach.Landmark
import com.formcoach.app.coach.LandmarkId
import com.formcoach.app.coach.PoseFrame
import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.PoseLandmark

/** Converts ML Kit [Pose] results into the pure [PoseFrame] the coaching engine consumes. */
object PoseFrameMapper {
    /** ML Kit landmark types in [LandmarkId] order: index i must equal LandmarkId i. */
    internal val ML_KIT_TYPES: IntArray = intArrayOf(
        PoseLandmark.NOSE, PoseLandmark.LEFT_EYE_INNER, PoseLandmark.LEFT_EYE, PoseLandmark.LEFT_EYE_OUTER,
        PoseLandmark.RIGHT_EYE_INNER, PoseLandmark.RIGHT_EYE, PoseLandmark.RIGHT_EYE_OUTER,
        PoseLandmark.LEFT_EAR, PoseLandmark.RIGHT_EAR, PoseLandmark.LEFT_MOUTH, PoseLandmark.RIGHT_MOUTH,
        PoseLandmark.LEFT_SHOULDER, PoseLandmark.RIGHT_SHOULDER, PoseLandmark.LEFT_ELBOW, PoseLandmark.RIGHT_ELBOW,
        PoseLandmark.LEFT_WRIST, PoseLandmark.RIGHT_WRIST, PoseLandmark.LEFT_PINKY, PoseLandmark.RIGHT_PINKY,
        PoseLandmark.LEFT_INDEX, PoseLandmark.RIGHT_INDEX, PoseLandmark.LEFT_THUMB, PoseLandmark.RIGHT_THUMB,
        PoseLandmark.LEFT_HIP, PoseLandmark.RIGHT_HIP, PoseLandmark.LEFT_KNEE, PoseLandmark.RIGHT_KNEE,
        PoseLandmark.LEFT_ANKLE, PoseLandmark.RIGHT_ANKLE, PoseLandmark.LEFT_HEEL, PoseLandmark.RIGHT_HEEL,
        PoseLandmark.LEFT_FOOT_INDEX, PoseLandmark.RIGHT_FOOT_INDEX,
    )

    /** True when ML Kit's numbering matches [LandmarkId] for every landmark. */
    fun idsMatch(): Boolean =
        ML_KIT_TYPES.size == LandmarkId.COUNT && ML_KIT_TYPES.indices.all { ML_KIT_TYPES[it] == it }

    init {
        check(idsMatch()) { "ML Kit PoseLandmark ids differ from LandmarkId" }
    }

    /**
     * [imageWidth] and [imageHeight] are the size of the upright (already rotated) image, the same
     * space ML Kit reports landmark positions in.
     */
    fun map(pose: Pose, timestampMs: Long, imageWidth: Int, imageHeight: Int): PoseFrame {
        val landmarks = arrayOfNulls<Landmark>(LandmarkId.COUNT)
        for (i in 0 until LandmarkId.COUNT) {
            val l = pose.getPoseLandmark(ML_KIT_TYPES[i]) ?: continue
            landmarks[i] = Landmark(
                x = l.position.x,
                y = l.position.y,
                z = l.position3D.z,
                inFrame = l.inFrameLikelihood,
            )
        }
        return PoseFrame(timestampMs, imageWidth, imageHeight, landmarks)
    }
}
