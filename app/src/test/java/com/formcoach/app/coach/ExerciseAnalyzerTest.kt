package com.formcoach.app.coach

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Counting, faults and movement gates for each exercise through the full session. */
class ExerciseAnalyzerTest {
    private val noise = TestPoses.Noise(21, 0.003)

    private fun ready(exercise: Exercise, pose: (Long) -> PoseFrame): Driver {
        val d = Driver()
        d.command(SessionCommand.SelectExercise(exercise))
        d.command(SessionCommand.StartSet)
        d.run(2500) { t, _ -> pose(t) }
        assertEquals("Ready expected for $exercise", 1, d.eventsOf<CoachEvent.Ready>().size)
        assertEquals(SessionPhase.IN_SET, d.state.phase)
        return d
    }

    private fun faultIds(d: Driver) = d.reps().map { r -> r.faults.map { it.id } }

    // ---- squat --------------------------------------------------------------------------------

    @Test
    fun squatSideHeelsLift() {
        val d = ready(Exercise.SQUAT) { TestPoses.squatSide(it, 178.0, noise) }
        val (keys, end) = TestPoses.repKeys(0, 3, 178.0, 90.0)
        d.run(end + 500) { t, rel ->
            val k = TestPoses.interp(keys, rel)
            TestPoses.squatSide(t, k, noise, heelRise = 0.05 * (178.0 - k) / 88.0)
        }
        assertEquals(3, d.state.reps)
        assertTrue(faultIds(d).all { it == listOf("heels_lift") })
    }

    @Test
    fun squatSideFastDescent() {
        val d = ready(Exercise.SQUAT) { TestPoses.squatSide(it, 178.0, noise) }
        val (keys, end) = TestPoses.repKeys(0, 2, 178.0, 90.0, downMs = 300, upMs = 1000)
        d.run(end + 500) { t, rel -> TestPoses.squatSide(t, TestPoses.interp(keys, rel), noise) }
        assertEquals(2, d.state.reps)
        assertTrue(faultIds(d).all { "fast_descent" in it })
    }

    @Test
    fun squatFrontCountsAndDetectsKneesCaving() {
        val d = ready(Exercise.SQUAT) { TestPoses.squatFront(it, 0.0, noise) }
        assertEquals(CameraView.FRONT, d.state.view)
        assertNull(d.state.lockedSide)
        assertEquals("HIP DROP", d.state.primaryLabel)
        val (keys, end) = TestPoses.repKeys(0, 4, 0.0, 0.35)
        d.run(end + 500) { t, rel ->
            val cave = if (rel > 5200) 0.7 else 1.0
            TestPoses.squatFront(t, TestPoses.interp(keys, rel), noise, caveRatio = cave)
        }
        assertEquals(4, d.state.reps)
        assertEquals(listOf(emptyList(), emptyList(), listOf("knees_cave"), listOf("knees_cave")), faultIds(d))
        assertEquals("Knees out.", d.reps().last().faults.first().cue)
    }

    @Test
    fun squatFrontWeightShift() {
        val d = ready(Exercise.SQUAT) { TestPoses.squatFront(it, 0.0, noise) }
        val (keys, end) = TestPoses.repKeys(0, 2, 0.0, 0.35)
        d.run(end + 500) { t, rel -> TestPoses.squatFront(t, TestPoses.interp(keys, rel), noise, hipShift = 0.04) }
        assertEquals(2, d.state.reps)
        assertTrue(faultIds(d).all { "weight_shift" in it })
    }

    @Test
    fun squatBendingOverIsNotARep() {
        val d = ready(Exercise.SQUAT) { TestPoses.squatSide(it, 178.0, noise) }
        // Bend over to the phone: torso to 85° with knees bending to 120°, twice.
        val (knee, end) = TestPoses.repKeys(0, 2, 178.0, 120.0, downMs = 1200, upMs = 1200, holdMs = 800)
        d.run(end + 500) { t, rel ->
            val k = TestPoses.interp(knee, rel)
            val bend = 85.0 * (178.0 - k) / 58.0
            TestPoses.squatSide(t, k, noise, leanDeg = bend)
        }
        // Same with a deep knee bend (picking something up off the floor).
        val (deep, end2) = TestPoses.repKeys(0, 1, 178.0, 90.0, downMs = 1200, upMs = 1200, holdMs = 800)
        d.run(end2 + 500) { t, rel ->
            val k = TestPoses.interp(deep, rel)
            TestPoses.squatSide(t, k, noise, leanDeg = 80.0 * (178.0 - k) / 88.0)
        }
        assertEquals(0, d.state.reps)
        assertEquals(0, d.state.partialReps)
        assertTrue(d.events.none { it is CoachEvent.RepCounted || it is CoachEvent.PartialRep })
    }

    @Test
    fun squatWalkingIsNotARep() {
        val d = ready(Exercise.SQUAT) { TestPoses.squatSide(it, 178.0, noise) }
        // Knees bend and straighten while the feet travel across the frame.
        val (knee, end) = TestPoses.repKeys(0, 4, 178.0, 95.0, downMs = 400, upMs = 400, holdMs = 66, restMs = 200)
        d.run(end) { t, rel ->
            TestPoses.squatSide(t, TestPoses.interp(knee, rel), noise, ankleX = 0.25 + 0.1 * rel / 1000.0)
        }
        assertEquals(0, d.state.reps)
        assertEquals(0, d.state.partialReps)
    }

    // ---- push-up ------------------------------------------------------------------------------

    private fun pushUpReady() = ready(Exercise.PUSH_UP) { TestPoses.pushUp(it, 175.0, noise) }

    @Test
    fun pushUpCountsAndDetectsHipSag() {
        val d = pushUpReady()
        assertEquals("ELBOW ANGLE", d.state.primaryLabel)
        assertEquals(CameraView.SIDE, d.state.view)
        val (keys, end) = TestPoses.repKeys(0, 4, 175.0, 85.0)
        d.run(end + 500) { t, rel -> TestPoses.pushUp(t, TestPoses.interp(keys, rel), noise, sag = if (rel > 5200) 0.08 else 0.0) }
        assertEquals(4, d.state.reps)
        assertEquals(listOf(emptyList(), emptyList(), listOf("hips_sag"), listOf("hips_sag")), faultIds(d))
    }

    @Test
    fun pushUpHipPike() {
        val d = pushUpReady()
        val (keys, end) = TestPoses.repKeys(0, 2, 175.0, 85.0)
        d.run(end + 500) { t, rel -> TestPoses.pushUp(t, TestPoses.interp(keys, rel), noise, sag = -0.1) }
        assertEquals(2, d.state.reps)
        assertTrue(faultIds(d).all { it == listOf("hips_pike") })
    }

    @Test
    fun pushUpNoLockoutIsPartial() {
        val d = pushUpReady()
        val keys = listOf(
            0L to 175.0, 1000L to 85.0, 1100L to 85.0, 1700L to 138.0, // not locked out
            2400L to 85.0, 2500L to 85.0, 3500L to 175.0, 4000L to 175.0,
        )
        d.run(4000) { t, rel -> TestPoses.pushUp(t, TestPoses.interp(keys, rel), noise) }
        val p = d.eventsOf<CoachEvent.PartialRep>().single()
        assertEquals(PartialKind.NO_LOCKOUT, p.kind)
        assertEquals("Lock out.", p.firstCue)
        assertEquals("Lock out.", p.cue)
        assertEquals(1, d.state.reps)
        d.command(SessionCommand.StopSet)
        assertEquals("straighten your arms at the top", d.eventsOf<CoachEvent.SetCompleted>().single().summary.topFaultAdvice)
    }

    @Test
    fun pushUpEndsAfterLeavingThePlank() {
        val d = pushUpReady()
        val (keys, end) = TestPoses.repKeys(0, 3, 175.0, 85.0)
        d.run(end) { t, rel -> TestPoses.pushUp(t, TestPoses.interp(keys, rel), noise) }
        assertEquals(3, d.state.reps)
        d.run(4000) { t, _ -> TestPoses.standingLandscape(t) }
        val done = d.eventsOf<CoachEvent.SetCompleted>().single()
        assertEquals(3, done.summary.reps)
        assertEquals(3, d.reps().size)
        assertTrue(d.eventsOf<CoachEvent.PartialRep>().isEmpty())
    }

    @Test
    fun pushUpRequiresSideView() {
        val d = Driver()
        d.command(SessionCommand.SelectExercise(Exercise.PUSH_UP))
        d.command(SessionCommand.StartSet)
        d.run(2500) { t, _ -> TestPoses.squatFront(t, 0.0) }
        val issue = d.eventsOf<CoachEvent.SetupIssueChanged>().last().issue!!
        assertEquals("view", issue.id)
        assertEquals("Turn sideways to the camera.", issue.text)
    }

    @Test
    fun pushUpHandsForwardSetupCheck() {
        val d = Driver()
        d.command(SessionCommand.SelectExercise(Exercise.PUSH_UP))
        d.command(SessionCommand.StartSet)
        d.run(2500) { t, _ ->
            val pts = TestPoses.pushUpPts(175.0)
            // Hands 0.2 ahead (head direction) of the shoulders, arms straight.
            for (side in BodySide.values()) {
                val w = pts[Joint.WRIST.id(side)]!! + Pt(0.2, 0.0)
                val s = pts[Joint.SHOULDER.id(side)]!!
                pts[Joint.WRIST.id(side)] = w
                pts[Joint.ELBOW.id(side)] = Pt((w.x + s.x) / 2, (w.y + s.y) / 2, w.z)
            }
            TestPoses.frame(t, TestPoses.LANDSCAPE_W, TestPoses.LANDSCAPE_H, pts)
        }
        val issues = d.eventsOf<CoachEvent.SetupIssueChanged>().mapNotNull { it.issue?.id }
        assertTrue(issues.toString(), "hands_forward" in issues)
        assertTrue(d.eventsOf<CoachEvent.Ready>().isEmpty())
    }

    // ---- curls --------------------------------------------------------------------------------

    @Test
    fun curlSideCountsWithoutFaults() {
        val d = ready(Exercise.CURL) { TestPoses.curlSide(it, 0.0, noise) }
        val (keys, end) = TestPoses.repKeys(0, 4, 0.0, 150.0)
        d.run(end + 500) { t, rel -> TestPoses.curlSide(t, TestPoses.interp(keys, rel), noise) }
        assertEquals(4, d.state.reps)
        assertTrue(faultIds(d).all { it.isEmpty() })
        assertNull(d.state.leftReps)
    }

    @Test
    fun curlElbowDriftAndSwinging() {
        val d = ready(Exercise.CURL) { TestPoses.curlSide(it, 0.0, noise) }
        val (keys, end) = TestPoses.repKeys(0, 2, 0.0, 150.0)
        d.run(end + 500) { t, rel ->
            val fa = TestPoses.interp(keys, rel)
            TestPoses.curlSide(t, fa, noise, upperArmDeg = 32.0 * fa / 150.0)
        }
        val (keys2, end2) = TestPoses.repKeys(0, 2, 0.0, 150.0)
        d.run(end2 + 500) { t, rel ->
            val fa = TestPoses.interp(keys2, rel)
            TestPoses.curlSide(t, fa, noise, leanDeg = -20.0 * fa / 150.0)
        }
        assertEquals(4, d.state.reps)
        assertEquals(listOf(listOf("elbow_drift"), listOf("elbow_drift"), listOf("swinging"), listOf("swinging")), faultIds(d))
    }

    @Test
    fun curlFastLowering() {
        val d = ready(Exercise.CURL) { TestPoses.curlSide(it, 0.0, noise) }
        val (keys, end) = TestPoses.repKeys(0, 2, 0.0, 150.0, downMs = 1000, upMs = 250)
        d.run(end + 500) { t, rel -> TestPoses.curlSide(t, TestPoses.interp(keys, rel), noise) }
        assertEquals(2, d.state.reps)
        assertTrue(faultIds(d).all { it == listOf("fast_lowering") })
    }

    @Test
    fun curlShallowAndNoExtensionPartials() {
        val d = ready(Exercise.CURL) { TestPoses.curlSide(it, 0.0, noise) }
        val (keys, end) = TestPoses.repKeys(0, 1, 0.0, 100.0)
        d.run(end + 500) { t, rel -> TestPoses.curlSide(t, TestPoses.interp(keys, rel), noise) }
        val keys2 = listOf(0L to 0.0, 1000L to 150.0, 1100L to 150.0, 1600L to 60.0, 2200L to 150.0, 2300L to 150.0, 3300L to 0.0, 3800L to 0.0)
        d.run(3800) { t, rel -> TestPoses.curlSide(t, TestPoses.interp(keys2, rel), noise) }
        val partials = d.eventsOf<CoachEvent.PartialRep>()
        assertEquals(listOf(PartialKind.TOO_SHALLOW, PartialKind.NO_LOCKOUT), partials.map { it.kind })
        assertEquals("Not counted. Curl higher.", partials[0].firstCue)
        assertEquals("Higher.", partials[0].cue)
        assertEquals("Straighten fully.", partials[1].firstCue)
        assertEquals("Straighten fully.", partials[1].cue)
        assertEquals(1, d.state.reps)
    }

    @Test
    fun curlFrontBothArmsCountOnce() {
        val d = ready(Exercise.CURL) { TestPoses.curlFront(it, 0.0, 0.0, noise) }
        assertEquals(CameraView.FRONT, d.state.view)
        val (keys, end) = TestPoses.repKeys(0, 5, 0.0, 150.0)
        d.run(end + 500) { t, rel ->
            val a = TestPoses.interp(keys, rel)
            // Right arm slightly behind the left one (100 ms).
            TestPoses.curlFront(t, a, TestPoses.interp(keys, rel - 100), noise)
        }
        assertEquals(5, d.state.reps)
        assertEquals(5, d.state.leftReps)
        assertEquals(5, d.state.rightReps)
    }

    @Test
    fun curlFrontAlternatingCountsEachArm() {
        val d = ready(Exercise.CURL) { TestPoses.curlFront(it, 0.0, 0.0, noise) }
        val (keys, end) = TestPoses.repKeys(0, 3, 0.0, 150.0, restMs = 2600) // one arm every 5.2 s
        d.run(end + 500) { t, rel ->
            TestPoses.curlFront(t, TestPoses.interp(keys, rel), TestPoses.interp(keys, rel - 2600), noise)
        }
        assertEquals(6, d.state.reps)
        assertEquals(3, d.state.leftReps)
        assertEquals(3, d.state.rightReps)
    }

    @Test
    fun curlArmOverheadAndReachingAreNotReps() {
        val d = ready(Exercise.CURL) { TestPoses.curlFront(it, 0.0, 0.0, noise) }
        // Raise the left arm overhead to scratch the head, twice.
        val (ua, end) = TestPoses.repKeys(0, 2, 0.0, 170.0, downMs = 1000, upMs = 1000, holdMs = 1000)
        d.run(end + 500) { t, rel ->
            val u = TestPoses.interp(ua, rel)
            TestPoses.curlFront(t, u * 180.0 / 170.0, 0.0, noise, upperArmDeg = u)
        }
        assertEquals(0, d.state.reps)
        assertEquals(0, d.state.partialReps)
    }

    @Test
    fun curlSideReachForwardIsNotARep() {
        val d = ready(Exercise.CURL) { TestPoses.curlSide(it, 0.0, noise) }
        // Reach forward to adjust the phone: upper arm swings up to 90°, forearm follows.
        val (ua, end) = TestPoses.repKeys(0, 2, 0.0, 90.0, holdMs = 800)
        d.run(end + 500) { t, rel ->
            val u = TestPoses.interp(ua, rel)
            TestPoses.curlSide(t, u * 1.6, noise, upperArmDeg = u)
        }
        assertEquals(0, d.state.reps)
        assertEquals(0, d.state.partialReps)
    }

    @Test
    fun curlVisibilityTextMentionsHips() {
        val d = Driver()
        d.command(SessionCommand.SelectExercise(Exercise.CURL))
        d.command(SessionCommand.StartSet)
        d.run(200) { t, _ -> TestPoses.curlSide(t, 0.0).let { f ->
            val l = f.landmarks.copyOf()
            l[LandmarkId.LEFT_HIP] = null
            l[LandmarkId.RIGHT_HIP] = null
            PoseFrame(f.timestampMs, f.imageWidth, f.imageHeight, l)
        } }
        assertEquals("Step back so I can see you to your hips.", d.eventsOf<CoachEvent.SetupIssueChanged>().single().issue!!.text)
        assertEquals("Phone at chest height, 1.5-2 m away.", d.state.placementHint)
    }
}
