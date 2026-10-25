package com.formcoach.app.coach

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BodyGeometryTest {
    private val gate = 0.5f

    @Test
    fun anglesInDegrees() {
        assertEquals(90.0, Geometry.angleDeg(Pt(1.0, 0.0), Pt(0.0, 0.0), Pt(0.0, 1.0))!!, 1e-9)
        assertEquals(180.0, Geometry.angleDeg(Pt(-1.0, 0.0), Pt(0.0, 0.0), Pt(1.0, 0.0))!!, 1e-9)
        assertNull(Geometry.angleDeg(Pt(0.0, 0.0), Pt(0.0, 0.0), Pt(1.0, 0.0)))
    }

    @Test
    fun segmentOrientation() {
        // y grows downward: (0,1) -> (0,0) points straight up.
        assertEquals(0.0, Geometry.fromUpDeg(Pt(0.0, 1.0), Pt(0.0, 0.0)), 1e-9)
        assertEquals(45.0, Geometry.signedFromUpDeg(Pt(0.0, 1.0), Pt(1.0, 0.0)), 1e-9)
        assertEquals(-45.0, Geometry.signedFromUpDeg(Pt(0.0, 1.0), Pt(-1.0, 0.0)), 1e-9)
        assertEquals(0.0, Geometry.fromDownDeg(Pt(0.0, 0.0), Pt(0.0, 1.0)), 1e-9)
        assertEquals(30.0, Geometry.fromHorizontalDeg(Pt(0.0, 0.0), Pt(-Math.sqrt(3.0), 1.0)), 1e-9)
    }

    @Test
    fun signedDistanceBelowLineWorksFacingEitherWay() {
        val below = Pt(0.5, 0.6)
        assertTrue(Geometry.signedBelowLine(Pt(0.0, 0.5), Pt(1.0, 0.5), below) > 0)
        assertTrue(Geometry.signedBelowLine(Pt(1.0, 0.5), Pt(0.0, 0.5), below) > 0)
        assertTrue(Geometry.signedBelowLine(Pt(1.0, 0.5), Pt(0.0, 0.5), Pt(0.5, 0.4)) < 0)
        assertEquals(0.1, Geometry.signedBelowLine(Pt(0.0, 0.5), Pt(1.0, 0.5), below), 1e-9)
    }

    @Test
    fun gatingDropsLowLikelihoodAndNormalisesByHeight() {
        val lms = arrayOfNulls<Landmark>(LandmarkId.COUNT)
        lms[LandmarkId.LEFT_HIP] = Landmark(320f, 640f, 64f, 0.9f)
        lms[LandmarkId.RIGHT_HIP] = Landmark(320f, 640f, 0f, 0.4f)
        val b = Body.fromFrame(PoseFrame(0, 480, 640, lms), gate)
        assertEquals(0.5, b[LandmarkId.LEFT_HIP]!!.x, 1e-6)
        assertEquals(1.0, b[LandmarkId.LEFT_HIP]!!.y, 1e-6)
        assertEquals(0.1, b[LandmarkId.LEFT_HIP]!!.z, 1e-6)
        assertNull(b[LandmarkId.RIGHT_HIP])
    }

    @Test
    fun mirrorIds() {
        assertEquals(LandmarkId.RIGHT_KNEE, mirrorLandmarkId(LandmarkId.LEFT_KNEE))
        assertEquals(LandmarkId.LEFT_FOOT_INDEX, mirrorLandmarkId(LandmarkId.RIGHT_FOOT_INDEX))
        assertEquals(LandmarkId.RIGHT_EYE_OUTER, mirrorLandmarkId(LandmarkId.LEFT_EYE_OUTER))
        assertEquals(LandmarkId.LEFT_EAR, mirrorLandmarkId(LandmarkId.RIGHT_EAR))
        for (i in 0 until LandmarkId.COUNT) assertEquals(i, mirrorLandmarkId(mirrorLandmarkId(i)))
    }

    @Test
    fun kneeAngleOfStickFigure() {
        val b = Body.fromFrame(TestPoses.squatSide(0, 100.0), gate)
        val a = Geometry.angleDeg(b.j(Joint.HIP, BodySide.LEFT)!!, b.j(Joint.KNEE, BodySide.LEFT)!!, b.j(Joint.ANKLE, BodySide.LEFT)!!)!!
        assertEquals(100.0, a, 0.5)
    }

    @Test
    fun viewRatioFrontAndSide() {
        val front = Body.fromFrame(TestPoses.squatFront(0, 0.0), gate)
        val side = Body.fromFrame(TestPoses.squatSide(0, 178.0), gate)
        assertTrue(Geometry.viewRatio(front)!! > 0.5)
        assertTrue(Geometry.viewRatio(side)!! < 0.3)
    }

    @Test
    fun viewDetectorNeedsOneSecondAndUsesHysteresis() {
        val d = ViewDetector(SessionConfig())
        assertNull(d.update(0.7, 0))
        assertNull(d.update(0.7, 500))
        assertEquals(CameraView.FRONT, d.update(0.7, 1000))
        // 0.47 is inside the hysteresis band: stays FRONT.
        assertEquals(CameraView.FRONT, d.update(0.47, 1100))
        assertEquals(CameraView.FRONT, d.candidate)
        // Below 0.45 it becomes ANGLED, and is stable only after 1 s.
        d.update(0.40, 1200)
        assertEquals(CameraView.ANGLED, d.candidate)
        assertEquals(CameraView.FRONT, d.stable)
        assertEquals(CameraView.ANGLED, d.update(0.40, 2200))
        // From ANGLED, 0.47 is not enough for FRONT.
        d.update(0.47, 2300)
        assertEquals(CameraView.ANGLED, d.candidate)
        d.update(0.2, 2400)
        assertEquals(CameraView.SIDE, d.candidate)
        d.update(0.33, 2500) // within SIDE hysteresis
        assertEquals(CameraView.SIDE, d.candidate)
    }

    @Test
    fun missingFarShoulderCountsAsSide() {
        val d = ViewDetector(SessionConfig())
        d.update(null, 0)
        assertEquals(CameraView.SIDE, d.update(null, 1000))
    }

    @Test
    fun nearSideUsesZ() {
        val joints = listOf(Joint.HIP, Joint.KNEE, Joint.ANKLE)
        val left = Body.fromFrame(TestPoses.squatSide(0, 120.0, near = BodySide.LEFT), gate)
        val right = Body.fromFrame(TestPoses.squatSide(0, 120.0, near = BodySide.RIGHT), gate)
        assertEquals(BodySide.LEFT, Geometry.nearSide(left, joints))
        assertEquals(BodySide.RIGHT, Geometry.nearSide(right, joints))
        assertEquals(BodySide.RIGHT, Geometry.nearSide(listOf(right, right), joints))
    }

    @Test
    fun nearSidePrefersTheCompleteSide() {
        val f = TestPoses.squatSide(0, 120.0, near = BodySide.LEFT, hide = setOf(LandmarkId.LEFT_KNEE))
        val b = Body.fromFrame(f, gate)
        assertEquals(BodySide.RIGHT, Geometry.nearSide(b, listOf(Joint.HIP, Joint.KNEE, Joint.ANKLE)))
    }

    @Test
    fun swapCorrectorFrontViewRestoresLeftRightOrder() {
        val c = SwapCorrector(TrackingConfig())
        val ref = Body.fromFrame(TestPoses.squatFront(0, 0.0), gate)
        c.lockFront(listOf(ref))
        val pts = TestPoses.squatFrontPts(0.1)
        TestPoses.swapSides(pts)
        val swapped = TestPoses.frame(33, TestPoses.PORTRAIT_W, TestPoses.PORTRAIT_H, pts)
        val fixed = c.correct(swapped)
        val original = TestPoses.squatFront(33, 0.1)
        assertEquals(original[LandmarkId.LEFT_KNEE]!!.x, fixed[LandmarkId.LEFT_KNEE]!!.x, 1e-3f)
        assertEquals(original[LandmarkId.RIGHT_WRIST]!!.x, fixed[LandmarkId.RIGHT_WRIST]!!.x, 1e-3f)
    }

    @Test
    fun swapCorrectorSideViewKeepsLockedSideNear() {
        val c = SwapCorrector(TrackingConfig())
        c.lockSide(BodySide.LEFT)
        val swapped = TestPoses.squatSide(0, 120.0, near = BodySide.RIGHT)
        val fixed = c.correct(swapped)
        val original = TestPoses.squatSide(0, 120.0, near = BodySide.LEFT)
        assertNotNull(fixed[LandmarkId.LEFT_KNEE])
        assertEquals(original[LandmarkId.LEFT_KNEE]!!.z, fixed[LandmarkId.LEFT_KNEE]!!.z, 1e-3f)
        assertEquals(original[LandmarkId.LEFT_KNEE]!!.x, fixed[LandmarkId.LEFT_KNEE]!!.x, 1e-3f)
    }

    @Test
    fun percentileAndSd() {
        val v = listOf(1.0, 2.0, 3.0, 4.0, 5.0)
        assertEquals(3.0, Stats.median(v), 1e-9)
        assertEquals(4.6, Stats.percentile(v, 0.9), 1e-9)
        assertEquals(Math.sqrt(2.0), Stats.sd(v), 1e-9)
    }
}
