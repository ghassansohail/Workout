package com.formcoach.app.coach

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Locale

class PoseFrameCsvTest {

    private fun sampleFrame(): PoseFrame {
        val landmarks = arrayOfNulls<Landmark>(LandmarkId.COUNT)
        for (i in 0 until LandmarkId.COUNT) {
            if (i % 3 == 1) continue // every third landmark is missing, to exercise null handling
            landmarks[i] = Landmark(
                x = 10.5f + i,
                y = 200.25f - i * 0.5f,
                z = -0.125f * i,
                inFrame = 1f / (i + 1),
            )
        }
        return PoseFrame(1234L, 480, 640, landmarks)
    }

    private fun assertFramesEqual(expected: PoseFrame, actual: PoseFrame) {
        assertEquals(expected.timestampMs, actual.timestampMs)
        assertEquals(expected.imageWidth, actual.imageWidth)
        assertEquals(expected.imageHeight, actual.imageHeight)
        for (i in 0 until LandmarkId.COUNT) {
            val e = expected.landmarks[i]
            val a = actual.landmarks[i]
            if (e == null) {
                assertNull("landmark $i should be null", a)
            } else {
                assertEquals("x$i", e.x, a!!.x, 0f)
                assertEquals("y$i", e.y, a.y, 0f)
                assertEquals("z$i", e.z, a.z, 0f)
                assertEquals("p$i", e.inFrame, a.inFrame, 0f)
            }
        }
    }

    @Test
    fun headerHas135Columns() {
        val columns = PoseFrameCsv.header().split(",")
        assertEquals(3 + 33 * 4, columns.size)
        assertEquals(listOf("t_ms", "w", "h"), columns.take(3))
        assertEquals("x0", columns[3])
        assertEquals("p32", columns.last())
        assertTrue(columns.none { it.isEmpty() })
    }

    @Test
    fun encodeHasHeaderColumnCount() {
        assertEquals(135, PoseFrameCsv.encode(sampleFrame()).split(",").size)
    }

    @Test
    fun roundTripPreservesEveryValueIncludingNullLandmarks() {
        val frame = sampleFrame()
        val decoded = PoseFrameCsv.decode(PoseFrameCsv.encode(frame))
        assertFramesEqual(frame, decoded)
    }

    @Test
    fun roundTripOfAllNullFrame() {
        val frame = PoseFrame.empty(99L, 720, 1280)
        val line = PoseFrameCsv.encode(frame)
        assertEquals("99,720,1280" + ",,,,".repeat(33), line)
        val decoded = PoseFrameCsv.decode(line)
        assertFalse(decoded.hasPerson)
        assertFramesEqual(frame, decoded)
    }

    @Test
    fun nullLandmarkWritesFourEmptyFields() {
        val landmarks = arrayOfNulls<Landmark>(LandmarkId.COUNT)
        landmarks[LandmarkId.RIGHT_ANKLE] = Landmark(1f, 2f, 3f, 0.5f)
        val fields = PoseFrameCsv.encode(PoseFrame(0L, 1, 1, landmarks)).split(",")
        val base = 3 + LandmarkId.RIGHT_ANKLE * 4
        assertEquals(listOf("", "", "", ""), fields.subList(base - 4, base))
        assertEquals(listOf("1.0", "2.0", "3.0", "0.5"), fields.subList(base, base + 4))
    }

    @Test
    fun decodeRejectsWrongColumnCount() {
        val good = PoseFrameCsv.encode(sampleFrame())
        val truncated = good.substringBeforeLast(",")
        try {
            PoseFrameCsv.decode(truncated)
            fail("Expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!, e.message!!.contains("134"))
        }
        try {
            PoseFrameCsv.decode("$good,extra")
            fail("Expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!, e.message!!.contains("136"))
        }
    }

    @Test
    fun readAllSkipsHeaderAndBlankLines() {
        val a = sampleFrame()
        val b = PoseFrame.empty(2000L, 480, 640)
        val lines = sequenceOf(
            PoseFrameCsv.header(),
            PoseFrameCsv.encode(a),
            "",
            "   ",
            PoseFrameCsv.encode(b),
            "",
        )
        val frames = PoseFrameCsv.readAll(lines)
        assertEquals(2, frames.size)
        assertFramesEqual(a, frames[0])
        assertFramesEqual(b, frames[1])
    }

    @Test
    fun encodingIsIdenticalUnderGermanLocale() {
        val frame = sampleFrame()
        val usEncoded = PoseFrameCsv.encode(frame)
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            val deEncoded = PoseFrameCsv.encode(frame)
            assertEquals(usEncoded, deEncoded)
        } finally {
            Locale.setDefault(original)
        }
    }
}
