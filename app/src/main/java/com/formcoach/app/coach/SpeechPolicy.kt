package com.formcoach.app.coach

/**
 * Decides what the coach says and when. Pure Kotlin; timing uses only the nowMs arguments.
 * Rules: docs/COACH_FIX_PLAN.md sections 3.2 and 3.3.
 *
 * Thread-safe: onEvents and onTick may be called from different threads.
 */
class SpeechPolicy : SpeechScheduler {

    private enum class Kind { COUNT, READY, TRACKING, SUMMARY, REP_CUE, PARTIAL, SETUP, GOOD, PRAISE }

    private class Utt(
        val text: String,
        val priority: SpeechPriority,
        val kind: Kind,
        val createdAtMs: Long,
        val maxAgeMs: Long,
    )

    private val lock = Any()
    private val pending = ArrayList<Utt>()
    private var current: Utt? = null
    private var seq = 0
    private var lastNonCountEndMs = Long.MIN_VALUE / 4

    @Volatile
    private var mutedFlag = false
    override var muted: Boolean
        get() = mutedFlag
        set(value) {
            synchronized(lock) {
                mutedFlag = value
                if (value) pending.clear()
            }
        }

    // Set / rep state
    private var setupActive = false
    private var repIndex = 0
    private var firstPartialPending = true
    private var partialSinceLastRep = false
    private var cueSinceLastRep = false
    private var lastPraiseRep = -1000
    private val history = HashMap<String, ArrayDeque<Boolean>>()
    private val lastCueRep = HashMap<String, Int>()
    private val lastCueAtMs = HashMap<String, Long>()
    private val praiseWatch = HashMap<String, Int>()

    // Setup state
    private var rawIssue: SetupIssue? = null
    private var rawSinceMs = 0L
    private var spokenIssueId: String? = null
    private val setupCount = HashMap<String, Int>()
    private val setupLastAtMs = HashMap<String, Long>()

    // Tracking state
    private var lostAtMs: Long? = null
    private var lostSpoken = false

    override fun onEvents(events: List<CoachEvent>, nowMs: Long) {
        synchronized(lock) {
            for (e in events) handle(e, nowMs)
        }
    }

    override fun onTick(nowMs: Long, speaker: Speaker) {
        synchronized(lock) {
            val cur = current
            if (cur != null && !speaker.isSpeaking) {
                current = null
                if (cur.priority != SpeechPriority.COUNT) lastNonCountEndMs = nowMs
            }
            if (mutedFlag) {
                pending.clear()
                if (current != null || speaker.isSpeaking) {
                    speaker.stopAll()
                    current = null
                    lastNonCountEndMs = nowMs
                }
                return
            }
            tickTracking(nowMs)
            tickSetup(nowMs)
            pending.removeAll { it.priority != SpeechPriority.COUNT && nowMs - it.createdAtMs > it.maxAgeMs }
            startNext(nowMs, speaker)
        }
    }

    // ---- events ----

    private fun handle(e: CoachEvent, now: Long) {
        when (e) {
            is CoachEvent.SetStarted -> {
                pending.removeAll { it.priority != SpeechPriority.COUNT }
                setupActive = true
                repIndex = 0
                firstPartialPending = true
                partialSinceLastRep = false
                cueSinceLastRep = false
                lastPraiseRep = -1000
                history.clear(); lastCueRep.clear(); lastCueAtMs.clear(); praiseWatch.clear()
                rawIssue = null; rawSinceMs = now; spokenIssueId = null
                setupCount.clear(); setupLastAtMs.clear()
                lostAtMs = null; lostSpoken = false
            }
            is CoachEvent.SetupIssueChanged -> {
                if (rawIssue?.id != e.issue?.id) rawSinceMs = now
                rawIssue = e.issue
            }
            is CoachEvent.Ready -> {
                setupActive = false
                pending.removeAll { it.kind == Kind.SETUP || it.kind == Kind.GOOD }
                enqueue("Ready. Go.", SpeechPriority.STATE, Kind.READY, now, STATE_MAX_AGE_MS)
            }
            is CoachEvent.RepCounted -> onRep(e, now)
            is CoachEvent.PartialRep -> {
                if (!cueSinceLastRep && !partialSinceLastRep) {
                    val text = if (firstPartialPending) e.firstCue else e.cue
                    firstPartialPending = false
                    partialSinceLastRep = true
                    enqueue(text, SpeechPriority.FORM, Kind.PARTIAL, now, MAX_AGE_MS)
                }
            }
            is CoachEvent.TrackingLost -> {
                if (lostAtMs == null) {
                    lostAtMs = now
                    lostSpoken = false
                }
            }
            is CoachEvent.TrackingRestored -> {
                lostAtMs = null
                lostSpoken = false
                pending.removeAll { it.kind == Kind.TRACKING }
            }
            is CoachEvent.SetCompleted -> {
                setupActive = false
                lostAtMs = null
                pending.removeAll { it.priority != SpeechPriority.COUNT }
                val n = e.summary.reps
                var text = "Set done. $n ${if (n == 1) "rep" else "reps"}."
                e.summary.topFaultAdvice?.let { text += " Next set, $it." }
                enqueue(text, SpeechPriority.STATE, Kind.SUMMARY, now, STATE_MAX_AGE_MS)
            }
        }
    }

    private fun onRep(e: CoachEvent.RepCounted, now: Long) {
        repIndex++
        // A newer count replaces an unspoken older count; older rep-specific lines are outdated too.
        pending.removeAll {
            it.kind == Kind.COUNT || it.kind == Kind.REP_CUE || it.kind == Kind.PRAISE || it.kind == Kind.PARTIAL
        }
        enqueue(e.count.toString(), SpeechPriority.COUNT, Kind.COUNT, now, MAX_AGE_MS)

        val present = e.faults.map { it.id }.toSet()
        for (id in present) history.getOrPut(id) { ArrayDeque() }
        for ((id, h) in history) {
            h.addLast(id in present)
            while (h.size > 3) h.removeFirst()
        }

        val skipCue = partialSinceLastRep
        partialSinceLastRep = false
        cueSinceLastRep = false

        var cued: FaultCue? = null
        if (!skipCue) {
            for (f in e.faults) {
                val qualifies = history.getValue(f.id).count { it } >= 2
                val repOk = lastCueRep[f.id]?.let { repIndex - it >= COOLDOWN_REPS } ?: true
                val timeOk = lastCueAtMs[f.id]?.let { now - it >= COOLDOWN_MS } ?: true
                if (qualifies && repOk && timeOk) {
                    cued = f
                    break
                }
            }
        }

        // Praise bookkeeping: faults that were cued and are now absent.
        var praise = false
        val iter = praiseWatch.entries.iterator()
        while (iter.hasNext()) {
            val en = iter.next()
            if (en.key in present) {
                en.setValue(0)
            } else {
                en.setValue(en.value + 1)
                if (en.value >= 2) {
                    praise = true
                    iter.remove()
                }
            }
        }

        if (cued != null) {
            lastCueRep[cued.id] = repIndex
            lastCueAtMs[cued.id] = now
            praiseWatch[cued.id] = 0
            cueSinceLastRep = true
            enqueue(cued.cue, SpeechPriority.FORM, Kind.REP_CUE, now, MAX_AGE_MS)
        } else if (praise && repIndex - lastPraiseRep >= PRAISE_EVERY_REPS) {
            lastPraiseRep = repIndex
            enqueue("Better.", SpeechPriority.PRAISE, Kind.PRAISE, now, MAX_AGE_MS)
        }
    }

    // ---- timers ----

    private fun tickTracking(now: Long) {
        val lost = lostAtMs ?: return
        if (!lostSpoken && now - lost >= TRACKING_DELAY_MS) {
            lostSpoken = true
            enqueue("I can't see you. Step back into view.", SpeechPriority.STATE, Kind.TRACKING, now, STATE_MAX_AGE_MS)
        }
    }

    /** Setup lines are only queued when they can start at once, so they never go stale waiting for the gap. */
    private fun canEnqueueSetup(now: Long): Boolean =
        pending.none { it.priority != SpeechPriority.COUNT } &&
            (current?.priority ?: SpeechPriority.COUNT) == SpeechPriority.COUNT &&
            now - lastNonCountEndMs >= GAP_MS

    private fun tickSetup(now: Long) {
        if (!setupActive) return
        if (now - rawSinceMs < SETUP_DEBOUNCE_MS) return
        val raw = rawIssue
        val spoken = spokenIssueId
        if (spoken != null && raw?.id != spoken) {
            if (canEnqueueSetup(now)) {
                spokenIssueId = null
                enqueue("Good.", SpeechPriority.SETUP, Kind.GOOD, now, SETUP_MAX_AGE_MS)
            }
            return
        }
        if (raw == null) return
        val n = setupCount[raw.id] ?: 0
        val last = setupLastAtMs[raw.id]
        if (n < SETUP_MAX_REPEATS && (last == null || now - last >= SETUP_REPEAT_MS) && canEnqueueSetup(now)) {
            setupCount[raw.id] = n + 1
            setupLastAtMs[raw.id] = now
            spokenIssueId = raw.id
            enqueue(raw.text, SpeechPriority.SETUP, Kind.SETUP, now, SETUP_MAX_AGE_MS)
        }
    }

    // ---- output ----

    private fun enqueue(text: String, p: SpeechPriority, k: Kind, now: Long, maxAge: Long) {
        if (mutedFlag) return
        pending.add(Utt(text, p, k, now, maxAge))
    }

    private fun startNext(now: Long, speaker: Speaker) {
        val best = pending.minWithOrNull(compareBy<Utt>({ it.priority.ordinal }, { it.createdAtMs })) ?: return
        val isCount = best.priority == SpeechPriority.COUNT
        if (speaker.isSpeaking) {
            val cur = current
            if (isCount && cur != null && cur.priority != SpeechPriority.COUNT) {
                pending.remove(best)
                speaker.speak(best.text, "u${seq++}", true)
                lastNonCountEndMs = now // the cut-off line ended now
                current = best
            }
            return
        }
        // "Ready. Go." starts the set, so it only waits a short breath after the previous line.
        val gap = if (best.kind == Kind.READY) READY_GAP_MS else GAP_MS
        if (!isCount && now - lastNonCountEndMs < gap) return
        pending.remove(best)
        speaker.speak(best.text, "u${seq++}", false)
        current = best
    }

    private companion object {
        const val GAP_MS = 2_500L
        const val READY_GAP_MS = 600L
        const val MAX_AGE_MS = 1_500L
        const val SETUP_MAX_AGE_MS = 2_000L
        const val STATE_MAX_AGE_MS = 5_000L
        const val COOLDOWN_REPS = 3
        const val COOLDOWN_MS = 15_000L
        const val PRAISE_EVERY_REPS = 5
        const val SETUP_DEBOUNCE_MS = 1_000L
        const val SETUP_REPEAT_MS = 10_000L
        const val SETUP_MAX_REPEATS = 3
        const val TRACKING_DELAY_MS = 500L
    }
}
