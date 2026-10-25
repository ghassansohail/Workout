package com.formcoach.app.ui

import com.formcoach.app.coach.BodySide
import com.formcoach.app.coach.Landmark
import kotlin.math.min

/**
 * Maps points of the upright analysis image to view coordinates, matching PreviewView FIT_CENTER:
 * uniform scale so the whole image fits, centred, with letterbox bars on the longer side.
 */
data class OverlayTransform(
    val scale: Float,
    val offsetX: Float,
    val offsetY: Float,
    val imageWidth: Float,
    val mirrorX: Boolean,
) {
    fun mapX(x: Float): Float = offsetX + (if (mirrorX) imageWidth - x else x) * scale
    fun mapY(y: Float): Float = offsetY + y * scale
}

fun fitCenterTransform(
    imageWidth: Float,
    imageHeight: Float,
    viewWidth: Float,
    viewHeight: Float,
    mirrorX: Boolean,
): OverlayTransform {
    if (imageWidth <= 0f || imageHeight <= 0f || viewWidth <= 0f || viewHeight <= 0f) {
        return OverlayTransform(1f, 0f, 0f, maxOf(imageWidth, 0f), mirrorX)
    }
    val scale = min(viewWidth / imageWidth, viewHeight / imageHeight)
    return OverlayTransform(
        scale = scale,
        offsetX = (viewWidth - imageWidth * scale) / 2f,
        offsetY = (viewHeight - imageHeight * scale) / 2f,
        imageWidth = imageWidth,
        mirrorX = mirrorX,
    )
}

enum class JointStyle { HIDDEN, LOW_CONFIDENCE, NORMAL, LOCKED }

const val MIN_IN_FRAME = 0.5f

/** Left/right side of a landmark id, or null for the nose. */
fun sideOf(id: Int): BodySide? = when {
    id == 0 -> null
    id in 1..3 -> BodySide.LEFT
    id in 4..6 -> BodySide.RIGHT
    id % 2 == 1 -> BodySide.LEFT
    else -> BodySide.RIGHT
}

/** Missing landmarks are not drawn; low-confidence ones are grey; the locked side is highlighted. */
fun jointStyle(landmark: Landmark?, id: Int, lockedSide: BodySide?): JointStyle = when {
    landmark == null -> JointStyle.HIDDEN
    landmark.inFrame < MIN_IN_FRAME -> JointStyle.LOW_CONFIDENCE
    lockedSide != null && sideOf(id) == lockedSide -> JointStyle.LOCKED
    else -> JointStyle.NORMAL
}

/** Pairs of landmark ids to join with a line (body only, no face). */
val SKELETON_CONNECTIONS: List<Pair<Int, Int>> = listOf(
    11 to 12, 11 to 23, 12 to 24, 23 to 24,
    11 to 13, 13 to 15, 12 to 14, 14 to 16,
    23 to 25, 25 to 27, 24 to 26, 26 to 28,
    27 to 29, 29 to 31, 27 to 31, 28 to 30, 30 to 32, 28 to 32,
)

/** Landmark ids drawn as dots. */
val SKELETON_JOINTS: List<Int> = (11..32).toList()
