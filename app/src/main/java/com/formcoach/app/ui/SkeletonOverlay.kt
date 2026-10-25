package com.formcoach.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import com.formcoach.app.coach.BodySide
import com.formcoach.app.coach.PoseFrame

private val NormalColor = Color(0xFFF2F4F3)
private val GreyColor = Color(0xFF6E7570)

/** Draws [frame] over the preview. Must use the same FIT_CENTER scaling as the PreviewView below it. */
@Composable
fun SkeletonOverlay(frame: PoseFrame?, lockedSide: BodySide?, mirrorX: Boolean, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val f = frame ?: return@Canvas
        if (!f.hasPerson) return@Canvas
        val t = fitCenterTransform(f.imageWidth.toFloat(), f.imageHeight.toFloat(), size.width, size.height, mirrorX)
        val thin = 3.dp.toPx()
        val thick = 6.dp.toPx()
        val dot = 4.dp.toPx()
        val styles = Array(f.landmarks.size) { jointStyle(f.landmarks[it], it, lockedSide) }
        fun point(id: Int): Offset {
            val l = f.landmarks[id]!!
            return Offset(t.mapX(l.x), t.mapY(l.y))
        }
        for ((a, b) in SKELETON_CONNECTIONS) {
            val sa = styles[a]
            val sb = styles[b]
            if (sa == JointStyle.HIDDEN || sb == JointStyle.HIDDEN) continue
            val (color, width) = when {
                sa == JointStyle.LOW_CONFIDENCE || sb == JointStyle.LOW_CONFIDENCE -> GreyColor to thin
                sa == JointStyle.LOCKED && sb == JointStyle.LOCKED -> Mint to thick
                else -> NormalColor to thin
            }
            drawLine(color, point(a), point(b), strokeWidth = width, cap = StrokeCap.Round)
        }
        for (id in SKELETON_JOINTS) {
            val color = when (styles[id]) {
                JointStyle.HIDDEN -> continue
                JointStyle.LOW_CONFIDENCE -> GreyColor
                JointStyle.NORMAL -> NormalColor
                JointStyle.LOCKED -> Mint
            }
            drawCircle(color, radius = if (styles[id] == JointStyle.LOCKED) thick else dot, center = point(id))
        }
    }
}
