package com.formcoach.app.coach

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PoseFrameTest {
    @Test
    fun emptyFrameHasNoPerson() {
        val frame = PoseFrame.empty(0L, 480, 640)
        assertEquals(LandmarkId.COUNT, frame.landmarks.size)
        assertFalse(frame.hasPerson)
    }

    @Test
    fun frameWithOneLandmarkHasPerson() {
        val landmarks = arrayOfNulls<Landmark>(LandmarkId.COUNT)
        landmarks[LandmarkId.LEFT_HIP] = Landmark(100f, 200f, 0f, 0.9f)
        val frame = PoseFrame(10L, 480, 640, landmarks)
        assertTrue(frame.hasPerson)
        assertEquals(100f, frame[LandmarkId.LEFT_HIP]!!.x)
    }
}
