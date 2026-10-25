package com.formcoach.app.ui

import android.os.SystemClock
import android.view.ViewGroup
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.FlipCameraAndroid
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.formcoach.app.WorkoutViewModel
import com.formcoach.app.camera.CameraFacing
import com.formcoach.app.coach.Exercise
import com.formcoach.app.coach.SessionPhase
import com.formcoach.app.coach.SetSummary
import com.formcoach.app.coach.WorkoutUiState
import kotlinx.coroutines.delay

/** The camera screen: preview, skeleton overlay, counter, controls. Layout adapts to orientation. */
@Composable
fun WorkoutScreen(viewModel: WorkoutViewModel, cameraGranted: Boolean, onRequestCamera: () -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val facing by viewModel.facing.collectAsStateWithLifecycle()
    val muted by viewModel.muted.collectAsStateWithLifecycle()
    val cameraError by viewModel.cameraError.collectAsStateWithLifecycle()
    val recording by viewModel.recording.collectAsStateWithLifecycle()
    val lastRecording by viewModel.lastRecording.collectAsStateWithLifecycle()
    val landscape = LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    val active = isSetActive(state.phase)

    val camera: @Composable (Modifier) -> Unit = { modifier ->
        CameraPanel(
            state = state,
            facing = facing,
            cameraGranted = cameraGranted,
            cameraError = cameraError,
            flipEnabled = !active,
            viewModel = viewModel,
            onRequestCamera = onRequestCamera,
            modifier = modifier,
        )
    }
    val controls: @Composable () -> Unit = {
        Controls(
            state = state,
            muted = muted,
            recorderAvailable = viewModel.recorderAvailable,
            recording = recording,
            lastRecording = lastRecording,
            viewModel = viewModel,
        )
    }

    if (landscape) {
        Row(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            camera(Modifier.weight(1.5f).fillMaxSize())
            Column(Modifier.weight(1f).fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Header(state.tracking, cameraGranted, compact = true)
                controls()
            }
        }
    } else {
        Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 18.dp)) {
            Header(state.tracking, cameraGranted, compact = false)
            Spacer(Modifier.height(14.dp))
            ExerciseChips(state.exercise, enabled = !active, onSelect = viewModel::selectExercise)
            Spacer(Modifier.height(12.dp))
            camera(Modifier.weight(1f).fillMaxWidth())
            Spacer(Modifier.height(12.dp))
            controls()
        }
    }
}

@Composable
private fun Header(tracking: Boolean, cameraGranted: Boolean, compact: Boolean) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(if (compact) 34.dp else 42.dp).clip(RoundedCornerShape(14.dp)).background(Mint), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.FitnessCenter, contentDescription = null, tint = Ink)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("FORM COACH", color = Color.White, fontWeight = FontWeight.Black, letterSpacing = 1.5.sp, fontSize = 15.sp)
            if (!compact) Text("Your movement, in the moment", color = Muted, fontSize = 12.sp)
        }
        val color = if (cameraGranted && tracking) Mint else Amber
        Box(Modifier.size(7.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(
            if (!cameraGranted) "CAMERA" else if (tracking) "TRACKING" else "SEARCHING",
            color = color, fontSize = 10.sp, fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun ExerciseChips(selected: Exercise, enabled: Boolean, onSelect: (Exercise) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Exercise.entries.forEach { item ->
            AssistChip(
                onClick = { onSelect(item) },
                enabled = enabled,
                label = { Text(item.label, fontWeight = FontWeight.SemiBold) },
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = if (selected == item) Mint else Panel,
                    labelColor = if (selected == item) Ink else Color.White,
                    disabledContainerColor = if (selected == item) Mint.copy(alpha = 0.6f) else Panel,
                    disabledLabelColor = if (selected == item) Ink else Muted,
                ),
                border = null,
            )
        }
    }
}

@Composable
private fun CameraPanel(
    state: WorkoutUiState,
    facing: CameraFacing,
    cameraGranted: Boolean,
    cameraError: String?,
    flipEnabled: Boolean,
    viewModel: WorkoutViewModel,
    onRequestCamera: () -> Unit,
    modifier: Modifier,
) {
    Box(modifier.clip(RoundedCornerShape(28.dp)).background(Panel)) {
        if (cameraGranted) {
            CameraPreview(viewModel, facing)
            SkeletonOverlay(state.overlay, state.lockedSide, mirrorX = facing == CameraFacing.FRONT, modifier = Modifier.fillMaxSize())
            PhaseBanner(state.phase, Modifier.align(Alignment.TopStart).padding(14.dp))
            IconButton(
                onClick = viewModel::flipCamera,
                enabled = flipEnabled,
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).clip(CircleShape).background(Scrim),
            ) {
                Icon(
                    Icons.Default.FlipCameraAndroid,
                    contentDescription = if (facing == CameraFacing.FRONT) "Use rear camera" else "Use front camera",
                    tint = if (flipEnabled) Color.White else Muted,
                )
            }
            Row(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                RepCounter(state, Modifier.weight(1f))
                if (state.primaryLabel.isNotBlank()) MetricCard(state.primaryLabel, state.primaryValue, Modifier.weight(1f))
            }
            if (cameraError != null) {
                Text(
                    cameraError, color = Amber, fontSize = 13.sp,
                    modifier = Modifier.align(Alignment.Center).clip(RoundedCornerShape(12.dp)).background(Scrim).padding(14.dp),
                )
            }
        } else {
            Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.Videocam, contentDescription = null, tint = Muted, modifier = Modifier.size(34.dp))
                Spacer(Modifier.height(12.dp))
                Text("Camera access is needed", color = Color.White, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Text("Your video stays on this device.", color = Muted, fontSize = 13.sp)
                Spacer(Modifier.height(14.dp))
                Button(onClick = onRequestCamera, colors = ButtonDefaults.buttonColors(containerColor = Mint, contentColor = Ink)) {
                    Text("Enable camera")
                }
            }
        }
    }
}

@Composable
private fun CameraPreview(viewModel: WorkoutViewModel, facing: CameraFacing) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val view = LocalView.current
    // Keep the screen awake while the camera screen is visible.
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    val previewView = remember {
        PreviewView(view.context).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            scaleType = PreviewView.ScaleType.FIT_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    DisposableEffect(lifecycleOwner, previewView, facing) {
        viewModel.bindCamera(lifecycleOwner, previewView.surfaceProvider)
        onDispose { viewModel.unbindCamera() }
    }
    AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
}

@Composable
private fun PhaseBanner(phase: SessionPhase, modifier: Modifier) {
    val label = phaseLabel(phase) ?: return
    val color = when (phase) {
        SessionPhase.IN_SET -> Mint
        SessionPhase.PAUSED -> Amber
        else -> Color.White
    }
    Text(
        label.uppercase(), color = color, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp,
        modifier = modifier.clip(RoundedCornerShape(50)).background(Scrim).padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

@Composable
private fun RepCounter(state: WorkoutUiState, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(16.dp)).background(Scrim).padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text("REPS", color = Muted, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        Text(state.reps.toString(), color = Mint, fontSize = 64.sp, fontWeight = FontWeight.Black, lineHeight = 66.sp)
        if (state.leftReps != null && state.rightReps != null) {
            Text("L ${state.leftReps}  ·  R ${state.rightReps}", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
        if (state.partialReps > 0) {
            Text("${state.partialReps} partial", color = Amber, fontSize = 12.sp)
        }
    }
}

@Composable
private fun MetricCard(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier.clip(RoundedCornerShape(16.dp)).background(Scrim).padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(label, color = Muted, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        Spacer(Modifier.height(3.dp))
        Text(value, color = Mint, fontSize = 24.sp, fontWeight = FontWeight.Black)
    }
}

/** Shows [instruction], but never swaps the displayed text sooner than [INSTRUCTION_MIN_HOLD_MS] after it appeared. */
@Composable
private fun rememberHeldInstruction(instruction: String?): String? {
    var shown by remember { mutableStateOf(instruction) }
    var shownAt by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(instruction) {
        if (instruction == shown) return@LaunchedEffect
        val wait = holdRemainingMs(shownAt, SystemClock.elapsedRealtime())
        if (wait > 0) delay(wait)
        shown = instruction
        shownAt = SystemClock.elapsedRealtime()
    }
    return shown
}

@Composable
private fun Controls(
    state: WorkoutUiState,
    muted: Boolean,
    recorderAvailable: Boolean,
    recording: Boolean,
    lastRecording: String?,
    viewModel: WorkoutViewModel,
) {
    val landscape = LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    val active = isSetActive(state.phase)
    val held = rememberHeldInstruction(state.instruction)
    val message = held ?: when (state.phase) {
        SessionPhase.IDLE -> "Pick an exercise, then press Start set."
        SessionPhase.SET_COMPLETE -> "Press Start set for another set."
        else -> if (!state.tracking) "Step into view so I can see you." else ""
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (landscape) ExerciseChips(state.exercise, enabled = !active, onSelect = viewModel::selectExercise)

        state.summary?.takeIf { state.phase == SessionPhase.SET_COMPLETE }?.let { SummaryCard(it) }

        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Panel).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(if (state.tracking) Mint else Amber))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("COACH", color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                if (message.isNotBlank()) {
                    Spacer(Modifier.height(3.dp))
                    Text(message, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                }
                if (state.phase == SessionPhase.POSITIONING && state.placementHint != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(state.placementHint, color = Muted, fontSize = 12.sp)
                }
            }
            IconButton(onClick = viewModel::toggleMuted) {
                Icon(
                    imageVector = if (muted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                    contentDescription = if (muted) "Unmute voice instructions" else "Mute voice; keep text instructions",
                    tint = if (muted) Muted else Mint,
                )
            }
        }

        Button(
            onClick = { if (active) viewModel.stopSet() else viewModel.startSet() },
            modifier = Modifier.fillMaxWidth().height(52.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (active) Color(0xFFFF6B6B) else Mint,
                contentColor = Ink,
            ),
        ) {
            Text(if (active) "Stop set" else "Start set", fontWeight = FontWeight.Black, fontSize = 16.sp)
        }

        if (recorderAvailable) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Record landmarks (debug)", color = Color.White, fontSize = 13.sp)
                    if (lastRecording != null && !recording) Text("Saved $lastRecording", color = Muted, fontSize = 11.sp)
                }
                Switch(
                    checked = recording,
                    onCheckedChange = viewModel::setRecording,
                    colors = SwitchDefaults.colors(checkedTrackColor = Mint, checkedThumbColor = Ink),
                )
            }
        }

        Text(
            "Camera guidance is an estimate. Stop if you feel pain or develop a headache.",
            color = Muted, fontSize = 11.sp, modifier = Modifier.align(Alignment.CenterHorizontally),
        )
    }
}

@Composable
private fun SummaryCard(summary: SetSummary) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Panel).padding(16.dp)) {
        Text("SET COMPLETE", color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(summary.reps.toString(), color = Mint, fontSize = 40.sp, fontWeight = FontWeight.Black)
            Spacer(Modifier.width(8.dp))
            Text(if (summary.reps == 1) "rep" else "reps", color = Color.White, fontSize = 16.sp, modifier = Modifier.padding(bottom = 6.dp))
        }
        if (summary.partialReps > 0) {
            Text("${summary.partialReps} partial (not counted)", color = Amber, fontSize = 13.sp)
        }
        if (summary.topFaultAdvice != null) {
            Spacer(Modifier.height(4.dp))
            Text("Next set: ${summary.topFaultAdvice}", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}
