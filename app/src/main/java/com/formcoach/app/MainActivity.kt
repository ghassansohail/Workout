package com.formcoach.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner

private val Ink = Color(0xFF101212)
private val Panel = Color(0xFF1B1F1D)
private val Mint = Color(0xFFB5F36B)
private val Muted = Color(0xFF9BA49D)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { FormCoachApp() }
    }
}

@Composable
private fun FormCoachApp() {
    val context = LocalContext.current
    var cameraGranted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        cameraGranted = it
    }
    var exercise by remember { mutableStateOf(Exercise.SQUAT) }
    var reps by remember { mutableIntStateOf(0) }
    var feedback by remember { mutableStateOf("Position yourself in frame") }
    var angle by remember { mutableStateOf("--°") }
    var voiceEnabled by remember { mutableStateOf(true) }
    var cameraFacing by remember { mutableStateOf(CameraFacing.FRONT) }
    var voiceCue by remember { mutableStateOf<String?>(null) }
    var personDetected by remember { mutableStateOf(false) }
    val voiceCoach = remember(context) { VoiceCoach(context) }

    DisposableEffect(voiceCoach) {
        onDispose { voiceCoach.shutdown() }
    }
    LaunchedEffect(voiceCue, voiceEnabled, personDetected) {
        if (!voiceEnabled || !personDetected) voiceCoach.stop() else voiceCue?.let(voiceCoach::speak)
    }

    LaunchedEffect(Unit) {
        if (!cameraGranted) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = Ink) {
            Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 18.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(42.dp).clip(RoundedCornerShape(14.dp)).background(Mint), contentAlignment = Alignment.Center) {
                        androidx.compose.material3.Icon(Icons.Default.FitnessCenter, contentDescription = null, tint = Ink)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("FORM COACH", color = Color.White, fontWeight = FontWeight.Black, letterSpacing = 1.5.sp, fontSize = 15.sp)
                        Text("Your movement, in the moment", color = Muted, fontSize = 12.sp)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(7.dp).clip(CircleShape).background(if (cameraGranted) Mint else Color(0xFFFFB86C)))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            if (!cameraGranted) "CAMERA" else if (personDetected) "TRACKING" else "SEARCHING",
                            color = if (personDetected) Mint else Color(0xFFFFB86C),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                Spacer(Modifier.height(22.dp))
                Text("TODAY’S SESSION", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.3.sp)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Exercise.entries.forEach { item ->
                        AssistChip(
                            onClick = {
                                voiceCoach.stop()
                                exercise = item
                                reps = 0
                                feedback = "Get in position"
                                angle = "--°"
                                voiceCue = null
                                personDetected = false
                            },
                            label = { Text(item.label, fontWeight = FontWeight.SemiBold) },
                            colors = AssistChipDefaults.assistChipColors(
                                containerColor = if (exercise == item) Mint else Panel,
                                labelColor = if (exercise == item) Ink else Color.White
                            ),
                            border = null
                        )
                    }
                }
                Spacer(Modifier.height(14.dp))
                Box(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(Panel)) {
                    if (cameraGranted) {
                        CameraPreview(
                            exercise = exercise,
                            cameraFacing = cameraFacing,
                            onResult = { result ->
                                reps = result.reps
                                feedback = result.feedback
                                angle = result.angle
                                voiceCue = result.voiceCue
                                personDetected = result.personDetected
                            }
                        )
                        Column(Modifier.align(Alignment.TopStart).padding(16.dp)) {
                            Text("LIVE FORM CHECK", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp,
                                modifier = Modifier.clip(RoundedCornerShape(50)).background(Color(0xAA101212)).padding(horizontal = 12.dp, vertical = 8.dp))
                        }
                        Button(
                            onClick = {
                                voiceCoach.stop()
                                cameraFacing = if (cameraFacing == CameraFacing.FRONT) CameraFacing.BACK else CameraFacing.FRONT
                                feedback = "Switching camera…"
                                voiceCue = null
                                personDetected = false
                            },
                            modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xCC101212), contentColor = Color.White)
                        ) {
                            Text(if (cameraFacing == CameraFacing.FRONT) "Use rear camera" else "Use front camera")
                        }
                    } else {
                        Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            androidx.compose.material3.Icon(Icons.Default.Videocam, contentDescription = null, tint = Muted, modifier = Modifier.size(34.dp))
                            Spacer(Modifier.height(12.dp))
                            Text("Camera access is needed", color = Color.White, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(6.dp))
                            Text("Your video stays on this device.", color = Muted, fontSize = 13.sp)
                            Spacer(Modifier.height(14.dp))
                            Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }, colors = ButtonDefaults.buttonColors(containerColor = Mint, contentColor = Ink)) {
                                Text("Enable camera")
                            }
                        }
                    }
                    Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        MetricCard("REPS", reps.toString(), Modifier.weight(1f))
                        MetricCard("JOINT ANGLE", angle, Modifier.weight(1f))
                    }
                }
                Spacer(Modifier.height(14.dp))
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Panel).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(Mint))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("COACH CUE", color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                        Spacer(Modifier.height(3.dp))
                        Text(feedback.ifBlank { "Form looks good" }, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    }
                    IconButton(onClick = { voiceEnabled = !voiceEnabled }) {
                        androidx.compose.material3.Icon(
                            imageVector = if (voiceEnabled) Icons.Default.VolumeUp else Icons.Default.VolumeOff,
                            contentDescription = if (voiceEnabled) "Mute voice; keep text instructions" else "Unmute voice instructions",
                            tint = if (voiceEnabled) Mint else Muted
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text("Camera guidance is an estimate. Stop if you feel pain or develop a headache.", color = Muted, fontSize = 11.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
            }
        }
    }
}

@Composable
private fun MetricCard(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier.clip(RoundedCornerShape(16.dp)).background(Color(0xCC101212)).padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(label, color = Muted, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        Spacer(Modifier.height(3.dp))
        Text(value, color = Mint, fontSize = 24.sp, fontWeight = FontWeight.Black)
    }
}

@Composable
private fun CameraPreview(exercise: Exercise, cameraFacing: CameraFacing, onResult: (CoachResult) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val camera = remember(context) { PoseCameraController(context) }
    DisposableEffect(camera) {
        onDispose { camera.dispose() }
    }
    DisposableEffect(lifecycleOwner, exercise, cameraFacing) {
        camera.start(lifecycleOwner, exercise, cameraFacing, onResult)
        onDispose { camera.stop() }
    }
    AndroidView(
        factory = { camera.previewView },
        modifier = Modifier.fillMaxSize()
    )
}
