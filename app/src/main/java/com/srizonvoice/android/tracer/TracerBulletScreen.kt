package com.srizonvoice.android.tracer

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.srizonvoice.android.recording.RecordingCoordinator
import com.srizonvoice.android.recording.RecordingState
import com.srizonvoice.android.settings.SecureKeyStore
import com.srizonvoice.android.settings.SettingsRepository
import com.srizonvoice.android.ui.GradientPalette
import com.srizonvoice.android.ui.WaveformBars

@Composable
fun TracerBulletScreen(
    coordinator: RecordingCoordinator,
    settings: SettingsRepository,
    keys: SecureKeyStore,
    onOpenSettings: () -> Unit,
    onRequestMicPermission: () -> Unit,
    hasMicPermission: () -> Boolean,
) {
    val state by coordinator.state.collectAsState()
    val settingsState by settings.state.collectAsState(initial = null)

    var lastTranscript by remember { mutableStateOf<String?>(null) }
    var lastError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(coordinator) {
        coordinator.transcripts.collect { event -> lastTranscript = event.text; lastError = null }
    }
    LaunchedEffect(coordinator) {
        coordinator.errors.collect { msg -> lastError = msg }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "SrizonVoice",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                IconButton(onClick = onOpenSettings) {
                    Icon(Icons.Filled.Settings, contentDescription = "Settings")
                }
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = when (val s = state) {
                        is RecordingState.Recording -> "Listening… release to transcribe"
                        is RecordingState.Transcribing -> "Transcribing…"
                        is RecordingState.Error -> s.message
                        is RecordingState.Idle -> "Hold the mic to dictate"
                    },
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.titleMedium,
                )

                Box(
                    modifier = Modifier
                        .size(180.dp)
                        .clip(CircleShape)
                        .background(GradientPalette.Brush)
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onPress = {
                                    if (!hasMicPermission()) {
                                        onRequestMicPermission()
                                        return@detectTapGestures
                                    }
                                    if (settingsState == null) return@detectTapGestures
                                    if (keys.groqApiKey.isBlank()) {
                                        lastError = "Invalid API key. Check Settings."
                                        return@detectTapGestures
                                    }
                                    coordinator.startRecording()
                                    val released = tryAwaitRelease()
                                    if (released) coordinator.stopAndTranscribe(sourcePackage = "srizon.tracer")
                                    else coordinator.cancel()
                                },
                            )
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    when (state) {
                        is RecordingState.Transcribing -> CircularProgressIndicator(color = Color.White)
                        is RecordingState.Recording -> WaveformBars(
                            level = (state as RecordingState.Recording).visualLevel,
                            modifier = Modifier
                                .padding(24.dp)
                                .height(72.dp)
                                .fillMaxWidth(),
                        )
                        else -> Icon(
                            imageVector = Icons.Filled.Mic,
                            contentDescription = "Hold to record",
                            tint = Color.White,
                            modifier = Modifier.size(72.dp),
                        )
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "The dictation bubble is always running in the background. " +
                        "Long-press it in any app to dictate.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
                lastTranscript?.let {
                    Text("Transcript", style = MaterialTheme.typography.labelMedium)
                    Text(it, style = MaterialTheme.typography.bodyLarge)
                }
                lastError?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}
