package com.formcoach.app.coach.synthetic

import com.formcoach.app.coach.Landmark
import com.formcoach.app.coach.LandmarkId
import com.formcoach.app.coach.PoseFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.sqrt

class SyntheticGeneratorTest {
    private val tol = 1.5

    // --- measurement helpers ---------------------------------------------------------------------------------

    private fun lm(f: PoseFrame, id: Int): Landmark = f[id] ?: fail("landmark $id missing at ${f.timestampMs}").let { error("unreachable") }

    private fun angleAt(a: Landmark, b: Landmark, c: Landmark): Double {
        val v1x = a.x - b.x; val v1y = a.y - b.y
        val v2x = c.x - b.x; val v2y = c.y - b.y
        val cos = (v1x * v2x + v1y * v2y) / (hypot(v1x, v1y) * hypot(v2x, v2y))
        return Math.toDegrees(acos(cos.toDouble().coerceIn(-1.0, 1.0)))
    }

    private fun kneeAngle(f: PoseFrame, s: Side): Double {
        val l = s == Side.LEFT
        return angleAt(lm(f, if (l) LandmarkId.LEFT_HIP else LandmarkId.RIGHT_HIP), lm(f, if (l) LandmarkId.LEFT_KNEE else LandmarkId.RIGHT_KNEE), lm(f, if (l) LandmarkId.LEFT_ANKLE else LandmarkId.RIGHT_ANKLE))
    }

    private fun elbowAngle(f: PoseFrame, s: Side): Double {
        val l = s == Side.LEFT
        return angleAt(lm(f, if (l) LandmarkId.LEFT_SHOULDER else LandmarkId.RIGHT_SHOULDER), lm(f, if (l) LandmarkId.LEFT_ELBOW else LandmarkId.RIGHT_ELBOW), lm(f, if (l) LandmarkId.LEFT_WRIST else LandmarkId.RIGHT_WRIST))
    }

    /** Torso lean from vertical in degrees (hip to shoulder), regardless of facing direction. */
    private fun torsoLean(f: PoseFrame, s: Side): Double {
        val l = s == Side.LEFT
        val hip = lm(f, if (l) LandmarkId.LEFT_HIP else LandmarkId.RIGHT_HIP)
        val sh = lm(f, if (l) LandmarkId.LEFT_SHOULDER else LandmarkId.RIGHT_SHOULDER)
        return Math.toDegrees(atan2(abs((sh.x - hip.x).toDouble()), (hip.y - sh.y).toDouble()))
    }

    private fun at(clip: SyntheticClip, ms: Long): PoseFrame = clip.frames.minByOrNull { abs(it.timestampMs - ms) }!!

    private fun assertNear(expected: Double, actual: Double, tol: Double, what: String) =
        assertTrue("$what: expected $expected +-$tol but was $actual", abs(expected - actual) <= tol)

    // --- geometry at keyframes -------------------------------------------------------------------------------

    @Test
    fun squatSideKneeAndTorsoMatchRequestedAngles() {
        val clip = Synthetic.squat(view = View.SIDE) {
            reps = 3
            bottomKneeDeg = 90.0; torsoLeanDeg = 35.0; shinLeanDeg = 25.0
            override(2) { bottomKneeDeg = 125.0; torsoLeanDeg = 60.0 }
        }
        val reps = clip.truth.reps
        assertNear(175.0, kneeAngle(clip.frames.first(), Side.RIGHT), tol, "standing knee")
        assertNear(0.0, torsoLean(clip.frames.first(), Side.RIGHT), tol, "standing lean")
        for ((i, r) in reps.withIndex()) {
            val f = at(clip, r.extremeMs)
            val knee = if (i == 1) 125.0 else 90.0
            val lean = if (i == 1) 60.0 else 35.0
            assertNear(knee, kneeAngle(f, Side.RIGHT), tol, "rep ${i + 1} bottom knee")
            assertNear(lean, torsoLean(f, Side.RIGHT), tol, "rep ${i + 1} bottom lean")
            // Shin lean: ankle to knee against vertical.
            val ankle = lm(f, LandmarkId.RIGHT_ANKLE); val k = lm(f, LandmarkId.RIGHT_KNEE)
            assertNear(25.0, Math.toDegrees(atan2(abs((k.x - ankle.x).toDouble()), (ankle.y - k.y).toDouble())), tol, "shin lean")
        }
    }

    @Test
    fun squatLeftNearSideAlsoMatches() {
        val clip = Synthetic.squat(view = View.SIDE, nearSide = Side.LEFT) { reps = 1; bottomKneeDeg = 100.0 }
        val f = at(clip, clip.truth.reps[0].extremeMs)
        assertNear(100.0, kneeAngle(f, Side.LEFT), tol, "left near-side knee")
    }

    @Test
    fun pushUpElbowAndBodyLineMatch() {
        val clip = Synthetic.pushUp {
            reps = 3; bottomElbowDeg = 80.0; lockoutCompleteness = 1.0
            override(2) { bottomElbowDeg = 100.0 }
        }
        assertEquals(640, clip.frames[0].imageWidth); assertEquals(480, clip.frames[0].imageHeight)
        assertNear(180.0, elbowAngle(clip.frames.first(), Side.RIGHT), tol, "lockout elbow")
        for ((i, r) in clip.truth.reps.withIndex()) {
            assertNear(if (i == 1) 100.0 else 80.0, elbowAngle(at(clip, r.extremeMs), Side.RIGHT), tol, "push-up ${i + 1} bottom elbow")
        }
        // Body is near horizontal and straight: shoulder-hip-ankle angle ~180.
        val f = at(clip, clip.truth.reps[0].extremeMs)
        assertNear(180.0, angleAt(lm(f, LandmarkId.RIGHT_SHOULDER), lm(f, LandmarkId.RIGHT_HIP), lm(f, LandmarkId.RIGHT_ANKLE)), tol, "straight body")
    }

    @Test
    fun pushUpHipSagAndPikeHaveRequestedSignedDistance() {
        fun hipOffset(sag: Double): Double {
            val clip = Synthetic.pushUp { reps = 1; hipSag = sag }
            val f = at(clip, clip.truth.reps[0].extremeMs)
            val s = lm(f, LandmarkId.RIGHT_SHOULDER); val a = lm(f, LandmarkId.RIGHT_ANKLE); val h = lm(f, LandmarkId.RIGHT_HIP)
            val len = hypot((s.x - a.x).toDouble(), (s.y - a.y).toDouble())
            // signed distance of the hip below the shoulder-ankle line (image y grows downward)
            val cross = ((s.x - a.x) * (h.y - a.y) - (s.y - a.y) * (h.x - a.x)) / len
            val facingRight = s.x > a.x
            val below = if (facingRight) cross else -cross
            return below / len
        }
        assertNear(0.0, hipOffset(0.0), 0.005, "straight")
        assertNear(0.1, hipOffset(0.1), 0.02, "sag")
        assertNear(-0.1, hipOffset(-0.1), 0.02, "pike")
    }

    @Test
    fun curlSideElbowTopAndBottomMatch() {
        val clip = Synthetic.curl(view = View.SIDE, nearSide = Side.RIGHT) {
            reps = 2; topElbowDeg = 40.0; bottomElbowDeg = 165.0
            override(2) { topElbowDeg = 70.0; bottomElbowDeg = 150.0 }
        }
        val r1 = clip.truth.reps[0]; val r2 = clip.truth.reps[1]
        assertNear(40.0, elbowAngle(at(clip, r1.extremeMs), Side.RIGHT), tol, "curl 1 top")
        assertNear(70.0, elbowAngle(at(clip, r2.extremeMs), Side.RIGHT), tol, "curl 2 top")
        assertNear(165.0, elbowAngle(at(clip, r1.endMs + 100), Side.RIGHT), tol, "curl 1 bottom")
        assertNear(150.0, elbowAngle(clip.frames.last(), Side.RIGHT), tol, "curl 2 bottom")
    }

    @Test
    fun curlElbowDriftAndTorsoSwingAreRendered() {
        val clip = Synthetic.curl(view = View.SIDE, nearSide = Side.RIGHT) { reps = 1; elbowDriftDeg = 30.0; torsoSwingDeg = 12.0 }
        val f = at(clip, clip.truth.reps[0].extremeMs)
        val sh = lm(f, LandmarkId.RIGHT_SHOULDER); val el = lm(f, LandmarkId.RIGHT_ELBOW)
        assertNear(30.0, Math.toDegrees(atan2(abs((el.x - sh.x).toDouble()), (el.y - sh.y).toDouble())), tol, "elbow drift")
        assertNear(12.0, torsoLean(f, Side.RIGHT), tol, "torso swing")
        assertTrue(clip.truth.reps[0].faults.containsAll(listOf(Fault.ELBOW_DRIFT, Fault.SWINGING)))
    }

    @Test
    fun squatHeelLiftRaisesTheHeel() {
        val plain = Synthetic.squat { reps = 1 }
        val lifted = Synthetic.squat { reps = 1; heelLift = 0.06 }
        fun heelRise(c: SyntheticClip): Double {
            val f = at(c, c.truth.reps[0].extremeMs)
            return (lm(f, LandmarkId.RIGHT_FOOT_INDEX).y - lm(f, LandmarkId.RIGHT_HEEL).y) / c.bodyHeightPx / 0.48
        }
        assertTrue(heelRise(lifted) - heelRise(plain) in 0.04..0.08)
        assertTrue(Fault.HEELS_LIFT in lifted.truth.reps[0].faults)
        assertTrue(plain.truth.reps[0].faults.isEmpty())
    }

    // --- time -----------------------------------------------------------------------------------------------

    @Test
    fun timestampsStepAtTheFrameRate() {
        for (fps in listOf(30, 15, 24)) {
            val clip = Synthetic.squat(fps = fps) { reps = 2 }
            val t = clip.frames.map { it.timestampMs }
            assertEquals(0L, t.first())
            for (i in 1 until t.size) assertTrue("fps $fps step ${t[i] - t[i - 1]}", abs((t[i] - t[i - 1]) - 1000.0 / fps) <= 1.0)
            // exact: frame i is at round(i * 1000 / fps)
            for (i in t.indices) assertEquals(Math.round(i * 1000.0 / fps), t[i])
        }
        val shifted = Synthetic.squat(startMs = 5000) { reps = 1 }
        assertEquals(5000L, shifted.frames.first().timestampMs)
    }

    @Test
    fun defaultImageSizes() {
        assertEquals(480, Synthetic.squat { reps = 1 }.frames[0].imageWidth)
        assertEquals(640, Synthetic.squat { reps = 1 }.frames[0].imageHeight)
        assertEquals(640, Synthetic.pushUp { reps = 1 }.frames[0].imageWidth)
        assertEquals(480, Synthetic.pushUp { reps = 1 }.frames[0].imageHeight)
    }

    // --- camera views -------------------------------------------------------------------------------------------

    @Test
    fun frontViewLeftAndRightAreOnTheCorrectImageSides() {
        val clip = Synthetic.squat(view = View.FRONT) { reps = 1 }
        for (f in listOf(clip.frames.first(), at(clip, clip.truth.reps[0].extremeMs))) {
            // Person faces the camera, image unmirrored: their left is on the image's right.
            for ((l, r) in listOf(
                LandmarkId.LEFT_SHOULDER to LandmarkId.RIGHT_SHOULDER, LandmarkId.LEFT_HIP to LandmarkId.RIGHT_HIP,
                LandmarkId.LEFT_KNEE to LandmarkId.RIGHT_KNEE, LandmarkId.LEFT_ANKLE to LandmarkId.RIGHT_ANKLE,
                LandmarkId.LEFT_EAR to LandmarkId.RIGHT_EAR, LandmarkId.LEFT_EYE to LandmarkId.RIGHT_EYE,
                LandmarkId.LEFT_WRIST to LandmarkId.RIGHT_WRIST, LandmarkId.LEFT_FOOT_INDEX to LandmarkId.RIGHT_FOOT_INDEX,
            )) assertTrue("left $l must be right of $r", lm(f, l).x > lm(f, r).x)
        }
        val f = clip.frames.first()
        // Shoulder width is wider than hip width; nose is centred.
        assertTrue(lm(f, LandmarkId.LEFT_SHOULDER).x - lm(f, LandmarkId.RIGHT_SHOULDER).x > lm(f, LandmarkId.LEFT_HIP).x - lm(f, LandmarkId.RIGHT_HIP).x)
        assertEquals(240.0, lm(f, LandmarkId.NOSE).x.toDouble(), 1.0)
        // Both sides close to the same depth.
        assertEquals(lm(f, LandmarkId.LEFT_SHOULDER).z.toDouble(), lm(f, LandmarkId.RIGHT_SHOULDER).z.toDouble(), 1.0)
    }

    @Test
    fun frontViewKneeCaveAndWeightShiftAreVisible() {
        val clip = Synthetic.squat(view = View.FRONT) { reps = 2; override(2) { kneeCave = 0.3; lateralShift = 0.4 } }
        fun kneeGapRatio(f: PoseFrame) =
            (lm(f, LandmarkId.LEFT_KNEE).x - lm(f, LandmarkId.RIGHT_KNEE).x) / (lm(f, LandmarkId.LEFT_ANKLE).x - lm(f, LandmarkId.RIGHT_ANKLE).x)
        fun hipShift(f: PoseFrame): Double {
            val hipMid = (lm(f, LandmarkId.LEFT_HIP).x + lm(f, LandmarkId.RIGHT_HIP).x) / 2
            val ankleMid = (lm(f, LandmarkId.LEFT_ANKLE).x + lm(f, LandmarkId.RIGHT_ANKLE).x) / 2
            val hipWidth = lm(f, LandmarkId.LEFT_HIP).x - lm(f, LandmarkId.RIGHT_HIP).x
            return ((hipMid - ankleMid) / hipWidth).toDouble()
        }
        val b1 = at(clip, clip.truth.reps[0].extremeMs); val b2 = at(clip, clip.truth.reps[1].extremeMs)
        assertNear(1.0, kneeGapRatio(b1).toDouble(), 0.01, "clean knee gap ratio")
        assertNear(0.7, kneeGapRatio(b2).toDouble(), 0.01, "caved knee gap ratio")
        assertNear(0.0, hipShift(b1), 0.01, "clean shift")
        assertNear(0.4, hipShift(b2), 0.01, "shift in hip widths")
        assertEquals(emptySet<Fault>(), clip.truth.reps[0].faults)
        assertEquals(setOf(Fault.KNEES_CAVE, Fault.WEIGHT_SHIFT), clip.truth.reps[1].faults)
        // Hips drop in the image at the bottom.
        assertTrue(lm(b1, LandmarkId.LEFT_HIP).y - lm(clip.frames.first(), LandmarkId.LEFT_HIP).y > 0.2f * clip.bodyHeightPx.toFloat() * 0.5f)
    }

    @Test
    fun sideViewNearSideHasSmallerZ() {
        for (near in Side.values()) {
            val clip = Synthetic.squat(view = View.SIDE, nearSide = near) { reps = 1 }
            val f = at(clip, clip.truth.reps[0].extremeMs)
            val nearIds = if (near == Side.LEFT) listOf(11, 13, 23, 25, 27) else listOf(12, 14, 24, 26, 28)
            for (n in nearIds) {
                val farId = if (near == Side.LEFT) n + 1 else n - 1
                assertTrue("near $n z ${lm(f, n).z} vs far $farId z ${lm(f, farId).z}", lm(f, n).z < lm(f, farId).z)
                assertTrue("far side still reported with high inFrame", lm(f, farId).inFrame >= 0.9f)
                assertTrue(lm(f, n).inFrame >= 0.9f)
            }
            // A right-near person faces image-right (nose ahead of hip), a left-near person faces image-left.
            val nose = lm(f, LandmarkId.NOSE).x; val hip = lm(f, LandmarkId.RIGHT_HIP).x
            if (near == Side.RIGHT) assertTrue(nose > hip) else assertTrue(nose < hip)
            // Far side slightly offset from the near side but not wildly.
            val dx = abs(lm(f, 11).x - lm(f, 12).x)
            assertTrue(dx in 1f..0.05f * clip.bodyHeightPx.toFloat())
        }
    }

    @Test
    fun landmarksOutsideTheImageHaveLowInFrame() {
        val cam = CameraSpec.portrait(View.SIDE).copy(centerX = 0.0) // figure hangs off the left edge
        val clip = Synthetic.standStill(durationMs = 500, camera = cam)
        val f = clip.frames.first()
        var outside = 0; var inside = 0
        for (id in 0 until LandmarkId.COUNT) {
            val l = lm(f, id)
            val isIn = l.x in 0f..480f && l.y in 0f..640f
            if (isIn) { inside++; assertTrue(l.inFrame >= 0.9f) } else { outside++; assertTrue("id $id inFrame ${l.inFrame}", l.inFrame < 0.2f) }
        }
        assertTrue(outside > 0 && inside > 0)
    }

    @Test
    fun allLandmarksStayInsideTheImageForNormalExercises() {
        val clips = listOf(
            Synthetic.squat { reps = 2 }, Synthetic.squat(view = View.FRONT) { reps = 2 },
            Synthetic.pushUp { reps = 2 }, Synthetic.curl { reps = 2 }, Synthetic.curl(view = View.SIDE) { reps = 2 },
        )
        for (c in clips) for (f in c.frames) for (id in 0 until LandmarkId.COUNT) {
            val l = lm(f, id)
            assertTrue("${c.truth.description} t=${f.timestampMs} id=$id (${l.x},${l.y})", l.x in 0f..f.imageWidth.toFloat() && l.y in 0f..f.imageHeight.toFloat() && l.inFrame > 0.5f)
        }
    }

    @Test
    fun feetStayOnTheFloor() {
        val cam = CameraSpec.portrait(View.SIDE)
        val clips = listOf(Synthetic.squat { reps = 2; heelLift = 0.05 }, Synthetic.curl { reps = 1 }, Synthetic.standStill(), Synthetic.bendOverToTouchPhone())
        for (c in clips) for (f in c.frames) {
            val lowest = listOf(LandmarkId.LEFT_HEEL, LandmarkId.RIGHT_HEEL, LandmarkId.LEFT_FOOT_INDEX, LandmarkId.RIGHT_FOOT_INDEX, LandmarkId.LEFT_ANKLE).maxOf { lm(f, it).y }
            assertTrue(lowest <= cam.floorY + 0.012 * cam.bodyHeightPx)
        }
        val pu = Synthetic.pushUp { reps = 2; hipSag = 0.05 }
        val camL = CameraSpec.landscape()
        for (f in pu.frames) for (id in 0 until LandmarkId.COUNT) assertTrue("id $id", lm(f, id).y <= camL.floorY + 0.012 * camL.bodyHeightPx)
    }

    // --- noise and modifiers --------------------------------------------------------------------------------------

    private fun same(a: SyntheticClip, b: SyntheticClip): Boolean =
        a.frames.size == b.frames.size && a.frames.indices.all { i ->
            a.frames[i].timestampMs == b.frames[i].timestampMs && (0 until LandmarkId.COUNT).all { a.frames[i][it] == b.frames[i][it] }
        }

    @Test
    fun noiseIsDeterministicPerSeedAndHasTheRequestedSigma() {
        val clean = Synthetic.squat { reps = 3 }
        val a = clean.withNoise(0.01, seed = 7)
        val b = clean.withNoise(0.01, seed = 7)
        val c = clean.withNoise(0.01, seed = 8)
        assertTrue(same(a, b)); assertFalse(same(a, c)); assertFalse(same(a, clean))
        assertTrue(same(clean, Synthetic.squat { reps = 3 })) // generator itself is deterministic
        assertTrue(same(clean.withNoise(0.0, seed = 1), clean))
        val dx = ArrayList<Double>()
        for (i in clean.frames.indices) for (id in 0 until LandmarkId.COUNT) dx += (a.frames[i][id]!!.x - clean.frames[i][id]!!.x).toDouble()
        val mean = dx.average(); val sd = sqrt(dx.sumOf { (it - mean) * (it - mean) } / dx.size)
        assertNear(0.01 * clean.bodyHeightPx, sd, 0.1 * 0.01 * clean.bodyHeightPx, "noise sigma")
        assertNear(0.0, mean, 0.5, "noise mean")
        assertEquals(clean.truth, a.truth)
    }

    @Test
    fun swapInjectionSwapsLeftAndRightPairs() {
        val clean = Synthetic.squat(view = View.FRONT) { reps = 1 }
        val all = clean.withSwaps(1.0, seed = 3)
        for (i in clean.frames.indices) for ((l, r) in SWAP_PAIRS) {
            assertEquals(clean.frames[i][r], all.frames[i][l]); assertEquals(clean.frames[i][l], all.frames[i][r])
        }
        assertTrue(same(clean, clean.withSwaps(0.0, seed = 3)))
        val some = clean.withSwaps(0.1, seed = 3)
        val swapped = clean.frames.indices.count { clean.frames[it][11] != some.frames[it][11] }
        assertTrue("swapped $swapped of ${clean.frames.size}", swapped in 1 until clean.frames.size / 3)
        assertTrue(same(some, clean.withSwaps(0.1, seed = 3)))
        assertFalse(same(some, clean.withSwaps(0.1, seed = 4)))
        // Nose is not part of a pair.
        assertEquals(clean.frames[5][LandmarkId.NOSE], all.frames[5][LandmarkId.NOSE])
    }

    @Test
    fun dropoutWindowsProduceNullLandmarks() {
        val clean = Synthetic.squat { reps = 3 }
        val d = clean.withDropout(2000, 3000)
        for (f in d.frames) {
            if (f.timestampMs in 2000 until 3000) { assertFalse(f.hasPerson); assertTrue(f.landmarks.all { it == null }) }
            else assertTrue(f.hasPerson && f.landmarks.all { it != null })
        }
        assertTrue(d.frames.any { it.timestampMs in 2000 until 3000 })
        assertEquals(clean.frames.size, d.frames.size)

        val subset = clean.withDropout(1000, 2000, Parts.RIGHT_LEG)
        for (f in subset.frames) for (id in 0 until LandmarkId.COUNT) {
            val inWindow = f.timestampMs in 1000 until 2000
            if (inWindow && id in Parts.RIGHT_LEG) assertNull(f[id]) else assertNotNull(f[id])
        }
    }

    @Test
    fun randomDropoutAndInFrameJitterAreSeeded() {
        val clean = Synthetic.squat { reps = 3 }
        val a = clean.withRandomDropout(0.2, seed = 5)
        val dropped = a.frames.count { !it.hasPerson }
        assertTrue("dropped $dropped of ${a.frames.size}", dropped in a.frames.size / 10..a.frames.size / 3)
        assertTrue(same(a, clean.withRandomDropout(0.2, seed = 5)))
        val pl = clean.withRandomDropout(0.1, seed = 5, perLandmark = true)
        assertTrue(pl.frames.any { f -> f.hasPerson && f.landmarks.any { it == null } })
        val j = clean.withInFrameJitter(0.05, seed = 2)
        assertTrue(j.frames.all { f -> f.landmarks.all { it!!.inFrame in 0f..1f } })
        assertTrue(j.frames.any { f -> f.landmarks.any { it!!.inFrame < 0.99f } })
        assertTrue(same(j, clean.withInFrameJitter(0.05, seed = 2)))
    }

    // --- composition ---------------------------------------------------------------------------------------------

    @Test
    fun sequenceKeepsTimestampsIncreasingAndMergesTruth() {
        val seq = Synthetic.sequence(
            Synthetic.walkIn(),
            Synthetic.standStill(durationMs = 1000),
            Synthetic.squat { reps = 3; override(2) { bottomKneeDeg = 125.0 } },
            Synthetic.bendOverToTouchPhone(),
            Synthetic.stepOut(),
        )
        val t = seq.frames.map { it.timestampMs }
        for (i in 1 until t.size) assertTrue("t[$i]=${t[i]} <= ${t[i - 1]}", t[i] > t[i - 1])
        assertEquals(0L, t.first())
        for (i in 1 until t.size) assertTrue(abs((t[i] - t[i - 1]) - 1000.0 / 30) <= 1.0)
        assertEquals(2, seq.truth.expectedReps); assertEquals(1, seq.truth.expectedPartialReps)
        assertEquals(Kind.SQUAT, seq.truth.kind)
        // Truth times point at the squat's bottom in the combined recording.
        val r = seq.truth.reps[0]
        val squatOnly = Synthetic.squat { reps = 3; override(2) { bottomKneeDeg = 125.0 } }
        val f = at(seq, r.extremeMs)
        assertNear(90.0, kneeAngle(f, Side.RIGHT), 3.0, "knee at truth time in sequence")
        assertTrue(r.extremeMs > squatOnly.truth.reps[0].extremeMs)
        // Modified clips cannot be composed.
        try { Synthetic.sequence(Synthetic.walkIn().withNoise(), Synthetic.stepOut()); fail("expected failure") } catch (_: IllegalArgumentException) {}
    }

    @Test
    fun sequenceTransitionsAreSmooth() {
        val seq = Synthetic.sequence(Synthetic.standStill(1000), Synthetic.squat { reps = 1 }, Synthetic.bendOverToTouchPhone(), Synthetic.standStill(1000))
        var worst = 0.0
        for (i in 1 until seq.frames.size) for (id in 0 until LandmarkId.COUNT) {
            val a = seq.frames[i - 1][id]!!; val b = seq.frames[i][id]!!
            worst = maxOf(worst, hypot((a.x - b.x).toDouble(), (a.y - b.y).toDouble()) / seq.bodyHeightPx)
        }
        assertTrue("max per-frame landmark jump $worst body heights", worst < 0.04)
    }

    @Test
    fun walkInStartsOffscreenAndEndsStanding() {
        val clip = Synthetic.walkIn()
        assertFalse("nobody in the first frame", clip.frames.first().hasPerson)
        val entering = clip.frames.first { it.hasPerson }
        assertTrue(entering.landmarks.any { it != null && it.inFrame < 0.2f })
        val last = clip.frames.last()
        assertTrue(last.landmarks.all { it!!.inFrame > 0.9f })
        assertNear(180.0, kneeAngle(last, Side.RIGHT), 3.0, "standing at the end")
        assertEquals(0, clip.truth.expectedReps)
        // Walking moves feet: knee angle varies mid-walk.
        val mid = clip.frames.filter { it.hasPerson && it.timestampMs in 800..2000 }.map { kneeAngle(it, Side.RIGHT) }
        assertTrue(mid.max() - mid.min() > 15.0)
    }

    @Test
    fun stepOutLeavesTheFrame() {
        val clip = Synthetic.stepOut()
        assertTrue(clip.frames.first().hasPerson)
        assertFalse(clip.frames.last().hasPerson)
    }

    @Test
    fun standStillSwayStaysWithinTheRequestedAmplitude() {
        val clip = Synthetic.standStill(durationMs = 20000, swayDeg = 3.0)
        val knees = clip.frames.map { kneeAngle(it, Side.RIGHT) }
        assertTrue(knees.max() - knees.min() in 2.0..6.5)
        assertTrue(knees.all { abs(it - 175.0) <= 3.5 })
        assertEquals(0, clip.truth.reps.size)
    }

    @Test
    fun distractorPosesAreWhatTheyClaim() {
        val bend = Synthetic.bendOverToTouchPhone()
        val maxLean = bend.frames.maxOf { torsoLean(it, Side.RIGHT) }
        assertTrue("torso near horizontal: $maxLean", maxLean in 78.0..86.0)
        val kneeAtBend = kneeAngle(at(bend, 2800), Side.RIGHT)
        assertTrue("knees slightly bent: $kneeAtBend", kneeAtBend in 145.0..170.0)

        val scratch = Synthetic.scratchHead(Side.RIGHT)
        val f = at(scratch, 2500)
        assertTrue("wrist above shoulder", lm(f, LandmarkId.RIGHT_WRIST).y < lm(f, LandmarkId.RIGHT_SHOULDER).y)
        assertTrue("left arm stays down", lm(f, LandmarkId.LEFT_WRIST).y > lm(f, LandmarkId.LEFT_HIP).y - 20)

        val reach = Synthetic.reachToPhone(View.SIDE)
        val rf = at(reach, 2500)
        val standing = reach.frames.first()
        // Near wrist moves much closer to the camera (smaller z) than at rest.
        assertTrue(lm(rf, LandmarkId.RIGHT_WRIST).z < lm(standing, LandmarkId.RIGHT_WRIST).z - 0.15 * reach.bodyHeightPx)
        val reachFront = Synthetic.reachToPhone(View.FRONT)
        assertTrue(lm(at(reachFront, 2500), LandmarkId.RIGHT_WRIST).z < lm(reachFront.frames.first(), LandmarkId.RIGHT_WRIST).z - 0.15 * reachFront.bodyHeightPx)

        val kneel = Synthetic.kneelAfterPushUps()
        val camL = CameraSpec.landscape()
        val kneelFrame = at(kneel, 4200)
        assertTrue("knee near the floor", lm(kneelFrame, LandmarkId.RIGHT_KNEE).y > camL.floorY - 0.06 * camL.bodyHeightPx)
        assertNear(0.0, torsoLean(kneelFrame, Side.RIGHT), 3.0, "kneeling upright")
        val standFrame = kneel.frames.last()
        assertTrue(lm(standFrame, LandmarkId.RIGHT_SHOULDER).y < camL.floorY - 0.7 * camL.bodyHeightPx)
        for (c in listOf(bend, scratch, reach, kneel)) assertEquals(0, c.truth.expectedReps)
    }

    // --- ground truth -------------------------------------------------------------------------------------------------

    @Test
    fun squatTruthForPartialsAndFaults() {
        val clip = Synthetic.squat {
            reps = 10
            override(4, 7) { bottomKneeDeg = 125.0 }
            override(3..10) { torsoLeanDeg = 60.0; shinLeanDeg = 25.0 }
            override(9) { repDurationMs = 800 }
        }
        val t = clip.truth
        assertEquals(8, t.expectedReps); assertEquals(2, t.expectedPartialReps)
        assertEquals(listOf(4, 7), t.reps.filter { it.partial }.map { it.index })
        assertEquals(emptySet<Fault>(), t.faultsByRep[1]); assertEquals(emptySet<Fault>(), t.faultsByRep[2])
        for (i in 3..10) assertTrue("rep $i", Fault.FORWARD_LEAN in t.faultsByRep[i]!!)
        assertTrue(Fault.FAST_DESCENT in t.faultsByRep[9]!!)
        // Rep times are ordered and inside the clip.
        for ((a, b) in t.reps.zipWithNext()) assertTrue(a.endMs <= b.startMs)
        assertTrue(t.reps.last().endMs <= clip.frames.last().timestampMs)
    }

    @Test
    fun alternatingAndSimultaneousCurlTruth() {
        val both = Synthetic.curl { reps = 10 }
        assertEquals(10, both.truth.expectedReps)
        val alt = Synthetic.curl { reps = 5; mode = CurlMode.ALTERNATING }
        assertEquals(10, alt.truth.expectedReps); assertEquals(5, alt.truth.leftReps); assertEquals(5, alt.truth.rightReps)

        // Alternating: when the left wrist is at its peak, the right arm is lower.
        val l = alt.truth.reps.first { it.side == Side.LEFT }
        val f = at(alt, l.extremeMs)
        val leftLift = lm(f, LandmarkId.LEFT_ELBOW).y - lm(f, LandmarkId.LEFT_WRIST).y
        val rightLift = lm(f, LandmarkId.RIGHT_ELBOW).y - lm(f, LandmarkId.RIGHT_WRIST).y
        assertTrue("left lift $leftLift right lift $rightLift", leftLift > 0.1 * alt.bodyHeightPx && rightLift < 0)
        // Simultaneous: both wrists above the elbows at the peak.
        val fb = at(both, both.truth.reps[0].extremeMs)
        assertTrue(lm(fb, LandmarkId.LEFT_WRIST).y < lm(fb, LandmarkId.LEFT_ELBOW).y && lm(fb, LandmarkId.RIGHT_WRIST).y < lm(fb, LandmarkId.RIGHT_ELBOW).y)
        // Per-arm override.
        val odd = Synthetic.curl { reps = 4; mode = CurlMode.ALTERNATING; overrideArm(Side.RIGHT, 2) { topElbowDeg = 70.0 } }
        assertEquals(3, odd.truth.rightReps); assertEquals(4, odd.truth.leftReps); assertEquals(1, odd.truth.expectedPartialReps)
    }

    @Test
    fun pushUpTruth() {
        val clip = Synthetic.pushUp {
            reps = 10
            override(1..10) { hipSag = 0.1 }
            override(5) { bottomElbowDeg = 115.0 }
            override(8) { lockoutCompleteness = 0.4 }
        }
        assertEquals(8, clip.truth.expectedReps); assertEquals(2, clip.truth.expectedPartialReps)
        assertTrue(clip.truth.reps.all { Fault.HIPS_SAG in it.faults })
        assertTrue(Fault.NO_LOCKOUT in clip.truth.reps[7].faults)
    }
}
