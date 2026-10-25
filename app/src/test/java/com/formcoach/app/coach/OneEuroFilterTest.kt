package com.formcoach.app.coach

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs

class OneEuroFilterTest {
    private fun filter(beta: Double = 0.3) = OneEuroFilter(minCutoffHz = 1.5, beta = beta, dCutoffHz = 1.0)

    @Test
    fun firstValuePassesThrough() {
        assertEquals(0.42, filter().filter(0.42, 0L), 1e-12)
    }

    @Test
    fun constantInputStaysConstant() {
        val f = filter()
        var out = 0.0
        for (i in 0 until 100) out = f.filter(5.0, i * 33L)
        assertEquals(5.0, out, 1e-9)
    }

    @Test
    fun reducesJitterWhileStill() {
        val f = filter()
        val rnd = Random(1)
        val raw = ArrayList<Double>()
        val smooth = ArrayList<Double>()
        for (i in 0 until 300) {
            val v = 0.5 + rnd.nextGaussian() * 0.01
            raw.add(v)
            smooth.add(f.filter(v, i * 33L))
        }
        assertTrue(Stats.sd(smooth.drop(30)) < Stats.sd(raw) * 0.5)
    }

    @Test
    fun followsAStep() {
        val f = filter()
        var out = 0.0
        for (i in 0 until 10) out = f.filter(0.0, i * 33L)
        for (i in 10 until 40) out = f.filter(1.0, i * 33L) // 1 s after the step
        assertTrue("after 1 s the output should be close to the new value, was $out", out > 0.95)
    }

    @Test
    fun higherBetaLagsLessOnFastMotion() {
        val slow = filter(beta = 0.0)
        val fast = filter(beta = 5.0)
        var a = 0.0
        var b = 0.0
        for (i in 0 until 30) {
            val v = i * 0.03 // 0.9 units/s ramp
            a = slow.filter(v, i * 33L)
            b = fast.filter(v, i * 33L)
        }
        val target = 29 * 0.03
        assertTrue(abs(target - b) < abs(target - a))
    }

    @Test
    fun repeatedTimestampKeepsEstimate() {
        val f = filter()
        f.filter(1.0, 0L)
        val v = f.filter(1.0, 33L)
        assertEquals(v, f.filter(100.0, 33L), 1e-12)
    }

    @Test
    fun smootherRestartsLandmarkAfterLongGap() {
        val s = LandmarkSmoother(FilterConfig(minCutoffHz = 1.5, beta = 0.3, resetGapMs = 500))
        fun body(t: Long, x: Double?): Body {
            val pts = arrayOfNulls<Pt>(LandmarkId.COUNT)
            if (x != null) pts[LandmarkId.LEFT_HIP] = Pt(x, 0.5)
            return Body(t, 640, pts)
        }
        for (i in 0 until 30) s.smooth(body(i * 33L, 0.2))
        // Short gap: filter continues, so a jump is smoothed.
        val short = s.smooth(body(30 * 33L + 100, 0.6))[LandmarkId.LEFT_HIP]!!
        assertTrue(short.x < 0.55)
        // Missing frame gives null, then a long gap restarts the filter at the new value.
        assertNull(s.smooth(body(2000, null))[LandmarkId.LEFT_HIP])
        val restarted = s.smooth(body(3000, 0.1))[LandmarkId.LEFT_HIP]
        assertNotNull(restarted)
        assertEquals(0.1, restarted!!.x, 1e-12)
    }
}
