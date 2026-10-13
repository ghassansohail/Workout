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
import com.google.mlkit.vision.pose.PoseDetectorOptions
import com.google.mlkit.vision.pose.PoseLandmark
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.acos
import kotlin.math.sqrt

enum class Exercise(val label: String) {
    SQUAT("Squat"), PUSH_UP("Push-up"), CURL("Biceps curl")
}

data class CoachResult(val reps: Int, val angle: String, val feedback: String)

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

    fun start(owner: LifecycleOwner, exercise: Exercise, onResult: (CoachResult) -> Unit) {
        coach = RepCoach()
        val providerFuture = ProcessCameraProvider.getInstance(appContext)
        providerFuture.addListener({
            val provider = providerFuture.get()
            cameraProvider = provider
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val analysis = ImageAnalysis.Builder()
                .setTargetResolution(Size(640, 480))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(cameraExecutor) { proxy -> analyze(proxy, exercise, onResult) }
            try {
                provider.unbindAll()
                provider.bindToLifecycle(owner, CameraSelector.DEFAULT_FRONT_CAMERA, preview, analysis)
            } catch (_: Exception) {
                onResult(CoachResult(0, "--°", "Unable to start camera. Try again."))
            }
        }, ContextCompat.getMainExecutor(appContext))
    }

    private fun analyze(proxy: ImageProxy, exercise: Exercise, onResult: (CoachResult) -> Unit) {
        val mediaImage = proxy.image
        if (mediaImage == null) {
            proxy.close()
            return
        }
        val image = InputImage.fromMediaImage(mediaImage, proxy.imageInfo.rotationDegrees)
        detector.process(image)
            .addOnSuccessListener { pose -> onResult(coach.update(pose, exercise)) }
            .addOnFailureListener { onResult(CoachResult(coach.reps, "--°", "Tracking paused—adjust your position")) }
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
    private var phase = "ready"

    fun update(pose: Pose, exercise: Exercise): CoachResult {
        val landmarks = pose.allPoseLandmarks
        if (landmarks.isEmpty()) return CoachResult(reps, "--°", "Step back until your full body is visible")

        val side = chooseSide(pose, exercise)
        val jointAngle = when (exercise) {
            Exercise.SQUAT -> angle(side.point(pose, PoseLandmark.LEFT_HIP, PoseLandmark.RIGHT_HIP),
                side.point(pose, PoseLandmark.LEFT_KNEE, PoseLandmark.RIGHT_KNEE), side.point(pose, PoseLandmark.LEFT_ANKLE, PoseLandmark.RIGHT_ANKLE))
            Exercise.PUSH_UP -> angle(side.point(pose, PoseLandmark.LEFT_SHOULDER, PoseLandmark.RIGHT_SHOULDER),
                side.point(pose, PoseLandmark.LEFT_ELBOW, PoseLandmark.RIGHT_ELBOW), side.point(pose, PoseLandmark.LEFT_WRIST, PoseLandmark.RIGHT_WRIST))
            Exercise.CURL -> angle(side.point(pose, PoseLandmark.LEFT_SHOULDER, PoseLandmark.RIGHT_SHOULDER),
                side.point(pose, PoseLandmark.LEFT_ELBOW, PoseLandmark.RIGHT_ELBOW), side.point(pose, PoseLandmark.LEFT_WRIST, PoseLandmark.RIGHT_WRIST))
        }
        if (jointAngle == null) return CoachResult(reps, "--°", "Turn slightly sideways so I can see your joints")

        val cue = when (exercise) {
            Exercise.SQUAT -> squatCue(pose, side, jointAngle)
            Exercise.PUSH_UP -> pushupCue(pose, side, jointAngle)
            Exercise.CURL -> curlCue(pose, side, jointAngle)
        }
        return CoachResult(reps, "${jointAngle.toInt()}°", cue)
    }

    private fun squatCue(pose: Pose, side: Side, knee: Double): String {
        val trunk = angle(side.point(pose, PoseLandmark.LEFT_SHOULDER, PoseLandmark.RIGHT_SHOULDER),
            side.point(pose, PoseLandmark.LEFT_HIP, PoseLandmark.RIGHT_HIP), side.point(pose, PoseLandmark.LEFT_KNEE, PoseLandmark.RIGHT_KNEE))
        if (trunk != null && trunk < 105) return "Keep your chest lifted"
        if (knee < 100 && phase == "up") {
            phase = "down"
            return "Good depth—press through your feet"
        }
        if (knee > 160) {
            val completedRep = phase == "down"
            if (completedRep) reps++
            phase = "up"
            return if (completedRep) "Nice rep. Keep your knees tracking over your feet" else "Stand tall, then sit your hips back"
        }
        return if (knee < 125) "Good depth—drive back up" else "Lower with control"
    }

    private fun pushupCue(pose: Pose, side: Side, elbow: Double): String {
        val line = angle(side.point(pose, PoseLandmark.LEFT_SHOULDER, PoseLandmark.RIGHT_SHOULDER),
            side.point(pose, PoseLandmark.LEFT_HIP, PoseLandmark.RIGHT_HIP), side.point(pose, PoseLandmark.LEFT_ANKLE, PoseLandmark.RIGHT_ANKLE))
        if (line != null && line < 155) return "Keep your body in one straight line"
        if (elbow < 95 && phase == "up") {
            phase = "down"
            return "Good lower position—push the floor away"
        }
        if (elbow > 150 && phase == "down") {
            reps++
            phase = "up"
            return "Good rep. Keep your core braced"
        }
        if (elbow > 150) phase = "up"
        return if (elbow < 115) "Press up with control" else "Lower until your elbows bend"
    }

    private fun curlCue(pose: Pose, side: Side, elbow: Double): String {
        if (elbow < 55 && phase == "down") {
            phase = "up"
            return "Squeeze at the top, then lower slowly"
        }
        if (elbow > 155 && phase == "up") {
            reps++
            phase = "down"
            return "Good rep. Keep your upper arm still"
        }
        if (elbow > 155) phase = "down"
        return if (elbow < 80) "Pause and squeeze your biceps" else "Curl smoothly without swinging"
    }

    private fun chooseSide(pose: Pose, exercise: Exercise): Side {
        val landmarks = when (exercise) {
            Exercise.SQUAT -> listOf(PoseLandmark.LEFT_HIP to PoseLandmark.RIGHT_HIP, PoseLandmark.LEFT_KNEE to PoseLandmark.RIGHT_KNEE, PoseLandmark.LEFT_ANKLE to PoseLandmark.RIGHT_ANKLE)
            Exercise.PUSH_UP -> listOf(PoseLandmark.LEFT_SHOULDER to PoseLandmark.RIGHT_SHOULDER, PoseLandmark.LEFT_ELBOW to PoseLandmark.RIGHT_ELBOW, PoseLandmark.LEFT_WRIST to PoseLandmark.RIGHT_WRIST)
            Exercise.CURL -> listOf(PoseLandmark.LEFT_SHOULDER to PoseLandmark.RIGHT_SHOULDER, PoseLandmark.LEFT_ELBOW to PoseLandmark.RIGHT_ELBOW, PoseLandmark.LEFT_WRIST to PoseLandmark.RIGHT_WRIST)
        }
        val leftScore = landmarks.fold(0f) { score, pair -> score + (pose.getPoseLandmark(pair.first)?.inFrameLikelihood ?: 0f) }
        val rightScore = landmarks.fold(0f) { score, pair -> score + (pose.getPoseLandmark(pair.second)?.inFrameLikelihood ?: 0f) }
        return if (leftScore >= rightScore) Side.LEFT else Side.RIGHT
    }

    private enum class Side {
        LEFT, RIGHT
    }

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
