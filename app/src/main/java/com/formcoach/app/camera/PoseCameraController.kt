package com.formcoach.app.camera

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.formcoach.app.coach.PoseFrame
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.PoseDetector
import com.google.mlkit.vision.pose.accurate.AccuratePoseDetectorOptions
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicInteger

enum class CameraFacing { FRONT, BACK }

/** Choose the ML Kit model. Keep accurate if it reaches 15 fps or more on the target phone (see the FPS log). */
const val USE_ACCURATE_POSE_MODEL = true

private const val TAG = "PoseCamera"
private const val FPS_LOG_INTERVAL_MS = 3_000L

/**
 * CameraX Preview + ImageAnalysis feeding ML Kit pose detection.
 *
 * Does not hold the Activity: the lifecycle owner and surface provider are handed in at [bind] time and
 * dropped at [unbind]. Frames are delivered on [executor] (the single analysis thread).
 */
class PoseCameraController(context: Context) {
    private val appContext = context.applicationContext

    /** The analysis thread. The ViewModel also runs engine commands here so the engine sees one thread. */
    val executor: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "pose-analysis") }

    private val bindingCounter = AtomicInteger(0)
    @Volatile private var activeBinding = 0

    private val detectorLock = Any()
    private var detector: PoseDetector? = null
    private var provider: ProcessCameraProvider? = null
    private var preview: Preview? = null

    // Only touched on the analysis thread.
    private var fpsFrames = 0
    private var fpsWindowStartMs = 0L

    private fun detector(): PoseDetector = synchronized(detectorLock) {
        detector ?: createDetector().also { detector = it }
    }

    private fun createDetector(): PoseDetector =
        if (USE_ACCURATE_POSE_MODEL) {
            PoseDetection.getClient(
                AccuratePoseDetectorOptions.Builder()
                    .setDetectorMode(AccuratePoseDetectorOptions.STREAM_MODE)
                    .build()
            )
        } else {
            PoseDetection.getClient(
                PoseDetectorOptions.Builder().setDetectorMode(PoseDetectorOptions.STREAM_MODE).build()
            )
        }

    /** Main thread only. Replaces any earlier binding. */
    fun bind(
        owner: LifecycleOwner,
        surfaceProvider: Preview.SurfaceProvider,
        facing: CameraFacing,
        onFrame: (PoseFrame) -> Unit,
        onError: (String) -> Unit,
    ) {
        val id = bindingCounter.incrementAndGet()
        activeBinding = id
        val providerFuture = ProcessCameraProvider.getInstance(appContext)
        providerFuture.addListener({
            if (activeBinding != id) return@addListener // unbound or rebound while the provider was loading
            try {
                val cameraProvider = providerFuture.get()
                provider = cameraProvider
                val selector = if (facing == CameraFacing.FRONT) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                if (!cameraProvider.hasCamera(selector)) {
                    onError("That camera is not available on this device.")
                    return@addListener
                }
                val resolution = ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy(AspectRatio.RATIO_4_3, AspectRatioStrategy.FALLBACK_RULE_AUTO))
                    .build()
                val newPreview = Preview.Builder().setResolutionSelector(resolution).build()
                newPreview.setSurfaceProvider(surfaceProvider)
                val analysis = ImageAnalysis.Builder()
                    .setResolutionSelector(resolution)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(executor) { proxy -> analyze(proxy, id, onFrame) }
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(owner, selector, newPreview, analysis)
                preview = newPreview
            } catch (e: Exception) {
                Log.w(TAG, "Unable to start camera", e)
                onError("Unable to start camera. Try again.")
            }
        }, ContextCompat.getMainExecutor(appContext))
    }

    /** Main thread only. Results of the current binding still in flight are dropped. */
    fun unbind() {
        activeBinding = bindingCounter.incrementAndGet()
        preview?.setSurfaceProvider(null)
        preview = null
        provider?.unbindAll()
    }

    /** Main thread only. The controller cannot be used afterwards. */
    fun close() {
        unbind()
        try {
            executor.execute { synchronized(detectorLock) { detector?.close(); detector = null } }
        } catch (_: RejectedExecutionException) {
        }
        executor.shutdown()
    }

    private fun analyze(proxy: ImageProxy, bindingId: Int, onFrame: (PoseFrame) -> Unit) {
        try {
            val rotation = proxy.imageInfo.rotationDegrees
            val sideways = rotation == 90 || rotation == 270
            val width = if (sideways) proxy.height else proxy.width
            val height = if (sideways) proxy.width else proxy.height
            val timestampMs = proxy.imageInfo.timestamp / 1_000_000
            val frame = try {
                val media = proxy.image
                if (media == null) {
                    PoseFrame.empty(timestampMs, width, height)
                } else {
                    val pose = Tasks.await(detector().process(InputImage.fromMediaImage(media, rotation)))
                    PoseFrameMapper.map(pose, timestampMs, width, height)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Pose detection failed", e)
                PoseFrame.empty(timestampMs, width, height)
            }
            logFps()
            if (bindingId != activeBinding) return
            try {
                onFrame(frame)
            } catch (e: Exception) {
                Log.e(TAG, "Frame handler failed", e)
            }
        } finally {
            proxy.close()
        }
    }

    private fun logFps() {
        val now = SystemClock.elapsedRealtime()
        if (fpsWindowStartMs == 0L) fpsWindowStartMs = now
        fpsFrames++
        val elapsed = now - fpsWindowStartMs
        if (elapsed >= FPS_LOG_INTERVAL_MS) {
            Log.d(TAG, "pose fps=%.1f model=%s".format(fpsFrames * 1000f / elapsed, if (USE_ACCURATE_POSE_MODEL) "accurate" else "base"))
            fpsFrames = 0
            fpsWindowStartMs = now
        }
    }
}
