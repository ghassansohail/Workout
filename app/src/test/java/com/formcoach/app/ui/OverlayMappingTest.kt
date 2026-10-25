package com.formcoach.app.ui

import com.formcoach.app.coach.BodySide
import com.formcoach.app.coach.Landmark
import com.formcoach.app.coach.SessionPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayMappingTest {
    private val eps = 1e-3f

    @Test fun portraitImageInWiderViewIsPillarboxed() {
        // 480x640 image in a 1000x800 view: scale 1.25, image 600 wide, bars of 200 left and right.
        val t = fitCenterTransform(480f, 640f, 1000f, 800f, mirrorX = false)
        assertEquals(1.25f, t.scale, eps)
        assertEquals(200f, t.mapX(0f), eps)
        assertEquals(800f, t.mapX(480f), eps)
        assertEquals(0f, t.mapY(0f), eps)
        assertEquals(800f, t.mapY(640f), eps)
    }

    @Test fun landscapeImageInTallViewIsLetterboxed() {
        // 640x480 image in 800x1000: scale 1.25, image 600 tall, bars of 200 top and bottom.
        val t = fitCenterTransform(640f, 480f, 800f, 1000f, mirrorX = false)
        assertEquals(1.25f, t.scale, eps)
        assertEquals(0f, t.mapX(0f), eps)
        assertEquals(200f, t.mapY(0f), eps)
        assertEquals(800f, t.mapY(480f), eps)
    }

    @Test fun mirroringFlipsXWithinTheImageArea() {
        val t = fitCenterTransform(480f, 640f, 1000f, 800f, mirrorX = true)
        assertEquals(800f, t.mapX(0f), eps)
        assertEquals(200f, t.mapX(480f), eps)
        assertEquals(500f, t.mapX(240f), eps)
        assertEquals(400f, t.mapY(320f), eps)
    }

    @Test fun degenerateSizesDoNotCrash() {
        val t = fitCenterTransform(0f, 0f, 0f, 0f, mirrorX = false)
        assertEquals(5f, t.mapX(5f), eps)
    }

    @Test fun sides() {
        assertEquals(null, sideOf(0))
        assertEquals(BodySide.LEFT, sideOf(11))
        assertEquals(BodySide.RIGHT, sideOf(12))
        assertEquals(BodySide.LEFT, sideOf(27))
        assertEquals(BodySide.RIGHT, sideOf(32))
        assertEquals(BodySide.LEFT, sideOf(2))
        assertEquals(BodySide.RIGHT, sideOf(5))
    }

    @Test fun jointStyles() {
        val good = Landmark(0f, 0f, 0f, 0.9f)
        val poor = Landmark(0f, 0f, 0f, 0.4f)
        assertEquals(JointStyle.HIDDEN, jointStyle(null, 11, BodySide.LEFT))
        assertEquals(JointStyle.LOW_CONFIDENCE, jointStyle(poor, 11, BodySide.LEFT))
        assertEquals(JointStyle.LOCKED, jointStyle(good, 11, BodySide.LEFT))
        assertEquals(JointStyle.NORMAL, jointStyle(good, 12, BodySide.LEFT))
        assertEquals(JointStyle.NORMAL, jointStyle(good, 11, null))
    }

    @Test fun instructionHold() {
        assertEquals(1500L, holdRemainingMs(shownAtMs = 1000, nowMs = 1500))
        assertEquals(0L, holdRemainingMs(shownAtMs = 1000, nowMs = 5000))
    }

    @Test fun activePhases() {
        assertFalse(isSetActive(SessionPhase.IDLE))
        assertFalse(isSetActive(SessionPhase.SET_COMPLETE))
        assertTrue(isSetActive(SessionPhase.POSITIONING))
        assertTrue(isSetActive(SessionPhase.IN_SET))
        assertTrue(isSetActive(SessionPhase.PAUSED))
    }
}
