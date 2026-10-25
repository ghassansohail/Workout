package com.formcoach.app.coach

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Plan-level noise (σ = 1% of body height on every coordinate, the figures are ~0.8 image heights tall)
 * over 10-rep sets, several seeds each. Approximates Phase 8 rows 1, 10, 11, 12 and 13 with stick figures.
 */
class NoisyScenarioTest {
    private val sigma = 0.008
    private val seeds = listOf(1L, 2L, 3L, 4L, 5L)

    private fun set(exercise: Exercise, seed: Long, standing: (Long, TestPoses.Noise) -> PoseFrame,
                    reps: (Driver, TestPoses.Noise) -> Unit): Driver {
        val n = TestPoses.Noise(seed, sigma)
        val d = Driver()
        d.command(SessionCommand.SelectExercise(exercise))
        d.command(SessionCommand.StartSet)
        d.run(4000) { t, _ -> standing(t, n) }
        assertEquals("Ready ($exercise, seed $seed)", 1, d.eventsOf<CoachEvent.Ready>().size)
        reps(d, n)
        return d
    }

    private fun summary(d: Driver) =
        "reps=${d.state.reps} partials=${d.state.partialReps} faults=${d.reps().map { r -> r.faults.map { it.id } }}"

    @Test
    fun squatSideTenCleanReps() {
        for (seed in seeds) {
            val d = set(Exercise.SQUAT, seed, { t, n -> TestPoses.squatSide(t, 178.0, n) }) { d, n ->
                val (keys, end) = TestPoses.repKeys(0, 10, 178.0, 85.0, downMs = 900, upMs = 900, holdMs = 100, restMs = 100)
                d.run(end + 500) { t, rel -> TestPoses.squatSide(t, TestPoses.interp(keys, rel), n) }
            }
            assertEquals("seed $seed: ${summary(d)}", 10, d.state.reps)
            assertEquals("seed $seed: ${summary(d)}", 0, d.state.partialReps)
            assertEquals("seed $seed: ${summary(d)}", 0, d.reps().count { it.faults.isNotEmpty() })
        }
    }

    @Test
    fun fastSquatsAreCountedImmediately() {
        for (seed in seeds) {
            val d = set(Exercise.SQUAT, seed, { t, n -> TestPoses.squatSide(t, 178.0, n) }) { d, n ->
                val (keys, end) = TestPoses.repKeys(0, 10, 178.0, 80.0, downMs = 300, upMs = 300, holdMs = 50, restMs = 50) // 0.7 s per rep
                d.run(end + 500) { t, rel -> TestPoses.squatSide(t, TestPoses.interp(keys, rel), n) }
                // Each count arrives within 0.7 s of the true rep end (the next rep has not finished yet). It can
                // come slightly before the true top because counting starts on entering the top zone.
                val counts = d.reps()
                val readyAt = d.eventsOf<CoachEvent.Ready>().single().atMs
                val start = d.t - (end + 500)
                for ((i, c) in counts.withIndex()) {
                    val trueEnd = start + (i + 1) * 700L
                    assertEquals("seed $seed rep ${i + 1} at ${c.atMs - start} (ready $readyAt)", true, c.atMs - trueEnd in -200L..700L)
                }
            }
            assertEquals("seed $seed: ${summary(d)}", 10, d.state.reps)
        }
    }

    @Test
    fun squatFrontTenRepsKneesCaving() {
        for (seed in seeds) {
            val d = set(Exercise.SQUAT, seed, { t, n -> TestPoses.squatFront(t, 0.0, n) }) { d, n ->
                val (keys, end) = TestPoses.repKeys(0, 10, 0.0, 0.35, downMs = 900, upMs = 900, holdMs = 100, restMs = 100)
                d.run(end + 500) { t, rel -> TestPoses.squatFront(t, TestPoses.interp(keys, rel), n, caveRatio = 0.7) }
            }
            assertEquals("seed $seed: ${summary(d)}", 10, d.state.reps)
            assertEquals("seed $seed: ${summary(d)}", 10, d.reps().count { r -> r.faults.any { it.id == "knees_cave" } })
        }
    }

    @Test
    fun pushUpTenRepsSaggingThenStandUp() {
        for (seed in seeds) {
            val d = set(Exercise.PUSH_UP, seed, { t, n -> TestPoses.pushUp(t, 175.0, n) }) { d, n ->
                val (keys, end) = TestPoses.repKeys(0, 10, 175.0, 85.0, downMs = 800, upMs = 800, holdMs = 100, restMs = 100)
                d.run(end) { t, rel -> TestPoses.pushUp(t, TestPoses.interp(keys, rel), n, sag = 0.08) }
                d.run(4000) { t, _ -> TestPoses.standingLandscape(t) }
            }
            assertEquals("seed $seed: ${summary(d)}", 10, d.state.reps)
            assertEquals("seed $seed: ${summary(d)}", 10, d.reps().count { r -> r.faults.any { it.id == "hips_sag" } })
            assertEquals(SessionPhase.SET_COMPLETE, d.state.phase)
        }
    }

    @Test
    fun curlFrontBothArmsTenReps() {
        for (seed in seeds) {
            val d = set(Exercise.CURL, seed, { t, n -> TestPoses.curlFront(t, 0.0, 0.0, n) }) { d, n ->
                val (keys, end) = TestPoses.repKeys(0, 10, 0.0, 150.0, downMs = 800, upMs = 900, holdMs = 100, restMs = 200)
                d.run(end + 500) { t, rel -> TestPoses.curlFront(t, TestPoses.interp(keys, rel), TestPoses.interp(keys, rel - 60), n) }
            }
            assertEquals("seed $seed: ${summary(d)}", 10, d.state.reps)
            assertEquals("seed $seed: ${summary(d)}", 0, d.reps().count { it.faults.isNotEmpty() })
        }
    }

    @Test
    fun curlFrontAlternatingFivePerArm() {
        for (seed in seeds) {
            val d = set(Exercise.CURL, seed, { t, n -> TestPoses.curlFront(t, 0.0, 0.0, n) }) { d, n ->
                val (keys, end) = TestPoses.repKeys(0, 5, 0.0, 150.0, downMs = 800, upMs = 900, holdMs = 100, restMs = 1800)
                d.run(end + 2000) { t, rel -> TestPoses.curlFront(t, TestPoses.interp(keys, rel), TestPoses.interp(keys, rel - 1800), n) }
            }
            assertEquals("seed $seed: ${summary(d)}", 10, d.state.reps)
            assertEquals(5, d.state.leftReps)
            assertEquals(5, d.state.rightReps)
        }
    }

    @Test
    fun standingStillThirtySecondsIsSilent() {
        for (seed in seeds) {
            val d = set(Exercise.SQUAT, seed, { t, n -> TestPoses.squatSide(t, 178.0, n) }) { d, n ->
                d.run(30_000) { t, _ -> TestPoses.squatSide(t, 178.0, n) }
            }
            val after = d.events.dropWhile { it !is CoachEvent.Ready }.drop(1)
            assertEquals("seed $seed: $after", 0, after.size)
            assertEquals(SessionPhase.IN_SET, d.state.phase)
        }
    }
}
