package com.formcoach.app.camera

import com.formcoach.app.coach.LandmarkId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PoseFrameMapperTest {
    @Test fun mlKitIdsMatchLandmarkIds() {
        assertTrue(PoseFrameMapper.idsMatch())
        assertEquals(LandmarkId.COUNT, PoseFrameMapper.ML_KIT_TYPES.size)
        assertEquals(LandmarkId.LEFT_SHOULDER, PoseFrameMapper.ML_KIT_TYPES[LandmarkId.LEFT_SHOULDER])
        assertEquals(LandmarkId.RIGHT_FOOT_INDEX, PoseFrameMapper.ML_KIT_TYPES[LandmarkId.RIGHT_FOOT_INDEX])
    }
}
