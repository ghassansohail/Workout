package com.formcoach.app.coach

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class SpeechPolicyTest {

    // ---------- harness ----------

    private class Spoken(val text: String, val startMs: Long, var endMs: Long, val flush: Boolean) {
        var cut = false
        val isCount get() = text.all { it.isDigit() }
    }

    /** Simulates utterance duration: base + perChar * length. Driven by an external clock. */
    private class FakeSpeaker(private val baseMs: Long = 200, private val perCharMs: Long = 60) : Speaker {
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

    private class Rig(baseMs: Long = 200) {
        val policy = SpeechPolicy()
        val speaker = FakeSpeaker(baseMs)
        var now = 0L
        val texts get() = speaker.log.map { it.text }
        fun send(vararg e: CoachEvent) = policy.onEvents(e.toList(), now)
        fun advance(ms: Long) {
            var left = ms
            while (left > 0) {
                now += 50
                left -= 50
                speaker.now = now
                policy.onTick(now, speaker)
            }
        }
        fun tick() { speaker.now = now; policy.onTick(now, speaker) }
    }

    private val lean = FaultCue("forward_lean", "Chest up.", "keep your chest up")
    private val knees = FaultCue("knees_cave", "Knees out.", "push your knees out")
    private val heels = FaultCue("heels_lift", "Heels down.", "keep your heels down")

    private fun rep(n: Int, vararg f: FaultCue) = CoachEvent.RepCounted(0, n, f.toList())
    private fun partial(n: Int = 1) =
        CoachEvent.PartialRep(0, n, PartialKind.TOO_SHALLOW, "Not counted. Go lower.", "Lower.")
    private fun issue(id: String?) = CoachEvent.SetupIssueChanged(0, id?.let { SetupIssue(it, "Fix $it.") })
    private val start = CoachEvent.SetStarted(0, Exercise.SQUAT)
    private val ready = CoachEvent.Ready(0)

    private fun assertNoOverlap(r: Rig) {
        assertEquals("non-flush speak while speaking", 0, r.speaker.overlaps)
        val log = r.speaker.log
        for (i in 1 until log.size) {
            val a = log[i - 1]
            val b = log[i]
            if (b.startMs < a.endMs) fail("overlap: ${a.text} / ${b.text}")
            if (a.cut) assertTrue("only a count may cut", b.isCount && b.flush && !a.isCount)
        }
        // Gap between non-count utterances, measured from the end of the previous one.
        val nc = log.filter { !it.isCount }
        for (i in 1 until nc.size) {
            assertTrue("gap ${nc[i - 1].text} -> ${nc[i].text}", nc[i].startMs - nc[i - 1].endMs >= 2500)
        }
    }

    private fun fail(m: String): Nothing = throw AssertionError(m)

    // ---------- counts and state ----------

    @Test fun countIsSpokenAsNumber() {
        val r = Rig()
        r.send(rep(7)); r.advance(100)
        assertEquals(listOf("7"), r.texts)
    }

    @Test fun readyEventSpeaksReadyGo() {
        val r = Rig()
        r.send(start, ready); r.advance(200)
        assertEquals(listOf("Ready. Go."), r.texts)
    }

    @Test fun countCutsOffLowerPriorityLine() {
        val r = Rig()
        r.send(start, issue("view")); r.advance(1300)
        assertEquals(listOf("Fix view."), r.texts)
        r.send(rep(1)); r.advance(100)
        assertEquals(listOf("Fix view.", "1"), r.texts)
        assertTrue(r.speaker.log[0].cut)
        assertTrue(r.speaker.log[1].flush)
    }

    @Test fun nothingInterruptsACount() {
        val r = Rig(baseMs = 1000)
        r.send(rep(1)); r.advance(100)
        r.send(CoachEvent.SetCompleted(0, SetSummary(1, 0, null)))
        r.send(CoachEvent.TrackingLost(0))
        r.advance(600)
        assertEquals(listOf("1"), r.texts)
        assertFalse(r.speaker.log[0].cut)
        r.advance(2000)
        assertEquals("1", r.texts[0])
        assertEquals(2, r.texts.size) // lost line is dropped by the summary clearing; summary follows
        assertNoOverlap(r)
    }

    @Test fun newerCountReplacesUnspokenOlderCount() {
        val r = Rig(baseMs = 1000)
        r.send(rep(1)); r.advance(100)
        r.send(rep(2)); r.advance(100)
        r.send(rep(3)); r.advance(3000)
        assertEquals(listOf("1", "3"), r.texts)
    }

    // ---------- form cues ----------

    @Test fun cueOnlyWhenFaultInTwoOfLastThreeReps() {
        val r = Rig()
        r.send(rep(1, lean)); r.advance(1500)
        assertEquals(listOf("1"), r.texts)
        r.send(rep(2)); r.advance(1500)
        r.send(rep(3, lean)); r.advance(1500)
        assertEquals(listOf("1", "2", "3", "Chest up."), r.texts)
    }

    @Test fun faultFourRepsApartDoesNotQualify() {
        val r = Rig()
        r.send(rep(1, lean)); r.advance(1500)
        r.send(rep(2)); r.advance(1500)
        r.send(rep(3)); r.advance(1500)
        r.send(rep(4, lean)); r.advance(1500)
        assertFalse(r.texts.contains("Chest up."))
    }

    @Test fun cueFollowsCountDirectlyAndOnlyOnePerRep() {
        val r = Rig()
        r.send(rep(1, knees, lean)); r.advance(1500)
        r.send(rep(2, knees, lean)); r.advance(1500)
        assertEquals(listOf("1", "2", "Knees out."), r.texts)
        val c = r.speaker.log[1]
        val cue = r.speaker.log[2]
        assertTrue(cue.startMs - c.endMs <= 50)
    }

    @Test fun secondQualifyingFaultCuedOnLaterRepNotSameRep() {
        val r = Rig()
        r.send(rep(1, knees, lean)); r.advance(1500)
        r.send(rep(2, knees, lean)); r.advance(3000)
        r.send(rep(3, knees, lean)); r.advance(3000)
        // rep 3: knees on cooldown, so the next qualifying fault in order is lean
        assertEquals(listOf("1", "2", "Knees out.", "3", "Chest up."), r.texts)
    }

    @Test fun cooldownIsThreeRepsAndFifteenSeconds() {
        val r = Rig()
        // Reps every 2 s with the same fault: cue after rep 2 (t=4s), next allowed at t>=19s and rep>=5.
        for (n in 1..12) {
            r.send(rep(n, lean)); r.advance(2000)
        }
        val cues = r.speaker.log.filter { it.text == "Chest up." }
        assertEquals(2, cues.size)
        assertTrue(cues[1].startMs - cues[0].startMs >= 15_000)
        // Rep-based cooldown on its own: fast reps (0.5 s) for 15+ s still honour 3 reps after 15 s.
        val r2 = Rig()
        for (n in 1..6) { r2.send(rep(n, lean)); r2.advance(500) }
        assertEquals(1, r2.speaker.log.count { it.text == "Chest up." })
    }

    @Test fun betterAfterTwoCleanRepsAtMostOncePerFiveReps() {
        val r = Rig()
        r.send(rep(1, lean)); r.advance(2000)
        r.send(rep(2, lean)); r.advance(2000)  // cue
        r.send(rep(3)); r.advance(2000)
        assertFalse(r.texts.contains("Better."))
        r.send(rep(4)); r.advance(2000)
        assertEquals(1, r.texts.count { it == "Better." })
        // Another cue/praise cycle within 5 reps: no second "Better.".
        r.send(rep(5, knees)); r.advance(2000)
        r.send(rep(6, knees)); r.advance(2000) // knees cue
        r.send(rep(7)); r.advance(2000)
        r.send(rep(8)); r.advance(2000)
        assertEquals(1, r.texts.count { it == "Better." })
    }

    @Test fun noBetterIfFaultReturns() {
        val r = Rig()
        r.send(rep(1, lean)); r.advance(500)
        r.send(rep(2, lean)); r.advance(1500)
        r.send(rep(3)); r.advance(500)
        r.send(rep(4, lean)); r.advance(500)
        r.send(rep(5)); r.advance(2000)
        assertFalse(r.texts.contains("Better."))
    }

    // ---------- partial reps ----------

    @Test fun partialUsesFirstCueThenCueAndResetsOnSetStart() {
        val r = Rig()
        r.send(start, ready); r.advance(3000)
        r.send(partial(1)); r.advance(3000)
        r.send(rep(1)); r.advance(300)
        r.send(partial(2)); r.advance(3000)
        assertEquals(listOf("Ready. Go.", "Not counted. Go lower.", "1", "Lower."), r.texts)
        r.send(start); r.send(partial(1)); r.advance(3000)
        assertEquals("Not counted. Go lower.", r.texts.last())
    }

    @Test fun atMostOneFormUtterancePerRep() {
        val r = Rig()
        r.send(rep(1, lean)); r.advance(1500)
        r.send(rep(2, lean)); r.advance(1500) // cue
        r.send(partial(1)); r.advance(3500)   // same rep window as the cue: suppressed
        assertEquals(1, r.texts.count { it == "Chest up." })
        assertFalse(r.texts.contains("Not counted. Go lower."))
        // Partial before a rep suppresses that rep's cue.
        r.send(partial(2)); r.advance(500)
        r.send(rep(3, lean)); r.advance(3500)
        assertEquals(1, r.texts.count { it == "Chest up." })
    }

    // ---------- setup ----------

    @Test fun setupSpokenOnlyAfterStableOneSecond() {
        val r = Rig()
        r.send(start); r.advance(100)
        r.send(issue("view")); r.advance(900)
        assertTrue(r.texts.isEmpty())
        r.advance(300)
        assertEquals(listOf("Fix view."), r.texts)
    }

    @Test fun setupRepeatsNoSoonerThan10sAndAtMostThreeTimes() {
        val r = Rig()
        r.send(start, issue("view")); r.advance(60_000)
        val s = r.speaker.log.filter { it.text == "Fix view." }
        assertEquals(3, s.size)
        assertTrue(s[1].startMs - s[0].startMs >= 10_000)
        assertTrue(s[2].startMs - s[1].startMs >= 10_000)
    }

    @Test fun goodSaidOnceWhenSpokenIssueClears() {
        val r = Rig()
        r.send(start, issue("view")); r.advance(1500)
        r.send(issue(null)); r.advance(500)
        assertEquals(listOf("Fix view."), r.texts)
        r.advance(3500)
        assertEquals(listOf("Fix view.", "Good."), r.texts)
        r.advance(20_000)
        assertEquals(2, r.texts.size)
    }

    @Test fun noGoodForIssueThatWasNeverSpoken() {
        val r = Rig()
        r.send(start, issue("view")); r.advance(500)
        r.send(issue(null)); r.advance(5000)
        assertTrue(r.texts.isEmpty())
    }

    @Test fun goodThenNewIssueWhenIssueChangesAndPersists() {
        val r = Rig()
        r.send(start, issue("view")); r.advance(1500)
        r.send(issue("stance")); r.advance(10_000)
        assertEquals(listOf("Fix view.", "Good.", "Fix stance."), r.texts)
        assertNoOverlap(r)
    }

    @Test fun setupSilentBeforeStartAndAfterReady() {
        val r = Rig()
        r.send(issue("view")); r.advance(3000)
        assertTrue(r.texts.isEmpty())
        r.send(start); r.advance(100)
        r.send(issue("stance")); r.advance(500)
        r.send(ready); r.advance(5000)
        assertEquals(listOf("Ready. Go."), r.texts)
        r.send(issue("view")); r.advance(5000)
        assertEquals(listOf("Ready. Go."), r.texts)
    }

    @Test fun row15_alternatingSetupFaultsSpeakAtMostTwice() {
        val r = Rig()
        r.send(start)
        var a = true
        var t = 0
        while (t < 10_000) {
            r.send(issue(if (a) "view" else "stance"))
            a = !a
            r.advance(200)
            t += 200
        }
        assertTrue("got ${r.texts}", r.texts.size <= 2)
        assertNoOverlap(r)
    }

    // ---------- tracking ----------

    @Test fun trackingLostSpokenAfterHalfSecondOncePerEpisode() {
        val r = Rig()
        r.send(CoachEvent.TrackingLost(0)); r.advance(400)
        assertTrue(r.texts.isEmpty())
        r.advance(200)
        assertEquals(listOf("I can't see you. Step back into view."), r.texts)
        r.send(CoachEvent.TrackingLost(0)); r.advance(30_000)
        assertEquals(1, r.texts.size)
        r.send(CoachEvent.TrackingRestored(0)); r.advance(1000)
        assertEquals(1, r.texts.size)
        r.send(CoachEvent.TrackingLost(0)); r.advance(1000)
        assertEquals(2, r.texts.size)
    }

    @Test fun trackingRestoredWithinHalfSecondIsSilent() {
        val r = Rig()
        r.send(CoachEvent.TrackingLost(0)); r.advance(300)
        r.send(CoachEvent.TrackingRestored(0)); r.advance(3000)
        assertTrue(r.texts.isEmpty())
    }

    // ---------- summary ----------

    @Test fun summaryWordingAndSingular() {
        val r = Rig()
        r.send(CoachEvent.SetCompleted(0, SetSummary(10, 0, "keep your chest up"))); r.advance(5000)
        assertEquals("Set done. 10 reps. Next set, keep your chest up.", r.texts.single())
        val r2 = Rig()
        r2.send(CoachEvent.SetCompleted(0, SetSummary(1, 0, null))); r2.advance(5000)
        assertEquals("Set done. 1 rep.", r2.texts.single())
    }

    // ---------- global timing ----------

    @Test fun gapOfTwoAndHalfSecondsFromEndOfPreviousNonCount() {
        val r = Rig()
        r.send(rep(1, lean)); r.advance(500)
        r.send(rep(2, lean)); r.advance(500)
        val cue = r.speaker.log.first { it.text == "Chest up." }
        r.send(CoachEvent.TrackingLost(0))
        r.advance(6000)
        val lost = r.speaker.log.first { it.text.startsWith("I can't") }
        assertTrue(lost.startMs - cue.endMs >= 2500)
        assertNoOverlap(r)
    }

    @Test fun staleQueuedLineIsDropped() {
        val r = Rig()
        r.send(start, ready); r.advance(1000) // "Ready. Go." ends at ~0.8 s => gap until ~3.3 s
        r.send(rep(1, lean)); r.advance(300)
        r.send(rep(2, lean)); r.advance(5000)
        assertEquals(listOf("Ready. Go.", "1", "2"), r.texts)
    }

    @Test fun mutedSpeaksNothingAndDropsPending() {
        val r = Rig()
        r.policy.muted = true
        r.send(start, ready, rep(1), rep(2, lean)); r.advance(5000)
        assertTrue(r.texts.isEmpty())
        r.policy.muted = false
        r.send(rep(3)); r.advance(500)
        assertEquals(listOf("3"), r.texts)
        r.send(rep(4)); r.policy.muted = true; r.advance(2000)
        assertEquals(listOf("3"), r.texts)
    }

    // ---------- plan rows 16, 17 and full set ----------

    @Test fun row16_noOverlapUnderRandomEventStorm() {
        val r = Rig()
        val rnd = Random(42)
        val faults = listOf(lean, knees, heels)
        var n = 0
        r.send(start)
        repeat(1200) { // 60 s
            if (rnd.nextInt(30) == 0) r.send(rep(++n, *faults.filter { rnd.nextBoolean() }.toTypedArray()))
            if (rnd.nextInt(40) == 0) r.send(partial(rnd.nextInt(3) + 1))
            if (rnd.nextInt(25) == 0) r.send(issue(listOf("view", "stance", null)[rnd.nextInt(3)]))
            if (rnd.nextInt(80) == 0) r.send(CoachEvent.TrackingLost(0))
            if (rnd.nextInt(60) == 0) r.send(CoachEvent.TrackingRestored(0))
            if (rnd.nextInt(300) == 0) r.send(ready)
            r.advance(50)
        }
        assertTrue(r.texts.isNotEmpty())
        assertNoOverlap(r)
    }

    private fun assertCountLagAtMostOne(r: Rig, sentAt: List<Pair<Long, Int>>) {
        for (u in r.speaker.log.filter { it.isCount }) {
            val latest = sentAt.last { it.first <= u.startMs }.second
            assertTrue("count ${u.text} spoken at ${u.startMs} while rep $latest", latest - u.text.toInt() <= 1)
        }
    }

    @Test fun row17_fastRepsEvery700msCountNeverLagsMoreThanOne() {
        val r = Rig()
        val sent = ArrayList<Pair<Long, Int>>()
        r.send(start, ready); r.advance(1000)
        for (n in 1..20) {
            r.send(rep(n)); sent.add(r.now to n)
            r.advance(700)
        }
        r.advance(2000)
        assertCountLagAtMostOne(r, sent)
        assertEquals(20, r.speaker.log.count { it.isCount })
        assertNoOverlap(r)
    }

    @Test fun row17_slowSpeakerDropsStaleCounts() {
        val r = Rig(baseMs = 900) // each count takes ~1 s, reps arrive every 0.7 s
        val sent = ArrayList<Pair<Long, Int>>()
        for (n in 1..20) {
            r.send(rep(n)); sent.add(r.now to n)
            r.advance(700)
        }
        r.advance(3000)
        val spoken = r.speaker.log.filter { it.isCount }
        assertTrue("some counts dropped", spoken.size < 20)
        assertCountLagAtMostOne(r, sent)
        assertEquals("20", spoken.last().text)
        assertNoOverlap(r)
    }

    @Test fun fullTenRepSetWithPersistentFault() {
        val r = Rig()
        r.send(start, issue("start_posture")); r.advance(1500)
        r.send(issue(null)); r.advance(4500)
        r.send(ready); r.advance(3000) // "Ready. Go." waits for the 2.5 s gap after "Good."
        for (n in 1..10) {
            r.send(rep(n, lean)); r.advance(2000)
        }
        r.send(CoachEvent.SetCompleted(0, SetSummary(10, 0, "keep your chest up")))
        r.advance(5000)

        val texts = r.texts
        assertEquals(10, texts.count { it.all(Char::isDigit) })
        assertEquals(listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "10"), texts.filter { it.all(Char::isDigit) })
        assertEquals("Fix start_posture.", texts[0])
        assertEquals("Good.", texts[1])
        assertEquals("Ready. Go.", texts[2])
        // First cue directly after the 2nd count, none after the 1st.
        val i2 = texts.indexOf("2")
        assertEquals("Chest up.", texts[i2 + 1])
        assertFalse(texts.subList(0, i2).contains("Chest up."))
        // Cooldown: the second cue is at least 15 s later (rep 10 at the earliest here).
        val cues = r.speaker.log.filter { it.text == "Chest up." }
        assertEquals(2, cues.size)
        assertTrue(cues[1].startMs - cues[0].startMs >= 15_000)
        assertEquals("10", texts[texts.lastIndexOf("Chest up.") - 1])
        assertEquals("Set done. 10 reps. Next set, keep your chest up.", texts.last())
        assertNoOverlap(r)
    }
}
