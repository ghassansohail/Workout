package com.formcoach.app

import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.Preview
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.viewModelScope
import com.formcoach.app.camera.CameraFacing
import com.formcoach.app.camera.PoseCameraController
import com.formcoach.app.coach.EngineOutput
import com.formcoach.app.coach.Exercise
import com.formcoach.app.coach.PoseFrame
import com.formcoach.app.coach.SessionCommand
import com.formcoach.app.coach.Speaker
import com.formcoach.app.coach.SpeechPolicy
import com.formcoach.app.coach.SpeechScheduler
import com.formcoach.app.debug.PoseRecorder
import com.formcoach.app.voice.VoiceCoach
import com.formcoach.app.coach.WorkoutEngine
import com.formcoach.app.coach.WorkoutSession
import com.formcoach.app.coach.WorkoutUiState
import com.formcoach.app.ui.isSetActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.RejectedExecutionException

// ---------------------------------------------------------------------------------------------
// Factory functions. The integrator swaps one line in each to wire the real implementations.
// ---------------------------------------------------------------------------------------------

internal fun createEngine(): WorkoutEngine = WorkoutSession()

internal fun createSpeechScheduler(): SpeechScheduler = SpeechPolicy()

internal fun createSpeaker(context: Context): VoiceCoach = VoiceCoach(context)

internal fun createRecorder(context: Context): FrameRecorder {
    val recorder = PoseRecorder(context)
    return object : FrameRecorder {
        override val isRecording: Boolean get() = recorder.isRecording
        override fun start(exercise: Exercise) = recorder.start(exercise)
        override fun record(frame: PoseFrame) = recorder.record(frame)
        override fun stop(): File? = recorder.stop()
    }
}

/** Implemented by debug/PoseRecorder. Called only from the camera analysis thread. */
interface FrameRecorder {
    val isRecording: Boolean
    fun start(exercise: Exercise)
    fun record(frame: PoseFrame)
    fun stop(): File?
}

// ---------------------------------------------------------------------------------------------
// ViewModel
// ---------------------------------------------------------------------------------------------

private const val TAG = "WorkoutViewModel"
private const val TICK_MS = 100L

/**
 * Owns the engine, speech scheduler, speaker, recorder and camera controller so that rotation and
 * other activity recreation keep every piece of workout state.
 *
 * Threading: [WorkoutEngine] and [FrameRecorder] are touched only on the camera analysis thread
 * ([PoseCameraController.executor]). The scheduler and speaker are touched only on the main thread.
 */
class WorkoutViewModel(application: Application) : AndroidViewModel(application) {
    private val engine: WorkoutEngine = createEngine()
    private val scheduler: SpeechScheduler = createSpeechScheduler()
    private val speaker: VoiceCoach = createSpeaker(application)
    private val camera = PoseCameraController(application)

    private val debuggable =
        (application.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    private val recorder: FrameRecorder? = if (debuggable) createRecorder(application) else null

    private val mainHandler = Handler(Looper.getMainLooper())

    private val _uiState = MutableStateFlow(WorkoutUiState())
    val uiState: StateFlow<WorkoutUiState> = _uiState.asStateFlow()

    private val _facing = MutableStateFlow(CameraFacing.FRONT)
    val facing: StateFlow<CameraFacing> = _facing.asStateFlow()

    private val _muted = MutableStateFlow(false)
    val muted: StateFlow<Boolean> = _muted.asStateFlow()

    private val _cameraError = MutableStateFlow<String?>(null)
    val cameraError: StateFlow<String?> = _cameraError.asStateFlow()

    private val _recording = MutableStateFlow(false)
    val recording: StateFlow<Boolean> = _recording.asStateFlow()

    /** Name of the last finished recording, for display. */
    private val _lastRecording = MutableStateFlow<String?>(null)
    val lastRecording: StateFlow<String?> = _lastRecording.asStateFlow()

    /** True only for debuggable builds where a recorder exists. */
    val recorderAvailable: Boolean get() = recorder != null

    @Volatile private var latestFrameMs = 0L
    @Volatile private var recordingOn = false

    init {
        viewModelScope.launch {
            while (true) {
                scheduler.onTick(SystemClock.elapsedRealtime(), speaker)
                delay(TICK_MS)
            }
        }
    }

    // ---- camera ----

    /** Main thread. Call when the camera screen is visible; pair with [unbindCamera]. */
    fun bindCamera(owner: LifecycleOwner, surfaceProvider: Preview.SurfaceProvider) {
        _cameraError.value = null
        camera.bind(
            owner = owner,
            surfaceProvider = surfaceProvider,
            facing = _facing.value,
            onFrame = ::onFrame,
            onError = { message -> _cameraError.value = message },
        )
    }

    fun unbindCamera() = camera.unbind()

    fun flipCamera() {
        if (isSetActive(_uiState.value.phase)) return
        _facing.update { if (it == CameraFacing.FRONT) CameraFacing.BACK else CameraFacing.FRONT }
    }

    // ---- commands ----

    fun startSet() = send(SessionCommand.StartSet)

    fun stopSet() = send(SessionCommand.StopSet)

    fun selectExercise(exercise: Exercise) {
        if (isSetActive(_uiState.value.phase)) return
        send(SessionCommand.SelectExercise(exercise))
    }

    fun setMuted(value: Boolean) {
        _muted.value = value
        scheduler.muted = value
        if (value) speaker.stopAll()
    }

    fun toggleMuted() = setMuted(!_muted.value)

    fun setRecording(on: Boolean) {
        val rec = recorder ?: return
        runOnAnalysis {
            if (on && !rec.isRecording) {
                rec.start(_uiState.value.exercise)
                recordingOn = true
                _recording.value = true
            } else if (!on && rec.isRecording) {
                recordingOn = false
                _recording.value = false
                _lastRecording.value = rec.stop()?.name
            }
        }
    }

    private fun send(command: SessionCommand) {
        runOnAnalysis {
            val out = engine.onCommand(command, latestFrameMs)
            publish(out)
        }
    }

    private fun runOnAnalysis(block: () -> Unit) {
        try {
            camera.executor.execute {
                try {
                    block()
                } catch (e: Exception) {
                    Log.e(TAG, "Command failed", e)
                }
            }
        } catch (_: RejectedExecutionException) {
            // Cleared; nothing to do.
        }
    }

    // ---- analysis thread ----

    private fun onFrame(frame: PoseFrame) {
        latestFrameMs = frame.timestampMs
        val rec = recorder
        if (rec != null && recordingOn && rec.isRecording) rec.record(frame)
        publish(engine.onFrame(frame))
    }

    private fun publish(out: EngineOutput) {
        _uiState.value = out.state
        if (out.events.isNotEmpty()) {
            val events = out.events
            mainHandler.post { scheduler.onEvents(events, SystemClock.elapsedRealtime()) }
        }
    }

    // ---- lifecycle ----

    override fun onCleared() {
        mainHandler.removeCallbacksAndMessages(null)
        // The recorder belongs to the analysis thread; finish the recording there before it shuts down.
        recorder?.let { rec ->
            try {
                camera.executor.execute { if (rec.isRecording) rec.stop() }
            } catch (_: RejectedExecutionException) {
                if (rec.isRecording) rec.stop()
            }
        }
        camera.close()
        speaker.stopAll()
        speaker.shutdown()
    }
}
