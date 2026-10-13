package com.formcoach.app

import android.content.Context
import android.util.Size
import android.view.ViewGroup
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.PoseDetector
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions
import com.google.mlkit.vision.pose.PoseLandmark
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.sqrt

enum class Exercise(val label: String) {
    SQUAT("Squat"), PUSH_UP("Push-up"), CURL("Biceps curl")
}

enum class CameraFacing { FRONT, BACK }

data class CoachResult(
    val reps: Int,
    val angle: String,
    val feedback: String,
    val voiceCue: String? = null,
    val personDetected: Boolean = true
)

/** Owns the local camera stream and on-device pose detector for the active screen. */
class PoseCameraController(context: Context) {
    val previewView = PreviewView(context).apply {
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        scaleType = PreviewView.ScaleType.FILL_CENTER
        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
    }

    private val appContext = context.applicationContext
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val detector: PoseDetector = PoseDetection.getClient(
        PoseDetectorOptions.Builder().setDetectorMode(PoseDetectorOptions.STREAM_MODE).build()
    )
    private var cameraProvider: ProcessCameraProvider? = null
    private var coach = RepCoach()

    fun start(owner: LifecycleOwner, exercise: Exercise, cameraFacing: CameraFacing, onResult: (CoachResult) -> Unit) {
        coach = RepCoach()
        val providerFuture = ProcessCameraProvider.getInstance(appContext)
        providerFuture.addListener({
            val provider = providerFuture.get()
            cameraProvider = provider
            val cameraSelector = if (cameraFacing == CameraFacing.FRONT) {
                CameraSelector.DEFAULT_FRONT_CAMERA
            } else {
                CameraSelector.DEFAULT_BACK_CAMERA
            }
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val analysis = ImageAnalysis.Builder()
                .setTargetResolution(Size(640, 480))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(cameraExecutor) { proxy -> analyze(proxy, exercise, onResult) }
            try {
                provider.unbindAll()
                if (!provider.hasCamera(cameraSelector)) {
                    onResult(CoachResult(0, "--°", "That camera is not available on this device.", personDetected = false))
                    return@addListener
                }
                provider.bindToLifecycle(owner, cameraSelector, preview, analysis)
            } catch (_: Exception) {
                onResult(CoachResult(0, "--°", "Unable to start camera. Try again.", personDetected = false))
            }
        }, ContextCompat.getMainExecutor(appContext))
    }

    private fun analyze(proxy: ImageProxy, exercise: Exercise, onResult: (CoachResult) -> Unit) {
        val mediaImage = proxy.image
        if (mediaImage == null) {
            proxy.close()
            onResult(CoachResult(coach.reps, "--°", "Move fully into view. Coaching paused.", personDetected = false))
            return
        }
        val image = InputImage.fromMediaImage(mediaImage, proxy.imageInfo.rotationDegrees)
        detector.process(image)
            .addOnSuccessListener { pose -> onResult(coach.update(pose, exercise)) }
            .addOnFailureListener {
                onResult(CoachResult(coach.reps, "--°", "Move fully into view. Coaching paused.", personDetected = false))
            }
            .addOnCompleteListener { proxy.close() }
    }

    fun stop() {
        cameraProvider?.unbindAll()
    }

    fun dispose() {
        stop()
        detector.close()
        cameraExecutor.shutdown()
    }
}

private class RepCoach {
    var reps = 0
        private set

    private enum class Stage { SETUP, WAITING, ACTIVE }

    private var stage = Stage.SETUP
    private var readySince = 0L
    private var waitingSince = 0L
    private var lastWaitReminderAt = 0L
    private var lastSetupPrompt: String? = null
    private var activeFault: String? = null
    private var descended = false
    private var reachedDepth = false
    private var warnedAboutDepth = false
    private var lastJointAngle: Double? = null

    fun update(pose: Pose, exercise: Exercise): CoachResult {
        val side = chooseSide(pose, exercise)
        if (!hasReliablePerson(pose, side, exercise)) {
            stage = Stage.SETUP
            readySince = 0L
            descended = false
            reachedDepth = false
            warnedAboutDepth = false
            activeFault = null
            lastSetupPrompt = null
            return CoachResult(reps, "--°", "Move fully into view. Coaching paused.", personDetected = false)
        }

        val jointAngle = when (exercise) {
            Exercise.SQUAT -> angle(
                side.point(pose, PoseLandmark.LEFT_HIP, PoseLandmark.RIGHT_HIP),
                side.point(pose, PoseLandmark.LEFT_KNEE, PoseLandmark.RIGHT_KNEE),
                side.point(pose, PoseLandmark.LEFT_ANKLE, PoseLandmark.RIGHT_ANKLE)
            )
            Exercise.PUSH_UP, Exercise.CURL -> angle(
                side.point(pose, PoseLandmark.LEFT_SHOULDER, PoseLandmark.RIGHT_SHOULDER),
                side.point(pose, PoseLandmark.LEFT_ELBOW, PoseLandmark.RIGHT_ELBOW),
                side.point(pose, PoseLandmark.LEFT_WRIST, PoseLandmark.RIGHT_WRIST)
            )
        } ?: return setupPrompt("Turn sideways so I can see your joints")

        val trunkAngle = if (exercise == Exercise.SQUAT || exercise == Exercise.PUSH_UP) {
            angle(
                side.point(pose, PoseLandmark.LEFT_SHOULDER, PoseLandmark.RIGHT_SHOULDER),
                side.point(pose, PoseLandmark.LEFT_HIP, PoseLandmark.RIGHT_HIP),
                side.point(pose, PoseLandmark.LEFT_ANKLE, PoseLandmark.RIGHT_ANKLE)
            )
        } else null
        val shoulderHandOffset = if (exercise == Exercise.PUSH_UP) {
            val shoulder = side.point(pose, PoseLandmark.LEFT_SHOULDER, PoseLandmark.RIGHT_SHOULDER)
            val wrist = side.point(pose, PoseLandmark.LEFT_WRIST, PoseLandmark.RIGHT_WRIST)
            val hip = side.point(pose, PoseLandmark.LEFT_HIP, PoseLandmark.RIGHT_HIP)
            if (shoulder != null && wrist != null && hip != null) {
                abs(shoulder.x - wrist.x) / distance(shoulder, hip)
            } else null
        } else null
        val curlElbowOffset = if (exercise == Exercise.CURL) {
            val shoulder = side.point(pose, PoseLandmark.LEFT_SHOULDER, PoseLandmark.RIGHT_SHOULDER)
            val elbow = side.point(pose, PoseLandmark.LEFT_ELBOW, PoseLandmark.RIGHT_ELBOW)
            val hip = side.point(pose, PoseLandmark.LEFT_HIP, PoseLandmark.RIGHT_HIP)
            if (shoulder != null && elbow != null && hip != null) {
                abs(elbow.x - hip.x) / distance(shoulder, hip)
            } else null
        } else null
        val setupError = when (exercise) {
            Exercise.SQUAT -> when {
                trunkAngle == null -> "Step back so your shoulders, hips, and ankles are visible"
                headCue(pose, side) != null -> headCue(pose, side)
                trunkAngle < 105 -> "Stand tall and keep your chest lifted"
                else -> null
            }
            Exercise.PUSH_UP -> when {
                trunkAngle == null -> "Step back so your shoulders, hips, and ankles are visible"
                headCue(pose, side) != null -> headCue(pose, side)
                shoulderHandOffset != null && shoulderHandOffset > 0.35 -> "Place your hands under your shoulders"
                trunkAngle < 150 -> "Make a straight line from shoulders to ankles"
                else -> null
            }
            Exercise.CURL -> when {
                curlElbowOffset != null && curlElbowOffset > 0.35 -> "Keep your upper arm close to your side"
                else -> null
            }
        }
        val startAngle = if (exercise == Exercise.SQUAT) 155.0 else 150.0
        val ready = jointAngle >= startAngle && setupError == null
        val now = android.os.SystemClock.elapsedRealtime()
        val angleText = "${jointAngle.toInt()}°"

        if (stage == Stage.SETUP) {
            if (!ready) {
                readySince = 0L
                val instruction = setupError ?: when (exercise) {
                    Exercise.SQUAT -> "Stand tall with feet shoulder-width apart; keep knees over toes"
                    Exercise.PUSH_UP -> "Start in a straight-arm plank position"
                    Exercise.CURL -> "Stand tall with your arms by your sides"
                }
                return setupPrompt(instruction, angleText)
            }
            if (readySince == 0L) readySince = now
            if (now - readySince < 500L) return CoachResult(reps, angleText, "Getting into position…")

            stage = Stage.WAITING
            waitingSince = now
            lastWaitReminderAt = 0L
            lastSetupPrompt = null
            lastJointAngle = jointAngle
            val cue = when (exercise) {
                Exercise.SQUAT -> "Ready. Keep your knees over your toes and head neutral. Start your squat when you're set."
                Exercise.PUSH_UP -> "Ready. Keep elbows angled back and head neutral. Start your push-up when you're set."
                Exercise.CURL -> "Ready. Keep your upper arms still. Start your curl when you're set."
            }
            return CoachResult(reps, angleText, cue, cue)
        }

        if (stage == Stage.WAITING) {
            if (setupError != null) {
                stage = Stage.SETUP
                readySince = 0L
                return setupPrompt(setupError, angleText)
            }
            if (jointAngle < startAngle - 16) {
                stage = Stage.ACTIVE
                descended = true
                reachedDepth = jointAngle <= depthThreshold(exercise)
                warnedAboutDepth = false
                activeFault = null
                lastJointAngle = jointAngle
                return CoachResult(reps, angleText, "")
            }
            if (now - waitingSince >= 5_000L && (lastWaitReminderAt == 0L || now - lastWaitReminderAt >= 10_000L)) {
                lastWaitReminderAt = now
                val cue = "Whenever you're ready, begin your first rep."
                return CoachResult(reps, angleText, cue, cue)
            }
            return CoachResult(reps, angleText, "Ready when you are")
        }

        if (setupError != null) return activeWarning(setupError, angleText)
        activeFault = null
        val previousAngle = lastJointAngle ?: jointAngle
        lastJointAngle = jointAngle
        if (jointAngle <= depthThreshold(exercise)) reachedDepth = true
        if (jointAngle < startAngle) descended = true

        val risingTooSoon = descended && !reachedDepth && jointAngle > previousAngle + 4
        if (risingTooSoon && !warnedAboutDepth) {
            warnedAboutDepth = true
            val warning = when (exercise) {
                Exercise.SQUAT -> "Lower a little more before standing"
                Exercise.PUSH_UP -> "Lower until your elbows bend more"
                Exercise.CURL -> "Curl higher before lowering"
            }
            return activeWarning(warning, angleText)
        }

        if (jointAngle >= startAngle) {
            if (descended && reachedDepth) reps++
            descended = false
            reachedDepth = false
            warnedAboutDepth = false
            lastJointAngle = jointAngle
        }
        // Correct movement is intentionally silent. The rep counter and live angle remain visible.
        return CoachResult(reps, angleText, "")
    }

    private fun depthThreshold(exercise: Exercise) = when (exercise) {
        Exercise.SQUAT -> 105.0
        Exercise.PUSH_UP -> 95.0
        Exercise.CURL -> 60.0
    }

    private fun setupPrompt(text: String, angle: String = "--°"): CoachResult {
        val voiceCue = if (text != lastSetupPrompt) text else null
        lastSetupPrompt = text
        return CoachResult(reps, angle, text, voiceCue)
    }

    private fun activeWarning(text: String, angle: String): CoachResult {
        val voiceCue = if (text != activeFault) text else null
        activeFault = text
        return CoachResult(reps, angle, text, voiceCue)
    }

    private fun hasReliablePerson(pose: Pose, side: Side, exercise: Exercise): Boolean {
        val required = when (exercise) {
            Exercise.SQUAT -> listOf(
                PoseLandmark.LEFT_SHOULDER to PoseLandmark.RIGHT_SHOULDER,
                PoseLandmark.LEFT_HIP to PoseLandmark.RIGHT_HIP,
                PoseLandmark.LEFT_KNEE to PoseLandmark.RIGHT_KNEE,
                PoseLandmark.LEFT_ANKLE to PoseLandmark.RIGHT_ANKLE
            )
            Exercise.PUSH_UP -> listOf(
                PoseLandmark.LEFT_SHOULDER to PoseLandmark.RIGHT_SHOULDER,
                PoseLandmark.LEFT_ELBOW to PoseLandmark.RIGHT_ELBOW,
                PoseLandmark.LEFT_WRIST to PoseLandmark.RIGHT_WRIST,
                PoseLandmark.LEFT_HIP to PoseLandmark.RIGHT_HIP,
                PoseLandmark.LEFT_ANKLE to PoseLandmark.RIGHT_ANKLE
            )
            Exercise.CURL -> listOf(
                PoseLandmark.LEFT_SHOULDER to PoseLandmark.RIGHT_SHOULDER,
                PoseLandmark.LEFT_ELBOW to PoseLandmark.RIGHT_ELBOW,
                PoseLandmark.LEFT_WRIST to PoseLandmark.RIGHT_WRIST
            )
        }
        return required.all { (left, right) ->
            val point = pose.getPoseLandmark(if (side == Side.LEFT) left else right)
            point != null && point.inFrameLikelihood >= 0.55f
        }
    }

    private fun headCue(pose: Pose, side: Side): String? {
        val ear = side.point(pose, PoseLandmark.LEFT_EAR, PoseLandmark.RIGHT_EAR) ?: return null
        val shoulder = side.point(pose, PoseLandmark.LEFT_SHOULDER, PoseLandmark.RIGHT_SHOULDER) ?: return null
        val hip = side.point(pose, PoseLandmark.LEFT_HIP, PoseLandmark.RIGHT_HIP) ?: return null
        val alignment = angle(ear, shoulder, hip) ?: return null
        return if (alignment < 145) "Keep your head in line with your spine; look slightly ahead" else null
    }

    private fun distance(a: Point, b: Point): Double =
        sqrt((a.x - b.x) * (a.x - b.x) + (a.y - b.y) * (a.y - b.y)).coerceAtLeast(0.0001)

    private fun chooseSide(pose: Pose, exercise: Exercise): Side {
        val landmarks = when (exercise) {
            Exercise.SQUAT -> listOf(PoseLandmark.LEFT_HIP to PoseLandmark.RIGHT_HIP, PoseLandmark.LEFT_KNEE to PoseLandmark.RIGHT_KNEE, PoseLandmark.LEFT_ANKLE to PoseLandmark.RIGHT_ANKLE)
            Exercise.PUSH_UP, Exercise.CURL -> listOf(PoseLandmark.LEFT_SHOULDER to PoseLandmark.RIGHT_SHOULDER, PoseLandmark.LEFT_ELBOW to PoseLandmark.RIGHT_ELBOW, PoseLandmark.LEFT_WRIST to PoseLandmark.RIGHT_WRIST)
        }
        val leftScore = landmarks.fold(0f) { score, pair -> score + (pose.getPoseLandmark(pair.first)?.inFrameLikelihood ?: 0f) }
        val rightScore = landmarks.fold(0f) { score, pair -> score + (pose.getPoseLandmark(pair.second)?.inFrameLikelihood ?: 0f) }
        return if (leftScore >= rightScore) Side.LEFT else Side.RIGHT
    }

    private enum class Side { LEFT, RIGHT }

    private fun Side.point(pose: Pose, left: Int, right: Int): Point? {
        val landmark = pose.getPoseLandmark(if (this == Side.LEFT) left else right) ?: return null
        if (landmark.inFrameLikelihood < 0.35f) return null
        return Point(landmark.position.x.toDouble(), landmark.position.y.toDouble())
    }

    private fun angle(a: Point?, b: Point?, c: Point?): Double? {
        if (a == null || b == null || c == null) return null
        val abx = a.x - b.x
        val aby = a.y - b.y
        val cbx = c.x - b.x
        val cby = c.y - b.y
        val denom = sqrt((abx * abx + aby * aby) * (cbx * cbx + cby * cby))
        if (denom < 0.0001) return null
        val cosine = ((abx * cbx + aby * cby) / denom).coerceIn(-1.0, 1.0)
        return Math.toDegrees(acos(cosine))
    }

    private data class Point(val x: Double, val y: Double)
}
