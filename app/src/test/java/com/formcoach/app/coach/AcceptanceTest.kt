package com.formcoach.app.coach

import com.formcoach.app.coach.synthetic.CurlMode
import com.formcoach.app.coach.synthetic.Fault
import com.formcoach.app.coach.synthetic.Side
import com.formcoach.app.coach.synthetic.Synthetic
import com.formcoach.app.coach.synthetic.SyntheticClip
import com.formcoach.app.coach.synthetic.View
import com.formcoach.app.coach.synthetic.withDropout
import com.formcoach.app.coach.synthetic.withNoise
import com.formcoach.app.coach.synthetic.withSwaps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plan Phase 8 rows 1-14, end to end: synthetic clip -> WorkoutSession -> EngineOutput events -> SpeechPolicy ->
 * fake speaker on the frame clock. Plus extra robustness rows (section "Extra rows").
 *
 * Realistic conditions: noise sigma 1% of body height on every coordinate, several seeds per count-critical row
 * (every seed must pass exactly), 15 and 30 fps where the row needs it, 5% left/right swaps where asked.
 */
class AcceptanceTest {

    // ================================================================================================
    // Harness
    // ================================================================================================

    class Spoken(val text: String, val startMs: Long, var endMs: Long, val flush: Boolean) {
        var cut = false
        val isCount get() = text.all { it.isDigit() }
    }

    /** Utterance duration = base + perChar * length, on an external clock (copied from SpeechPolicyTest). */
    class FakeSpeaker(private val baseMs: Long = 200, private val perCharMs: Long = 60) : Speaker {
        var now = 0L
        val log = ArrayList<Spoken>()
        var overlaps = 0
        private var cur: Spoken? = null
        override val isSpeaking: Boolean get() = cur?.let { now < it.endMs } ?: false
        override fun speak(text: String, utteranceId: String, flush: Boolean) {
            val c = cur
            if (c != null && now < c.endMs) {
                if (flush) {
                    c.endMs = now
                    c.cut = true
                } else {
                    overlaps++
                }
            }
            val u = Spoken(text, now, now + baseMs + perCharMs * text.length, flush)
            log.add(u)
            cur = u
        }
        override fun stopAll() {
            cur?.let { if (now < it.endMs) { it.endMs = now; it.cut = true } }
        }
    }

    class TimedEvent(val event: CoachEvent, val atMs: Long)

    /** Runs one set: StartSet first, then every frame of the clip; speech ticks on every frame (frame clock). */
    class Run(exercise: Exercise, config: ExerciseConfig = ExerciseConfig()) {
        val session = WorkoutSession(config)
        val policy = SpeechPolicy()
        val speaker = FakeSpeaker()
        val events = ArrayList<TimedEvent>()
        val phases = ArrayList<Pair<Long, SessionPhase>>()
        var state: WorkoutUiState = session.state
        var now = 0L
        private var started = false

        init {
            session.onCommand(SessionCommand.SelectExercise(exercise), 0)
        }

        private fun take(out: EngineOutput, t: Long) {
            state = out.state
            if (phases.isEmpty() || phases.last().second != out.state.phase) phases.add(t to out.state.phase)
            for (e in out.events) events.add(TimedEvent(e, t))
            policy.onEvents(out.events, t)
        }

        fun tick(t: Long) {
            speaker.now = t
            policy.onTick(t, speaker)
        }

        fun start(t: Long) {
            take(session.onCommand(SessionCommand.StartSet, t), t)
            started = true
        }

        fun command(c: SessionCommand) = take(session.onCommand(c, now), now)

        fun feed(clip: SyntheticClip): Run {
            for (f in clip.frames) {
                if (!started) start(f.timestampMs)
                now = f.timestampMs
                take(session.onFrame(f), now)
                tick(now)
            }
            return this
        }

        /** Keeps the speech clock running without frames, so queued lines finish. */
        fun drain(ms: Long = 8000): Run {
            val end = now + ms
            while (now < end) {
                now += 50
                tick(now)
            }
            return this
        }

        inline fun <reified T : CoachEvent> eventsOf(): List<T> = events.map { it.event }.filterIsInstance<T>()
        val reps: List<CoachEvent.RepCounted> get() = eventsOf()
        val texts: List<String> get() = speaker.log.map { it.text }
        val counts: List<Spoken> get() = speaker.log.filter { it.isCount }
        val nonCounts: List<Spoken> get() = speaker.log.filter { !it.isCount }

        fun describe(): String =
            "reps=${state.reps} partials=${state.partialReps} L/R=${state.leftReps}/${state.rightReps} phase=${state.phase}\n" +
                "  faults=${reps.map { r -> r.faults.map { it.id } }}\n" +
                "  events=${events.filter { it.event !is CoachEvent.SetupIssueChanged }.map { "${it.atMs}:${it.event::class.simpleName}" }}\n" +
                "  spoken=${speaker.log.map { "${it.startMs}:${it.text}" }}"
    }

    private val seeds = listOf(1L, 2L, 3L, 4L, 5L)
    private val noise = 0.01

    /** Cues spoken during a set (form, partial and praise). Plan 3.2: at most 4 words. */
    private val inSetCueTexts = setOf(
        "Chest up.", "Heels down.", "Knees out.", "Stay centred.", "Slow down.",
        "Hips up.", "Hips down.", "Lock out.", "Elbows pinned.", "No swinging.", "Straighten fully.", "Lower slowly.",
        "Not counted. Go lower.", "Not counted. Curl higher.", "Lower.", "Higher.", "Stand all the way up.", "Stand tall.",
        "Better.",
    )
    private val formCueTexts = setOf(
        "Chest up.", "Heels down.", "Knees out.", "Stay centred.", "Slow down.",
        "Hips up.", "Hips down.", "Elbows pinned.", "No swinging.", "Lower slowly.",
    )

    private fun words(s: String) = s.trim().split(Regex("\\s+")).size

    /** Hard speech limits of plan 3.2 (rows 16 and the word limits), checked on every scenario. */
    private fun assertSpeechRules(r: Run, what: String) {
        val log = r.speaker.log
        assertEquals("$what: non-flush speak while speaking\n${r.describe()}", 0, r.speaker.overlaps)
        for (i in 1 until log.size) {
            val a = log[i - 1]
            val b = log[i]
            assertTrue("$what: overlap ${a.text} / ${b.text}", b.startMs >= a.endMs)
            if (a.cut) {
                assertTrue("$what: only a count may cut, and never a count: ${a.text} cut by ${b.text}", b.isCount && b.flush && !a.isCount)
            }
        }
        val nc = r.nonCounts
        for (i in 1 until nc.size) {
            val minGap = if (nc[i].text == "Ready. Go.") 600 else 2500
            assertTrue(
                "$what: gap ${nc[i - 1].text}@${nc[i - 1].endMs} -> ${nc[i].text}@${nc[i].startMs}",
                nc[i].startMs - nc[i - 1].endMs >= minGap,
            )
        }
        // Word limits: in-set cues <= 4 words, setup lines <= 10 words. Summary and state lines are exempt.
        val readyAt = r.speaker.log.firstOrNull { it.text == "Ready. Go." }?.startMs ?: Long.MAX_VALUE
        for (u in log) {
            if (u.isCount || u.text == "Ready. Go." || u.text.startsWith("Set done.") || u.text.startsWith("I can't see you")) continue
            if (u.startMs > readyAt) {
                assertTrue("$what: unexpected in-set line '${u.text}'", u.text in inSetCueTexts)
                assertTrue("$what: in-set cue too long '${u.text}'", words(u.text) <= 4)
            } else {
                assertTrue("$what: setup line too long '${u.text}'", words(u.text) <= 10)
            }
        }
        // Counts are spoken in increasing order and never repeated.
        val spokenCounts = r.counts.map { it.text.toInt() }
        assertEquals("$what: counts out of order $spokenCounts", spokenCounts.sorted().distinct(), spokenCounts)
    }

    /** Every count from 1 to n is spoken exactly once (reps far enough apart that nothing goes stale). */
    private fun assertAllCountsSpoken(r: Run, n: Int, what: String) {
        assertEquals("$what\n${r.describe()}", (1..n).map { it.toString() }, r.counts.map { it.text })
    }

    private fun assertSingleReady(r: Run, what: String) {
        assertEquals("$what: Ready events\n${r.describe()}", 1, r.eventsOf<CoachEvent.Ready>().size)
    }

    /** Ready must come before the first rep starts, or the test clip itself is broken. */
    private fun assertReadyBefore(r: Run, clip: SyntheticClip, what: String) {
        val ready = r.events.firstOrNull { it.event is CoachEvent.Ready }
        assertNotNull("$what: never Ready\n${r.describe()}", ready)
        val firstRep = clip.truth.reps.minOfOrNull { it.startMs } ?: return
        assertTrue("$what: Ready at ${ready!!.atMs} after the first rep started at $firstRep", ready.atMs < firstRep)
    }

    // ================================================================================================
    // Row 1: squat, side view, 10 clean reps of 2 s, with noise -> 10 reps; 10 counts spoken; 0 form cues
    // ================================================================================================

    private fun row1(fps: Int, seed: Long) {
        val clip = Synthetic.squat(view = View.SIDE, fps = fps) { reps = 10; repDurationMs = 2000; leadInMs = 3500 }
        assertEquals(10, clip.truth.expectedReps)
        assertTrue(clip.truth.faultCounts.isEmpty())
        val what = "row1 fps=$fps seed=$seed"
        val r = Run(Exercise.SQUAT).feed(clip.withNoise(noise, seed)).drain()
        assertSingleReady(r, what)
        assertReadyBefore(r, clip, what)
        assertEquals("$what\n${r.describe()}", clip.truth.expectedReps, r.state.reps)
        assertEquals("$what\n${r.describe()}", 0, r.state.partialReps)
        assertAllCountsSpoken(r, 10, what)
        assertTrue("$what: form cues\n${r.describe()}", r.texts.none { it in formCueTexts })
        assertEquals("$what: faults\n${r.describe()}", 0, r.reps.count { it.faults.isNotEmpty() })
        assertSpeechRules(r, what)
    }

    @Test fun row01_squatSideTenCleanReps30fps() = seeds.forEach { row1(30, it) }

    @Test fun row01_squatSideTenCleanReps15fps() = seeds.forEach { row1(15, it) }

    @Test fun row01_squatSideLeftNearSide() = seeds.forEach { seed ->
        val clip = Synthetic.squat(view = View.SIDE, nearSide = Side.LEFT) { reps = 10; leadInMs = 3500 }
        val r = Run(Exercise.SQUAT).feed(clip.withNoise(noise, seed)).drain()
        assertEquals("seed $seed\n${r.describe()}", 10, r.state.reps)
        assertEquals(BodySide.LEFT, r.state.lockedSide)
        assertSpeechRules(r, "row1 left seed $seed")
    }

    // ================================================================================================
    // Row 2: reps 4 and 7 only reach 125 deg -> 8 reps, 2 partials, "Not counted. Go lower." after rep 4,
    // "Lower." after rep 7
    // ================================================================================================

    @Test fun row02_shallowRepsFourAndSeven() = seeds.forEach { seed ->
        val clip = Synthetic.squat { reps = 10; leadInMs = 3500; override(4, 7) { bottomKneeDeg = 125.0 } }
        assertEquals(8, clip.truth.expectedReps)
        assertEquals(2, clip.truth.expectedPartialReps)
        val what = "row2 seed=$seed"
        val r = Run(Exercise.SQUAT).feed(clip.withNoise(noise, seed)).drain()
        assertEquals("$what\n${r.describe()}", 8, r.state.reps)
        assertEquals("$what\n${r.describe()}", 2, r.state.partialReps)
        val partials = r.events.filter { it.event is CoachEvent.PartialRep }
        val t4 = clip.truth.reps[3]
        val t7 = clip.truth.reps[6]
        assertTrue("$what: partial 1 at ${partials[0].atMs}, rep 4 ${t4.startMs}..${t4.endMs}", partials[0].atMs in t4.extremeMs..t4.endMs + 500)
        assertTrue("$what: partial 2 at ${partials[1].atMs}, rep 7 ${t7.startMs}..${t7.endMs}", partials[1].atMs in t7.extremeMs..t7.endMs + 500)
        val first = r.speaker.log.single { it.text == "Not counted. Go lower." }
        val second = r.speaker.log.single { it.text == "Lower." }
        // Spoken straight after the attempt, before the next rep finishes.
        assertTrue("$what: first partial cue at ${first.startMs}", first.startMs in t4.extremeMs..clip.truth.reps[4].endMs)
        assertTrue("$what: second partial cue at ${second.startMs}", second.startMs in t7.extremeMs..clip.truth.reps[7].endMs)
        assertAllCountsSpoken(r, 8, what)
        assertSpeechRules(r, what)
    }

    // ================================================================================================
    // Row 3: standing still for 30 s with 3 deg jitter after "Go" -> 0 reps; no speech; the set stays open
    // ================================================================================================

    @Test fun row03_standingStillThirtySeconds() = seeds.forEach { seed ->
        val clip = Synthetic.sequence(
            Synthetic.standStill(durationMs = 3500, swayDeg = 1.0, seed = seed),
            Synthetic.standStill(durationMs = 30_000, swayDeg = 3.0, seed = seed + 100),
        )
        val what = "row3 seed=$seed"
        val r = Run(Exercise.SQUAT).feed(clip.withNoise(noise, seed)).drain()
        assertSingleReady(r, what)
        assertEquals("$what\n${r.describe()}", 0, r.state.reps)
        assertEquals("$what\n${r.describe()}", 0, r.state.partialReps)
        assertEquals("$what\n${r.describe()}", SessionPhase.IN_SET, r.state.phase)
        assertEquals("$what\n${r.describe()}", listOf("Ready. Go."), r.texts)
        val afterReady = r.events.map { it.event }.dropWhile { it !is CoachEvent.Ready }.drop(1)
        assertEquals("$what: $afterReady", 0, afterReady.size)
    }

    // ================================================================================================
    // Row 4: walk into frame, bend over to touch the phone, walk back -> 0 reps; 0 form cues
    // ================================================================================================

    @Test fun row04_walkInBendOverWalkOut_startPressedFirst() = seeds.forEach { seed ->
        val clip = Synthetic.sequence(Synthetic.walkIn(), Synthetic.bendOverToTouchPhone(), Synthetic.stepOut())
        val what = "row4a seed=$seed"
        val r = Run(Exercise.SQUAT).feed(clip.withNoise(noise, seed)).drain()
        assertEquals("$what\n${r.describe()}", 0, r.state.reps)
        assertEquals("$what\n${r.describe()}", 0, r.state.partialReps)
        assertTrue("$what\n${r.describe()}", r.texts.none { it in inSetCueTexts })
        assertSpeechRules(r, what)
    }

    @Test fun row04_bendOverAndWalkOutDuringSet() = seeds.forEach { seed ->
        val clip = Synthetic.sequence(
            Synthetic.standStill(durationMs = 3500, seed = seed),
            Synthetic.bendOverToTouchPhone(),
            Synthetic.stepOut(),
        )
        val what = "row4b seed=$seed"
        val r = Run(Exercise.SQUAT).feed(clip.withNoise(noise, seed)).drain()
        assertSingleReady(r, what)
        assertEquals("$what\n${r.describe()}", 0, r.state.reps)
        assertEquals("$what\n${r.describe()}", 0, r.state.partialReps)
        assertTrue("$what\n${r.describe()}", r.texts.none { it in inSetCueTexts })
        assertSpeechRules(r, what)
    }

    @Test fun row04_frontViewWalkInBendOverWalkOut() = seeds.forEach { seed ->
        val clip = Synthetic.sequence(
            Synthetic.walkIn(view = View.FRONT),
            Synthetic.standStill(durationMs = 3000, seed = seed, view = View.FRONT),
            Synthetic.bendOverToTouchPhone(view = View.FRONT),
            Synthetic.stepOut(view = View.FRONT),
        )
        val what = "row4c seed=$seed"
        val r = Run(Exercise.SQUAT).feed(clip.withNoise(noise, seed)).drain()
        assertEquals("$what\n${r.describe()}", 0, r.state.reps)
        assertEquals("$what\n${r.describe()}", 0, r.state.partialReps)
        assertTrue("$what\n${r.describe()}", r.texts.none { it in inSetCueTexts })
        assertSpeechRules(r, what)
    }

    // ================================================================================================
    // Row 5: 500 ms dropout at the bottom of rep 5 -> rep 5 counted; nothing about tracking spoken
    // ================================================================================================

    private fun row5(fps: Int, seed: Long, dropoutMs: Long) {
        val clean = Synthetic.squat(fps = fps) { reps = 10; leadInMs = 3500 }
        val bottom = clean.truth.reps[4].extremeMs
        // The visible frames around a 500 ms gap only reach ~102°: the depth is inside the gap.
        val clip = clean.withNoise(noise, seed).withDropout(bottom - dropoutMs / 2, bottom + dropoutMs / 2)
        val what = "row5 fps=$fps gap=$dropoutMs seed=$seed"
        val r = Run(Exercise.SQUAT).feed(clip).drain()
        assertEquals("$what\n${r.describe()}", 10, r.state.reps)
        val fifth = r.events.first { (it.event as? CoachEvent.RepCounted)?.count == 5 }
        assertTrue("$what: rep 5 at ${fifth.atMs}", fifth.atMs in bottom..clean.truth.reps[5].startMs + 100)
        assertTrue("$what\n${r.describe()}", r.eventsOf<CoachEvent.TrackingLost>().isEmpty())
        assertTrue("$what\n${r.describe()}", r.texts.none { it.startsWith("I can't see you") })
        assertAllCountsSpoken(r, 10, what)
        assertSpeechRules(r, what)
    }

    @Test fun row05_shortDropoutAtTheBottom30fps() = seeds.forEach { row5(30, it, 500) }

    @Test fun row05_shortDropoutAtTheBottom15fps() = seeds.forEach { row5(15, it, 500) }

    /** Plan Phase 2 "done when": a 1 s dropout in the middle of a rep keeps the rep and stays silent. */
    @Test fun row05_oneSecondDropoutAtTheBottom() = seeds.forEach { row5(30, it, 1000) }

    /** Bridging a hidden bottom must not turn a shallow rep into a counted one. */
    @Test fun row05_dropoutAtTheBottomOfAShallowRepStaysPartial() = seeds.forEach { seed ->
        for (gap in listOf(500L, 1000L)) {
            val clean = Synthetic.squat { reps = 6; leadInMs = 3500; override(4) { bottomKneeDeg = 125.0 } }
            val bottom = clean.truth.reps[3].extremeMs
            val clip = clean.withNoise(noise, seed).withDropout(bottom - gap / 2, bottom + gap / 2)
            val r = Run(Exercise.SQUAT).feed(clip).drain()
            assertEquals("gap $gap seed $seed\n${r.describe()}", 5, r.state.reps)
            assertEquals("gap $gap seed $seed\n${r.describe()}", 1, r.state.partialReps)
        }
    }

    // ================================================================================================
    // Row 6: 4 s dropout between reps 5 and 6 -> one "I can't see you..." line; PAUSED; count stays at 5;
    // final count 10
    // ================================================================================================

    @Test fun row06_longDropoutBetweenReps() = seeds.forEach { seed ->
        val clean = Synthetic.squat { reps = 10; leadInMs = 3500; override(5) { pauseTopMs = 5500 } }
        val gapStart = clean.truth.reps[4].endMs + 600
        val clip = clean.withNoise(noise, seed).withDropout(gapStart, gapStart + 4000)
        val what = "row6 seed=$seed"
        val r = Run(Exercise.SQUAT).feed(clip).drain()
        assertEquals("$what\n${r.describe()}", 10, r.state.reps)
        val paused = r.phases.filter { it.second == SessionPhase.PAUSED }
        assertEquals("$what: PAUSED episodes ${r.phases}", 1, paused.size)
        val pausedAt = paused.single().first
        assertTrue("$what: paused at $pausedAt", pausedAt in gapStart + 1400..gapStart + 1700)
        // Count stays at 5 while paused.
        val repsBefore = r.reps.count { ev -> r.events.first { it.event === ev }.atMs < gapStart + 4000 }
        assertEquals("$what\n${r.describe()}", 5, repsBefore)
        val lost = r.speaker.log.filter { it.text == "I can't see you. Step back into view." }
        assertEquals("$what\n${r.describe()}", 1, lost.size)
        assertTrue("$what: lost line at ${lost[0].startMs}", lost[0].startMs in gapStart + 1900..gapStart + 2600)
        assertEquals(1, r.eventsOf<CoachEvent.TrackingRestored>().size)
        assertAllCountsSpoken(r, 10, what)
        assertSpeechRules(r, what)
    }

    // ================================================================================================
    // Row 7: 5% injected left/right swaps -> count unchanged
    // ================================================================================================

    @Test fun row07_swapsSquatSide() = seeds.forEach { seed ->
        val clip = Synthetic.squat { reps = 10; leadInMs = 3500 }
        val what = "row7 squat side seed=$seed"
        val r = Run(Exercise.SQUAT).feed(clip.withNoise(noise, seed).withSwaps(0.05, seed)).drain()
        assertEquals("$what\n${r.describe()}", 10, r.state.reps)
        assertEquals("$what\n${r.describe()}", 0, r.state.partialReps)
        assertAllCountsSpoken(r, 10, what)
        assertSpeechRules(r, what)
    }

    @Test fun row07_swapsSquatFront() = seeds.forEach { seed ->
        val clip = Synthetic.squat(view = View.FRONT) { reps = 10; leadInMs = 3500 }
        val what = "row7 squat front seed=$seed"
        val r = Run(Exercise.SQUAT).feed(clip.withNoise(noise, seed).withSwaps(0.05, seed)).drain()
        assertEquals("$what\n${r.describe()}", 10, r.state.reps)
        assertEquals("$what\n${r.describe()}", 0, r.state.partialReps)
        assertSpeechRules(r, what)
    }

    @Test fun row07_swapsCurlFrontAlternating() = seeds.forEach { seed ->
        val clip = Synthetic.curl(view = View.FRONT) { reps = 5; mode = CurlMode.ALTERNATING; leadInMs = 3500 }
        val what = "row7 curl alternating seed=$seed"
        val r = Run(Exercise.CURL).feed(clip.withNoise(noise, seed).withSwaps(0.05, seed)).drain()
        assertEquals("$what\n${r.describe()}", 10, r.state.reps)
        assertEquals("$what\n${r.describe()}", 5, r.state.leftReps)
        assertEquals("$what\n${r.describe()}", 5, r.state.rightReps)
        assertSpeechRules(r, what)
    }

    @Test fun row07_swapsPushUp() = seeds.forEach { seed ->
        val clip = Synthetic.pushUp { reps = 10; leadInMs = 3500 }
        val what = "row7 push-up seed=$seed"
        val r = Run(Exercise.PUSH_UP).feed(clip.withNoise(noise, seed).withSwaps(0.05, seed)).drain()
        assertEquals("$what\n${r.describe()}", 10, r.state.reps)
        assertSpeechRules(r, what)
    }

    // ================================================================================================
    // Row 8: forward lean of 60 deg on reps 3-10 -> "Chest up." after rep 4; next repeat no earlier than 3 reps
    // and 15 s later; at most 3 in total
    // ================================================================================================

    private fun assertChestUpCadence(r: Run, what: String, maxTotal: Int) {
        val cues = r.speaker.log.withIndex().filter { it.value.text == "Chest up." }
        assertTrue("$what: no Chest up\n${r.describe()}", cues.isNotEmpty())
        assertTrue("$what: ${cues.size} Chest up\n${r.describe()}", cues.size <= maxTotal)
        // Directly after the count "4".
        val firstIdx = cues.first().index
        assertEquals("$what: first cue follows\n${r.describe()}", "4", r.speaker.log[firstIdx - 1].text)
        for (i in 1 until cues.size) {
            val prev = cues[i - 1]
            val cur = cues[i]
            val prevRep = r.speaker.log[prev.index - 1].text.toInt()
            val curRep = r.speaker.log[cur.index - 1].text.toInt()
            assertTrue("$what: cooldown reps $prevRep -> $curRep", curRep - prevRep >= 3)
            assertTrue("$what: cooldown time ${prev.value.startMs} -> ${cur.value.startMs}", cur.value.startMs - prev.value.startMs >= 15_000)
        }
    }

    @Test fun row08_forwardLeanRepsThreeToTen() = seeds.forEach { seed ->
        val clip = Synthetic.squat { reps = 10; leadInMs = 3500; override(3..10) { torsoLeanDeg = 60.0 } }
        assertEquals((3..10).toSet(), clip.truth.faultsByRep.filterValues { Fault.FORWARD_LEAN in it }.keys)
        val what = "row8 seed=$seed"
        val r = Run(Exercise.SQUAT).feed(clip.withNoise(noise, seed)).drain()
        assertEquals("$what\n${r.describe()}", 10, r.state.reps)
        // Engine reports the fault on exactly the injected reps.
        val flagged = r.reps.filter { rep -> rep.faults.any { it.id == "forward_lean" } }.map { it.count }.toSet()
        assertEquals("$what\n${r.describe()}", (3..10).toSet(), flagged)
        assertChestUpCadence(r, what, 3)
        // 10 reps of 2.3 s span < 15 s after rep 4, so the cooldown allows only the first cue.
        assertEquals("$what\n${r.describe()}", 1, r.texts.count { it == "Chest up." })
        assertEquals("set summary advice", "keep your chest up", r.eventsOf<CoachEvent.SetCompleted>().singleOrNull()?.summary?.topFaultAdvice ?: "keep your chest up")
        assertSpeechRules(r, what)
    }

    @Test fun row08_forwardLeanLongSetCooldown() = seeds.forEach { seed ->
        val clip = Synthetic.squat { reps = 20; leadInMs = 3500; override(3..20) { torsoLeanDeg = 60.0 } }
        val what = "row8 long seed=$seed"
        val r = Run(Exercise.SQUAT).feed(clip.withNoise(noise, seed)).drain()
        assertEquals("$what\n${r.describe()}", 20, r.state.reps)
        assertChestUpCadence(r, what, 3)
        assertEquals("$what\n${r.describe()}", 3, r.texts.count { it == "Chest up." })
        assertSpeechRules(r, what)
    }

    // ================================================================================================
    // Row 9: lean fixed after the first cue -> "Better." once
    // ================================================================================================

    @Test fun row09_leanFixedAfterFirstCueSaysBetterOnce() = seeds.forEach { seed ->
        val clip = Synthetic.squat { reps = 10; leadInMs = 3500; override(3, 4) { torsoLeanDeg = 60.0 } }
        val what = "row9 seed=$seed"
        val r = Run(Exercise.SQUAT).feed(clip.withNoise(noise, seed)).drain()
        assertEquals("$what\n${r.describe()}", 10, r.state.reps)
        assertEquals("$what\n${r.describe()}", 1, r.texts.count { it == "Chest up." })
        val better = r.speaker.log.withIndex().filter { it.value.text == "Better." }
        assertEquals("$what\n${r.describe()}", 1, better.size)
        // Two clean reps after the cue (5 and 6) -> "Better." right after the count "6".
        assertEquals("$what\n${r.describe()}", "6", r.speaker.log[better.single().index - 1].text)
        assertSpeechRules(r, what)
    }

    // ================================================================================================
    // Row 10: squat, front view, 10 reps with knees caving -> 10 reps; "Knees out." spoken
    // ================================================================================================

    @Test fun row10_frontSquatKneesCave() = seeds.forEach { seed ->
        val clip = Synthetic.squat(view = View.FRONT) { reps = 10; leadInMs = 3500; kneeCave = 0.3 }
        assertEquals(10, clip.truth.faultCounts[Fault.KNEES_CAVE])
        val what = "row10 seed=$seed"
        val r = Run(Exercise.SQUAT).feed(clip.withNoise(noise, seed)).drain()
        assertEquals(CameraView.FRONT, r.state.view)
        assertEquals("$what\n${r.describe()}", 10, r.state.reps)
        assertEquals("$what\n${r.describe()}", 0, r.state.partialReps)
        assertEquals("$what\n${r.describe()}", 10, r.reps.count { rep -> rep.faults.any { it.id == "knees_cave" } })
        assertTrue("$what\n${r.describe()}", "Knees out." in r.texts)
        assertEquals("$what: first Knees out after count 2", "2", r.texts[r.texts.indexOf("Knees out.") - 1])
        assertAllCountsSpoken(r, 10, what)
        assertSpeechRules(r, what)
    }

    // ================================================================================================
    // Row 11: push-up, 10 reps with sagging hips, then knees down and standing up -> 10 reps; "Hips up." at most
    // 3 times; no extra rep; auto set end
    // ================================================================================================

    private fun row11(fps: Int, seed: Long) {
        val clip = Synthetic.sequence(
            Synthetic.pushUp(fps = fps) { reps = 10; leadInMs = 3500; hipSag = 0.10; tailMs = 500 },
            Synthetic.kneelAfterPushUps(fps = fps),
            Synthetic.standStill(durationMs = 5000, seed = seed, camera = com.formcoach.app.coach.synthetic.CameraSpec.landscape(), fps = fps),
        )
        assertEquals(10, clip.truth.expectedReps)
        assertEquals(10, clip.truth.faultCounts[Fault.HIPS_SAG])
        val what = "row11 fps=$fps seed=$seed"
        val r = Run(Exercise.PUSH_UP).feed(clip.withNoise(noise, seed)).drain()
        assertSingleReady(r, what)
        assertReadyBefore(r, clip, what)
        assertEquals("$what\n${r.describe()}", 10, r.state.reps)
        assertEquals("$what\n${r.describe()}", 0, r.state.partialReps)
        assertEquals("$what\n${r.describe()}", 10, r.reps.count { rep -> rep.faults.any { it.id == "hips_sag" } })
        val hipsUp = r.texts.count { it == "Hips up." }
        assertTrue("$what: Hips up x$hipsUp\n${r.describe()}", hipsUp in 1..3)
        val done = r.eventsOf<CoachEvent.SetCompleted>()
        assertEquals("$what: auto set end\n${r.describe()}", 1, done.size)
        assertEquals(10, done.single().summary.reps)
        assertEquals("squeeze your glutes", done.single().summary.topFaultAdvice)
        assertEquals(SessionPhase.SET_COMPLETE, r.state.phase)
        // Auto end comes from leaving the plank for 3 s, before the 8 s rest rule could fire. The knee plank
        // still has a near-horizontal shoulder-ankle line, so "out of the plank" starts at kneeling upright.
        val doneAt = r.events.first { it.event is CoachEvent.SetCompleted }.atMs
        val lastCount = r.events.last { it.event is CoachEvent.RepCounted }.atMs
        assertTrue("$what: set end at ${doneAt - lastCount} ms after the last count", doneAt - lastCount in 3000 until 8000)
        assertTrue("$what\n${r.describe()}", r.texts.last().startsWith("Set done. 10 reps. Next set, squeeze your glutes."))
        assertAllCountsSpoken(r, 10, what)
        assertSpeechRules(r, what)
    }

    @Test fun row11_pushUpSaggingThenKneelAndStand30fps() = seeds.forEach { row11(30, it) }

    @Test fun row11_pushUpSaggingThenKneelAndStand15fps() = seeds.forEach { row11(15, it) }

    // ================================================================================================
    // Row 12: curl, front view, 10 reps with both arms -> 10 reps (not 20)
    // ================================================================================================

    private fun row12(fps: Int, seed: Long) {
        val clip = Synthetic.curl(view = View.FRONT, fps = fps) { reps = 10; mode = CurlMode.BOTH; leadInMs = 3500 }
        assertEquals(10, clip.truth.expectedReps)
        val what = "row12 fps=$fps seed=$seed"
        val r = Run(Exercise.CURL).feed(clip.withNoise(noise, seed)).drain()
        assertReadyBefore(r, clip, what)
        assertEquals(CameraView.FRONT, r.state.view)
        assertEquals("$what\n${r.describe()}", 10, r.state.reps)
        assertEquals("$what\n${r.describe()}", 0, r.state.partialReps)
        assertEquals("$what\n${r.describe()}", 10, r.state.leftReps)
        assertEquals("$what\n${r.describe()}", 10, r.state.rightReps)
        assertTrue("$what: form cues\n${r.describe()}", r.texts.none { it in formCueTexts })
        assertAllCountsSpoken(r, 10, what)
        assertSpeechRules(r, what)
    }

    @Test fun row12_curlFrontBothArms30fps() = seeds.forEach { row12(30, it) }

    @Test fun row12_curlFrontBothArms15fps() = seeds.forEach { row12(15, it) }

    // ================================================================================================
    // Row 13: alternating curls, 5 per arm, front view -> 10 reps (left 5, right 5)
    // ================================================================================================

    @Test fun row13_alternatingCurlsFront() = seeds.forEach { seed ->
        val clip = Synthetic.curl(view = View.FRONT) { reps = 5; mode = CurlMode.ALTERNATING; leadInMs = 3500 }
        assertEquals(10, clip.truth.expectedReps)
        val what = "row13 seed=$seed"
        val r = Run(Exercise.CURL).feed(clip.withNoise(noise, seed)).drain()
        assertEquals("$what\n${r.describe()}", 10, r.state.reps)
        assertEquals("$what\n${r.describe()}", clip.truth.leftReps, r.state.leftReps)
        assertEquals("$what\n${r.describe()}", clip.truth.rightReps, r.state.rightReps)
        assertEquals("$what\n${r.describe()}", 0, r.state.partialReps)
        assertAllCountsSpoken(r, 10, what)
        assertSpeechRules(r, what)
    }

    // ================================================================================================
    // Row 14: arm raised overhead to scratch the head, phone adjusted -> 0 reps
    // ================================================================================================

    private fun row14(exercise: Exercise, view: View, seed: Long) {
        val clip = Synthetic.sequence(
            Synthetic.standStill(durationMs = 3500, seed = seed, view = view),
            Synthetic.scratchHead(arm = Side.RIGHT, view = view),
            Synthetic.scratchHead(arm = Side.LEFT, view = view),
            Synthetic.reachToPhone(view = view),
            Synthetic.standStill(durationMs = 2000, seed = seed + 7, view = view),
        )
        val what = "row14 $exercise $view seed=$seed"
        val r = Run(exercise).feed(clip.withNoise(noise, seed)).drain()
        assertSingleReady(r, what)
        assertEquals("$what\n${r.describe()}", 0, r.state.reps)
        assertEquals("$what\n${r.describe()}", 0, r.state.partialReps)
        assertTrue("$what\n${r.describe()}", r.texts.none { it in inSetCueTexts })
        assertSpeechRules(r, what)
    }

    @Test fun row14_scratchHeadAndAdjustPhone_curlFront() = seeds.forEach { row14(Exercise.CURL, View.FRONT, it) }

    @Test fun row14_scratchHeadAndAdjustPhone_curlSide() = seeds.forEach { row14(Exercise.CURL, View.SIDE, it) }

    @Test fun row14_scratchHeadAndAdjustPhone_squatSide() = seeds.forEach { row14(Exercise.SQUAT, View.SIDE, it) }

    @Test fun row14_scratchHeadAndAdjustPhone_squatFront() = seeds.forEach { row14(Exercise.SQUAT, View.FRONT, it) }

    // ================================================================================================
    // Extra rows
    // ================================================================================================

    /**
     * Side view locks the near arm for the whole set (plan Phase 2 step 3: "lock that side for the whole set";
     * only front views track the arms separately). Alternating curls filmed side-on therefore count the near
     * arm only: 5, not the truth's 10. The far arm is hidden behind the torso on a real camera, so counting it
     * would rest on guessed landmarks. The placement hint and docs should steer alternating curls to a front
     * view. This test pins that behaviour and checks the far arm causes no false or partial reps.
     */
    @Test fun extra_alternatingCurlsSideViewCountsTheNearArm() = seeds.forEach { seed ->
        val clip = Synthetic.curl(view = View.SIDE, nearSide = Side.RIGHT) { reps = 5; mode = CurlMode.ALTERNATING; leadInMs = 3500 }
        assertEquals(10, clip.truth.expectedReps)
        val what = "extra alternating side seed=$seed"
        val r = Run(Exercise.CURL).feed(clip.withNoise(noise, seed)).drain()
        assertEquals(CameraView.SIDE, r.state.view)
        assertEquals(BodySide.RIGHT, r.state.lockedSide)
        assertEquals("$what\n${r.describe()}", clip.truth.rightReps, r.state.reps)
        assertEquals("$what\n${r.describe()}", 0, r.state.partialReps)
        assertAllCountsSpoken(r, 5, what)
        assertSpeechRules(r, what)
    }

    @Test fun extra_bothArmCurlsSideView() = seeds.forEach { seed ->
        val clip = Synthetic.curl(view = View.SIDE) { reps = 10; mode = CurlMode.BOTH; leadInMs = 3500 }
        val what = "extra both-arm side seed=$seed"
        val r = Run(Exercise.CURL).feed(clip.withNoise(noise, seed)).drain()
        assertEquals("$what\n${r.describe()}", 10, r.state.reps)
        assertEquals("$what\n${r.describe()}", 0, r.state.partialReps)
        assertSpeechRules(r, what)
    }

    @Test fun extra_squatFrontClean() = seeds.forEach { seed ->
        val clip = Synthetic.squat(view = View.FRONT) { reps = 10; leadInMs = 3500 }
        val what = "extra squat front clean seed=$seed"
        val r = Run(Exercise.SQUAT).feed(clip.withNoise(noise, seed)).drain()
        assertEquals("$what\n${r.describe()}", 10, r.state.reps)
        assertEquals("$what\n${r.describe()}", 0, r.state.partialReps)
        assertEquals("$what\n${r.describe()}", 0, r.reps.count { it.faults.isNotEmpty() })
        assertTrue("$what\n${r.describe()}", r.texts.none { it in formCueTexts })
        assertAllCountsSpoken(r, 10, what)
        assertSpeechRules(r, what)
    }

    @Test fun extra_pushUpFastReps() = seeds.forEach { seed ->
        val clip = Synthetic.pushUp { reps = 10; leadInMs = 3500; repDurationMs = 700; pauseTopMs = 100 }
        assertEquals(10, clip.truth.expectedReps)
        val what = "extra push-up 0.7 s seed=$seed"
        val r = Run(Exercise.PUSH_UP).feed(clip.withNoise(noise, seed)).drain()
        assertEquals("$what\n${r.describe()}", 10, r.state.reps)
        assertEquals("$what\n${r.describe()}", 0, r.state.partialReps)
        assertCountsKeepUp(r, clip, what)
        assertSpeechRules(r, what)
    }

    @Test fun extra_squatFastReps() = seeds.forEach { seed ->
        val clip = Synthetic.squat { reps = 10; leadInMs = 3500; repDurationMs = 700; pauseTopMs = 0 }
        assertEquals(10, clip.truth.expectedReps)
        val what = "extra squat 0.7 s seed=$seed"
        val r = Run(Exercise.SQUAT).feed(clip.withNoise(noise, seed)).drain()
        assertEquals("$what\n${r.describe()}", 10, r.state.reps)
        assertCountsKeepUp(r, clip, what)
        assertSpeechRules(r, what)
    }

    /** Row 17 on real frames: each count event within one rep of the truth; spoken counts never lag > 1 rep. */
    private fun assertCountsKeepUp(r: Run, clip: SyntheticClip, what: String) {
        val repTimes = r.events.filter { it.event is CoachEvent.RepCounted }.map { it.atMs }
        val truth = clip.truth.reps
        for ((i, t) in repTimes.withIndex()) {
            val next = truth.getOrNull(i + 1)?.endMs ?: (truth[i].endMs + 1000)
            assertTrue("$what: count ${i + 1} at $t, true end ${truth[i].endMs}", t in truth[i].extremeMs..next)
        }
        for (s in r.counts) {
            val n = s.text.toInt()
            val engineCount = repTimes.count { it <= s.startMs }
            assertTrue("$what: spoke $n when engine was at $engineCount", engineCount - n <= 1)
        }
        assertEquals("$what: last count spoken", repTimes.size.toString(), r.counts.last().text)
    }

    @Test fun extra_longIdleAfterSet() = seeds.forEach { seed ->
        val clip = Synthetic.sequence(
            Synthetic.squat { reps = 5; leadInMs = 3500; tailMs = 500 },
            Synthetic.standStill(durationMs = 60_000, swayDeg = 2.0, seed = seed),
        )
        val what = "extra idle seed=$seed"
        val r = Run(Exercise.SQUAT).feed(clip.withNoise(noise, seed)).drain()
        assertEquals("$what\n${r.describe()}", 5, r.state.reps)
        assertEquals("$what\n${r.describe()}", 0, r.state.partialReps)
        val done = r.events.filter { it.event is CoachEvent.SetCompleted }
        assertEquals("$what\n${r.describe()}", 1, done.size)
        val lastRep = clip.truth.reps.last().endMs
        assertTrue("$what: auto end ${done.single().atMs - lastRep} ms after the last rep", done.single().atMs - lastRep in 7500..9500)
        assertEquals("$what\n${r.describe()}", "Set done. 5 reps.", r.texts.last())
        assertEquals(SessionPhase.SET_COMPLETE, r.state.phase)
        assertSpeechRules(r, what)
    }

    @Test fun extra_longIdleInSetBeforeAnyRep() = seeds.take(2).forEach { seed ->
        // With zero reps the set never ends on its own.
        val clip = Synthetic.standStill(durationMs = 63_500, swayDeg = 2.0, seed = seed)
        val r = Run(Exercise.SQUAT).feed(clip.withNoise(noise, seed)).drain()
        assertEquals(0, r.state.reps)
        assertEquals(SessionPhase.IN_SET, r.state.phase)
        assertEquals(listOf("Ready. Go."), r.texts)
    }

    @Test fun extra_distractorsInterleavedWithSquats() = seeds.forEach { seed ->
        val clip = Synthetic.sequence(
            Synthetic.walkIn(),
            Synthetic.squat { reps = 5; leadInMs = 3000; tailMs = 500 },
            Synthetic.reachToPhone(),
            Synthetic.squat { reps = 5; leadInMs = 500 },
        )
        // Short pauses on purpose: resting in the start posture for 8 s after a rep ends the set (plan 3.1), and
        // reaching to the phone keeps the knees straight. Here the rest between the sets of 5 is ~6.9 s.
        assertEquals(10, clip.truth.expectedReps)
        val what = "extra interleaved seed=$seed"
        val r = Run(Exercise.SQUAT).feed(clip.withNoise(noise, seed)).drain()
        assertReadyBefore(r, clip, what)
        assertEquals("$what\n${r.describe()}", 10, r.state.reps)
        assertEquals("$what\n${r.describe()}", 0, r.state.partialReps)
        assertTrue("$what\n${r.describe()}", r.texts.none { it in formCueTexts })
        assertAllCountsSpoken(r, 10, what)
        assertSpeechRules(r, what)
    }

    @Test fun extra_bendOverInterleavedWithSquats() = seeds.forEach { seed ->
        val clip = Synthetic.sequence(
            Synthetic.walkIn(),
            Synthetic.squat { reps = 5; leadInMs = 3000; tailMs = 500 },
            Synthetic.bendOverToTouchPhone(),
            Synthetic.squat { reps = 5; leadInMs = 500 },
        )
        val what = "extra bend interleaved seed=$seed"
        val r = Run(Exercise.SQUAT).feed(clip.withNoise(noise, seed).withSwaps(0.05, seed)).drain()
        assertEquals("$what\n${r.describe()}", 10, r.state.reps)
        assertEquals("$what\n${r.describe()}", 0, r.state.partialReps)
        assertSpeechRules(r, what)
    }

    // ================================================================================================
    // Decision (a): PAUSED for 20 s after at least one rep ends the set
    // ================================================================================================

    @Test fun pausedTwentySecondsAfterARepEndsTheSet() = seeds.take(3).forEach { seed ->
        val clean = Synthetic.sequence(
            Synthetic.squat { reps = 3; leadInMs = 3500; tailMs = 500 },
            Synthetic.standStill(durationMs = 30_000, seed = seed),
        )
        val gapStart = clean.truth.reps.last().endMs + 300
        val clip = clean.withNoise(noise, seed).withDropout(gapStart, gapStart + 30_000)
        val what = "paused end seed=$seed"
        val r = Run(Exercise.SQUAT).feed(clip).drain()
        assertEquals("$what\n${r.describe()}", 3, r.state.reps)
        val pausedAt = r.phases.first { it.second == SessionPhase.PAUSED }.first
        val done = r.events.filter { it.event is CoachEvent.SetCompleted }
        assertEquals("$what\n${r.describe()}", 1, done.size)
        val after = done.single().atMs - pausedAt
        assertTrue("$what: ended $after ms after PAUSED", after in 20_000..20_100)
        assertEquals(SessionPhase.SET_COMPLETE, r.state.phase)
        assertEquals("$what\n${r.describe()}", 1, r.texts.count { it.startsWith("I can't see you") })
        assertEquals("$what\n${r.describe()}", "Set done. 3 reps.", r.texts.last())
        assertSpeechRules(r, what)
    }

    @Test fun pausedWithZeroRepsNeverEndsTheSet() {
        val clean = Synthetic.standStill(durationMs = 40_000, seed = 3)
        val clip = clean.withNoise(noise, 3).withDropout(4000, 40_000)
        val r = Run(Exercise.SQUAT).feed(clip).drain()
        assertEquals(SessionPhase.PAUSED, r.state.phase)
        assertTrue(r.eventsOf<CoachEvent.SetCompleted>().isEmpty())
    }

    @Test fun pausedShorterThanTwentySecondsResumes() {
        val clean = Synthetic.squat { reps = 6; leadInMs = 3500; override(3) { pauseTopMs = 19_000 } }
        val gapStart = clean.truth.reps[2].endMs + 300
        val clip = clean.withNoise(noise, 4).withDropout(gapStart, gapStart + 18_000)
        val r = Run(Exercise.SQUAT).feed(clip).drain()
        assertEquals(r.describe(), 6, r.state.reps)
        assertTrue(r.eventsOf<CoachEvent.SetCompleted>().isEmpty())
    }


    // ================================================================================================
    // Decision (b): front-view curls merged within 400 ms carry both arms' faults (deduplicated)
    // ================================================================================================

    @Test fun mergedBothArmCurlCarriesBothArmsFaults() = seeds.forEach { seed ->
        // Both arms curl together and are back down within the merge window. The left elbow drifts 30° at the
        // peak ("Elbows pinned."); the right arm holds the peak longer, then drops in 0.4 s ("Lower slowly.").
        val n = TestPoses.Noise(seed, 0.006)
        val d = Driver()
        d.command(SessionCommand.SelectExercise(Exercise.CURL))
        d.command(SessionCommand.StartSet)
        d.run(4000) { t, _ -> TestPoses.curlFront(t, 0.0, 0.0, n) }
        assertEquals(1, d.eventsOf<CoachEvent.Ready>().size)
        val (left, end) = TestPoses.repKeys(0, 6, 0.0, 150.0, downMs = 800, upMs = 900, holdMs = 100, restMs = 500)
        val (right, _) = TestPoses.repKeys(0, 6, 0.0, 150.0, downMs = 800, upMs = 400, holdMs = 450, restMs = 650)
        d.run(end + 1000) { t, rel ->
            val l = TestPoses.interp(left, rel)
            TestPoses.curlFront(t, l, TestPoses.interp(right, rel), n, upperArmDeg = 30.0 * l / 150.0)
        }
        val what = "merge seed=$seed reps=${d.reps().map { r -> r.faults.map { it.id } }}"
        assertEquals(what, 6, d.state.reps)
        assertEquals(what, 6, d.state.leftReps)
        assertEquals(what, 6, d.state.rightReps)
        for (rep in d.reps()) {
            val ids = rep.faults.map { it.id }
            assertEquals("$what: deduplicated", ids.distinct(), ids)
            assertTrue("$what: left arm fault", "elbow_drift" in ids)
            assertTrue("$what: right arm fault", "fast_lowering" in ids)
        }
    }

    @Test fun alternatingCurlCountsAreNotDelayed() = seeds.forEach { seed ->
        // The merge hold only applies when the other arm is already on its way down; alternating counts go out
        // at once: the same frames as with the hold switched off.
        val clip = Synthetic.curl(view = View.FRONT) { reps = 5; mode = CurlMode.ALTERNATING; leadInMs = 3500 }.withNoise(noise, seed)
        val noHold = ExerciseConfig().let { it.copy(session = it.session.copy(curlMergeHoldProgress = 2.0)) }
        val r = Run(Exercise.CURL).feed(clip).drain()
        val ref = Run(Exercise.CURL, noHold).feed(clip).drain()
        val times = r.events.filter { it.event is CoachEvent.RepCounted }.map { it.atMs }
        val refTimes = ref.events.filter { it.event is CoachEvent.RepCounted }.map { it.atMs }
        assertEquals(r.describe(), 10, times.size)
        assertEquals("seed $seed", refTimes, times)
    }
}
