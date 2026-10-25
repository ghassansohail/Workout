package com.formcoach.app.coach

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Session flow (plan 3.1 and Phase 3) with a side-view squat stick figure. */
class WorkoutSessionFlowTest {
    private val noise = TestPoses.Noise(11, 0.003)

    private fun stand(t: Long) = TestPoses.squatSide(t, 178.0, noise)

    private fun ready(d: Driver = Driver()): Driver {
        d.command(SessionCommand.StartSet)
        d.run(2500) { t, _ -> stand(t) }
        assertEquals(1, d.eventsOf<CoachEvent.Ready>().size)
        assertEquals(SessionPhase.IN_SET, d.state.phase)
        return d
    }

    private fun squats(d: Driver, n: Int, bottom: Double = 90.0, bottoms: List<Double>? = null, restAfter: Long = 500) {
        val (keys, end) = TestPoses.repKeys(0, n, 178.0, bottom, bottoms = bottoms)
        d.run(end + restAfter) { t, rel -> TestPoses.squatSide(t, TestPoses.interp(keys, rel), noise) }
    }

    @Test
    fun idleUntilStart() {
        val d = Driver()
        d.run(3000) { t, _ -> stand(t) }
        assertEquals(SessionPhase.IDLE, d.state.phase)
        assertTrue(d.events.isEmpty())
        assertTrue(d.state.tracking)
        assertNotNull(d.state.overlay)
        assertEquals("Phone at hip height, 2-3 m away, side-on or facing you.", d.state.placementHint)
        // Movements before Start are never counted.
        squats(d, 3)
        assertTrue(d.events.isEmpty())
    }

    @Test
    fun startEmitsSetStartedAndPositioning() {
        val d = Driver()
        d.command(SessionCommand.StartSet)
        assertEquals(SessionPhase.POSITIONING, d.state.phase)
        assertEquals(CoachEvent.SetStarted(0, Exercise.SQUAT), d.events.single())
    }

    @Test
    fun readyOnlyAfterStillHold() {
        val d = Driver()
        d.command(SessionCommand.StartSet)
        d.run(1400) { t, _ -> stand(t) }
        assertTrue(d.eventsOf<CoachEvent.Ready>().isEmpty())
        assertEquals(SessionPhase.POSITIONING, d.state.phase)
        d.run(1100) { t, _ -> stand(t) }
        val ready = d.eventsOf<CoachEvent.Ready>().single()
        assertTrue(ready.atMs >= 1500)
        assertEquals(SessionPhase.IN_SET, d.state.phase)
        assertEquals("Ready. Go.", d.state.instruction)
        assertEquals(CameraView.SIDE, d.state.view)
        assertEquals(BodySide.LEFT, d.state.lockedSide)
        assertEquals("KNEE ANGLE", d.state.primaryLabel)
        assertTrue(d.state.primaryValue.endsWith("°"))
        assertNull(d.state.placementHint)
        // No setup issue was ever raised for a good start.
        assertTrue(d.eventsOf<CoachEvent.SetupIssueChanged>().isEmpty())
    }

    @Test
    fun noReadyWhileMoving() {
        val d = Driver()
        d.command(SessionCommand.StartSet)
        // Knee angle swings 160..178: start posture OK, but not still.
        d.run(5000) { t, _ -> TestPoses.squatSide(t, 169.0 + 9.0 * Math.sin(t / 150.0)) }
        assertTrue(d.eventsOf<CoachEvent.Ready>().isEmpty())
        assertEquals(SessionPhase.POSITIONING, d.state.phase)
        assertEquals(WorkoutSession.HOLD_STILL, d.state.instruction)
    }

    @Test
    fun setupIssuesInPriorityOrderAndOnlyOnChange() {
        val d = Driver()
        d.command(SessionCommand.StartSet)
        d.run(500) { _, _ -> null } // nobody in view
        d.run(1500) { t, _ -> angledFront(t) } // turned 45°
        d.run(1500) { t, _ -> TestPoses.squatSide(t, 120.0) } // side view but crouched
        d.run(2500) { t, _ -> stand(t) }
        val issues = d.eventsOf<CoachEvent.SetupIssueChanged>().map { it.issue?.id }
        assertEquals(listOf("visibility", "view", "start_posture", null), issues)
        assertEquals("Step back so I can see your whole body.", d.eventsOf<CoachEvent.SetupIssueChanged>()[0].issue!!.text)
        assertEquals("Turn fully sideways, or face the camera.", d.eventsOf<CoachEvent.SetupIssueChanged>()[1].issue!!.text)
        assertEquals("Stand tall.", d.eventsOf<CoachEvent.SetupIssueChanged>()[2].issue!!.text)
        assertEquals(1, d.eventsOf<CoachEvent.Ready>().size)
    }

    @Test
    fun frontViewStanceChecks() {
        val d = Driver()
        d.command(SessionCommand.StartSet)
        d.run(1500) { t, _ -> TestPoses.squatFront(t, 0.0, ankleHalf = 0.05) }
        d.run(1500) { t, _ -> TestPoses.squatFront(t, 0.0, ankleHalf = 0.22) }
        val ids = d.eventsOf<CoachEvent.SetupIssueChanged>().mapNotNull { it.issue }
        assertTrue(ids.any { it.id == "stance_narrow" && it.text == "Feet a little wider." })
        assertTrue(ids.any { it.id == "stance_wide" && it.text == "Feet a little closer." })
        assertTrue(d.eventsOf<CoachEvent.Ready>().isEmpty())
    }

    @Test
    fun setupChecksDoNotRunDuringTheSet() {
        val d = ready()
        squats(d, 2)
        assertTrue(d.eventsOf<CoachEvent.SetupIssueChanged>().isEmpty())
        assertEquals(2, d.reps().size)
    }

    @Test
    fun countsRepsInOrder() {
        val d = ready()
        squats(d, 5)
        assertEquals(listOf(1, 2, 3, 4, 5), d.reps().map { it.count })
        assertTrue(d.reps().all { it.faults.isEmpty() })
        assertEquals(5, d.state.reps)
        assertNull(d.state.leftReps)
    }

    @Test
    fun shortDropoutAtTheBottomKeepsTheRep() {
        val d = ready()
        squats(d, 2)
        val (keys, end) = TestPoses.repKeys(0, 1, 178.0, 90.0, holdMs = 1200)
        // 500 ms of nothing in the middle of the bottom hold.
        d.run(end + 500) { t, rel -> if (rel in 1300L..1800L) null else TestPoses.squatSide(t, TestPoses.interp(keys, rel), noise) }
        assertEquals(3, d.reps().size)
        assertTrue(d.eventsOf<CoachEvent.TrackingLost>().isEmpty())
        assertEquals(SessionPhase.IN_SET, d.state.phase)
    }

    @Test
    fun oneSecondDropoutMidRepKeepsTheRepSilently() {
        val d = ready()
        val (keys, end) = TestPoses.repKeys(0, 1, 178.0, 90.0, holdMs = 1500)
        d.run(end + 500) { t, rel -> if (rel in 1200L..2200L) null else TestPoses.squatSide(t, TestPoses.interp(keys, rel), noise) }
        assertEquals(1, d.reps().size)
        assertTrue(d.eventsOf<CoachEvent.TrackingLost>().isEmpty())
    }

    @Test
    fun longDropoutPausesAndKeepsTheCount() {
        val d = ready()
        squats(d, 3)
        d.run(4000) { _, _ -> null }
        assertEquals(SessionPhase.PAUSED, d.state.phase)
        assertEquals(1, d.eventsOf<CoachEvent.TrackingLost>().size)
        assertEquals(3, d.state.reps)
        d.run(500) { t, _ -> stand(t) }
        assertEquals(SessionPhase.IN_SET, d.state.phase)
        assertEquals(1, d.eventsOf<CoachEvent.TrackingRestored>().size)
        squats(d, 2)
        assertEquals(5, d.state.reps)
        assertEquals(listOf(1, 2, 3, 4, 5), d.reps().map { it.count })
        // Resume never repeats the setup.
        assertEquals(1, d.eventsOf<CoachEvent.Ready>().size)
    }

    @Test
    fun repInProgressIsAbandonedWhenPaused() {
        val d = ready()
        // Go down, then disappear for 3 s, then come back standing.
        d.run(1200) { t, rel -> TestPoses.squatSide(t, 178.0 - 88.0 * minOf(1.0, rel / 1000.0), noise) }
        d.run(3000) { _, _ -> null }
        d.run(1000) { t, _ -> stand(t) }
        assertEquals(0, d.reps().size)
        assertEquals(SessionPhase.IN_SET, d.state.phase)
    }

    @Test
    fun pausedWaitsForStartPosture() {
        val d = ready()
        squats(d, 1)
        d.run(2000) { _, _ -> null }
        assertEquals(SessionPhase.PAUSED, d.state.phase)
        d.run(1000) { t, _ -> TestPoses.squatSide(t, 120.0, noise) } // back in view but crouched
        assertEquals(SessionPhase.PAUSED, d.state.phase)
        d.run(300) { t, _ -> stand(t) }
        assertEquals(SessionPhase.IN_SET, d.state.phase)
    }

    @Test
    fun zeroRepSetNeverEndsOnItsOwn() {
        val d = ready()
        val jitter = TestPoses.Noise(5, 0.006)
        d.run(30_000) { t, _ -> TestPoses.squatSide(t, 178.0, jitter) }
        assertEquals(SessionPhase.IN_SET, d.state.phase)
        assertTrue(d.reps().isEmpty())
        assertTrue(d.eventsOf<CoachEvent.PartialRep>().isEmpty())
        assertTrue(d.eventsOf<CoachEvent.SetCompleted>().isEmpty())
        // Even a long absence does not end it.
        d.run(20_000) { _, _ -> null }
        assertTrue(d.eventsOf<CoachEvent.SetCompleted>().isEmpty())
    }

    @Test
    fun autoEndAfterRestingAtTheTop() {
        val d = ready()
        squats(d, 1)
        d.run(6000) { t, _ -> stand(t) }
        assertTrue(d.eventsOf<CoachEvent.SetCompleted>().isEmpty())
        d.run(3000) { t, _ -> stand(t) }
        val done = d.eventsOf<CoachEvent.SetCompleted>().single()
        assertEquals(SetSummary(1, 0, null), done.summary)
        assertEquals(SessionPhase.SET_COMPLETE, d.state.phase)
        assertEquals(done.summary, d.state.summary)
    }

    @Test
    fun stopGivesSummaryWithTopFault() {
        val d = ready()
        val (keys, end) = TestPoses.repKeys(0, 4, 178.0, 90.0)
        // Reps 1-2 clean, reps 3-4 with a 60° forward lean, then one shallow rep.
        d.run(end + 500) { t, rel ->
            val lean = if (rel > 5200) 60.0 else null
            TestPoses.squatSide(t, TestPoses.interp(keys, rel), noise, leanDeg = lean?.let { l ->
                val k = TestPoses.interp(keys, rel)
                0.0 + (l * ((178.0 - k) / 88.0)).coerceAtLeast(0.0)
            })
        }
        squats(d, 1, bottom = 125.0)
        d.command(SessionCommand.StopSet)
        val done = d.eventsOf<CoachEvent.SetCompleted>().single()
        assertEquals(4, done.summary.reps)
        assertEquals(1, done.summary.partialReps)
        assertEquals("keep your chest up", done.summary.topFaultAdvice)
        assertEquals(listOf(emptyList(), emptyList(), listOf("forward_lean"), listOf("forward_lean")),
            d.reps().map { r -> r.faults.map { it.id } })
        assertEquals(SessionPhase.SET_COMPLETE, d.state.phase)
    }

    @Test
    fun partialRepsCarryCueTexts() {
        val d = ready()
        squats(d, 3, bottoms = listOf(90.0, 125.0, 125.0))
        val partials = d.eventsOf<CoachEvent.PartialRep>()
        assertEquals(2, partials.size)
        assertEquals(listOf(1, 2), partials.map { it.partialCount })
        assertTrue(partials.all { it.kind == PartialKind.TOO_SHALLOW })
        assertEquals("Not counted. Go lower.", partials[0].firstCue)
        assertEquals("Lower.", partials[0].cue)
        assertEquals(1, d.state.reps)
        assertEquals(2, d.state.partialReps)
        assertEquals("Lower.", d.state.instruction)
    }

    @Test
    fun selectExerciseIgnoredDuringSet() {
        val d = ready()
        d.command(SessionCommand.SelectExercise(Exercise.CURL))
        assertEquals(Exercise.SQUAT, d.state.exercise)
        d.command(SessionCommand.StopSet)
        d.command(SessionCommand.SelectExercise(Exercise.CURL))
        assertEquals(Exercise.CURL, d.state.exercise)
        assertEquals(SessionPhase.IDLE, d.state.phase)
        assertEquals("WRIST LIFT", d.state.primaryLabel)
    }

    @Test
    fun stopDuringPositioningReturnsToIdle() {
        val d = Driver()
        d.command(SessionCommand.StartSet)
        d.run(300) { _, _ -> null }
        d.command(SessionCommand.StopSet)
        assertEquals(SessionPhase.IDLE, d.state.phase)
        assertTrue(d.eventsOf<CoachEvent.SetCompleted>().isEmpty())
        // The pending setup issue is cleared.
        assertNull(d.eventsOf<CoachEvent.SetupIssueChanged>().last().issue)
    }

    @Test
    fun newSetResetsCount() {
        val d = ready()
        squats(d, 2)
        d.command(SessionCommand.StopSet)
        d.command(SessionCommand.StartSet)
        d.run(2500) { t, _ -> stand(t) }
        assertEquals(2, d.eventsOf<CoachEvent.Ready>().size)
        assertEquals(0, d.state.reps)
        squats(d, 1)
        assertEquals(1, d.state.reps)
    }

    @Test
    fun rightSideNearIsLocked() {
        val d = Driver()
        d.command(SessionCommand.StartSet)
        d.run(2500) { t, _ -> TestPoses.squatSide(t, 178.0, noise, near = BodySide.RIGHT) }
        assertEquals(BodySide.RIGHT, d.state.lockedSide)
        val (keys, end) = TestPoses.repKeys(0, 3, 178.0, 90.0)
        d.run(end + 500) { t, rel -> TestPoses.squatSide(t, TestPoses.interp(keys, rel), noise, near = BodySide.RIGHT) }
        assertEquals(3, d.state.reps)
    }

    @Test
    fun leftRightSwapsDoNotChangeTheCount() {
        val d = ready()
        val (keys, end) = TestPoses.repKeys(0, 6, 178.0, 90.0)
        var i = 0
        d.run(end + 500) { t, rel ->
            i++
            val near = if (i % 20 == 7) BodySide.RIGHT else BodySide.LEFT // 5% swapped frames
            TestPoses.squatSide(t, TestPoses.interp(keys, rel), noise, near = near)
        }
        assertEquals(6, d.state.reps)
    }

    @Test
    fun noisyStandingAngleIsSteadyAfterFiltering() {
        val s = LandmarkSmoother(FilterConfig())
        val n = TestPoses.Noise(2, 0.008) // 1% of body height (the figure is ~0.8 image heights tall)
        val angles = ArrayList<Double>()
        for (k in 0 until 300) {
            val b = s.smooth(Body.fromFrame(TestPoses.squatSide(k * 33L, 170.0, n), 0.5f))
            angles.add(Geometry.angleDeg(b.j(Joint.HIP, BodySide.LEFT)!!, b.j(Joint.KNEE, BodySide.LEFT)!!, b.j(Joint.ANKLE, BodySide.LEFT)!!)!!)
        }
        val sd = Stats.sd(angles.drop(30))
        assertTrue("sd was $sd", sd < 2.0)
    }

    @Test
    fun trackingFlagFollowsLandmarks() {
        val d = ready()
        d.run(100) { t, _ -> TestPoses.squatSide(t, 178.0, hide = setOf(LandmarkId.LEFT_ANKLE)) }
        assertFalse(d.state.tracking)
        d.run(100) { t, _ -> stand(t) }
        assertTrue(d.state.tracking)
    }

    private fun angledFront(t: Long): PoseFrame {
        val pts = TestPoses.squatFrontPts(0.0)
        for ((id, p) in pts.toMap()) pts[id] = Pt(0.375 + (p.x - 0.375) * 0.5, p.y, p.z)
        return TestPoses.frame(t, TestPoses.PORTRAIT_W, TestPoses.PORTRAIT_H, pts)
    }
}
