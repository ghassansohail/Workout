package com.formcoach.app.coach

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class RepCounterTest {
    private val squat = SquatConfig().sideCounter
    private val curl = CurlConfig().counter

    private fun counter(cfg: RepCounterConfig = squat, top: Double = 178.0) = RepCounter(cfg).also { it.calibrate(top) }

    /** Feeds a keyframed signal at 30 fps and returns all events. */
    private fun run(rc: RepCounter, keys: List<Pair<Long, Double>>, end: Long, noise: Double = 0.0, seed: Long = 7): List<RepCounterEvent> {
        val rnd = Random(seed)
        val out = ArrayList<RepCounterEvent>()
        var t = 0L
        while (t <= end) {
            val v = TestPoses.interp(keys, t) + rnd.nextGaussian() * noise
            rc.update(t, v)?.let { out.add(it) }
            t += 33
        }
        return out
    }

    @Test
    fun topZoneIsRelativeButNeverEasierThanFloor() {
        assertEquals(163.0, counter(top = 178.0).topThreshold, 1e-9)
        assertEquals(150.0, counter(top = 160.0).topThreshold, 1e-9)
        val ratio = RepCounter(curl).also { it.calibrate(-1.0) }
        assertEquals(-0.6, ratio.topThreshold, 1e-9)
    }

    @Test
    fun countsCleanReps() {
        val (keys, end) = TestPoses.repKeys(300, 5, 178.0, 90.0)
        val events = run(counter(), keys, end + 300, noise = 1.0)
        assertEquals(5, events.count { it is RepCounterEvent.Completed })
        assertEquals(0, events.count { it is RepCounterEvent.Partial })
    }

    @Test
    fun countsAsSoonAsTopIsConfirmedForTwoFrames() {
        val rc = counter()
        val values = listOf(178.0, 178.0, 150.0, 120.0, 95.0, 90.0, 90.0, 90.0, 92.0, 120.0, 150.0, 170.0, 175.0)
        val events = ArrayList<Pair<Int, RepCounterEvent>>()
        for ((i, v) in values.withIndex()) rc.update(i * 100L, v)?.let { events.add(i to it) }
        assertEquals(1, events.size)
        assertEquals(12, events[0].first) // second frame in the top zone (170 at 11, 175 at 12)
        val c = events[0].second as RepCounterEvent.Completed
        assertEquals(100L, c.startMs)
        assertEquals(1100L, c.endMs)
        assertEquals(90.0, c.extreme, 1e-9)
    }

    @Test
    fun shallowRepIsPartialAndNotCounted() {
        val (keys, end) = TestPoses.repKeys(300, 3, 178.0, 90.0, bottoms = listOf(90.0, 125.0, 90.0))
        val events = run(counter(), keys, end + 300)
        assertEquals(2, events.count { it is RepCounterEvent.Completed })
        val partials = events.filterIsInstance<RepCounterEvent.Partial>()
        assertEquals(1, partials.size)
        assertEquals(PartialKind.TOO_SHALLOW, partials[0].kind)
        assertEquals(125.0, partials[0].extreme, 1.0)
    }

    @Test
    fun movementBelowAttemptThresholdIsIgnored() {
        val (keys, end) = TestPoses.repKeys(300, 3, 178.0, 140.0)
        assertTrue(run(counter(), keys, end + 300).isEmpty())
    }

    @Test
    fun noLockoutIsPartialThenTheNextFullRepCounts() {
        val keys = listOf(
            0L to 178.0, 1000L to 90.0, 1200L to 90.0, 1800L to 140.0, // up, but not to the top zone
            2400L to 90.0, 2600L to 90.0, 3400L to 178.0, 4000L to 178.0,
        )
        val events = run(counter(), keys, 4000)
        assertEquals(2, events.size)
        val p = events[0] as RepCounterEvent.Partial
        assertEquals(PartialKind.NO_LOCKOUT, p.kind)
        assertTrue(events[1] is RepCounterEvent.Completed)
    }

    @Test
    fun jitterAtTopProducesNothing() {
        val rc = counter()
        val rnd = Random(3)
        var n = 0
        // 30 s of large jitter right at the top-zone boundary.
        for (i in 0 until 900) if (rc.update(i * 33L, 163.0 + rnd.nextGaussian() * 6.0) != null) n++
        assertEquals(0, n)
    }

    @Test
    fun tooFastAndTooSlowAreRejected() {
        val fast = TestPoses.repKeys(300, 2, 178.0, 90.0, downMs = 200, upMs = 200, holdMs = 66)
        assertTrue(run(counter(), fast.first, fast.second + 300).none { it is RepCounterEvent.Completed })
        val slow = TestPoses.repKeys(300, 1, 178.0, 90.0, downMs = 5000, upMs = 5000, holdMs = 1000)
        assertTrue(run(counter(), slow.first, slow.second + 300).isEmpty())
        val ok = TestPoses.repKeys(300, 1, 178.0, 90.0, downMs = 3000, upMs = 3000, holdMs = 1000)
        assertEquals(1, run(counter(), ok.first, ok.second + 300).size)
    }

    @Test
    fun fastButValidSquatsCount() {
        // 0.7 s per rep (plan Phase 8 row 17): the time outside the top zone is only ~0.58 s.
        val (keys, end) = TestPoses.repKeys(300, 5, 178.0, 85.0, downMs = 350, upMs = 350, holdMs = 0, restMs = 200)
        assertEquals(5, run(counter(), keys, end + 300).count { it is RepCounterEvent.Completed })
    }

    @Test
    fun bottomMustBeHeldTwoFramesAnd60ms() {
        val rc = counter()
        val values = listOf(178.0, 178.0, 140.0, 110.0, 99.0, 110.0, 140.0, 170.0, 175.0, 176.0)
        val events = values.mapIndexedNotNull { i, v -> rc.update(i * 100L, v) }
        // Only one frame at depth: a partial, not a rep.
        assertEquals(1, events.size)
        assertTrue(events[0] is RepCounterEvent.Partial)
    }

    @Test
    fun abandonDropsTheRepInProgress() {
        val rc = counter()
        var t = 0L
        fun feed(v: Double) = rc.update(t, v).also { t += 33 }
        repeat(5) { feed(178.0) }
        for (v in listOf(150.0, 120.0, 95.0, 90.0, 90.0, 90.0)) feed(v)
        rc.abandon() // tracking lost at the bottom
        assertEquals(RepPhase.WAIT_TOP, rc.phase)
        val events = listOf(120.0, 160.0, 175.0, 178.0, 178.0).mapNotNull { feed(it) }
        assertTrue(events.isEmpty())
        assertEquals(RepPhase.TOP, rc.phase)
        // The next full rep counts.
        val next = listOf(150.0, 120.0, 95.0, 90.0, 90.0, 90.0, 90.0, 120.0, 150.0, 170.0, 175.0, 178.0).let { vs ->
            // spread to a realistic duration
            vs.flatMap { v -> List(3) { v } }
        }.mapNotNull { feed(it) }
        assertEquals(1, next.count { it is RepCounterEvent.Completed })
    }

    @Test
    fun ratioSignalForCurls() {
        val rc = RepCounter(curl).also { it.calibrate(-1.0) }
        val (keys, end) = TestPoses.repKeys(300, 4, -1.0, 0.87)
        val events = run(rc, keys, end + 300, noise = 0.03)
        assertEquals(4, events.count { it is RepCounterEvent.Completed })
    }

    @Test
    fun phaseDurationsAreEstimated() {
        val (keys, end) = TestPoses.repKeys(300, 1, 178.0, 90.0, downMs = 1000, upMs = 400, holdMs = 200)
        val c = run(counter(), keys, end + 300).single() as RepCounterEvent.Completed
        assertEquals(1000.0, c.downMs.toDouble(), 120.0)
        assertEquals(400.0, c.upMs.toDouble(), 120.0)
        assertTrue(c.bottomStartMs < c.bottomEndMs)
    }
}
