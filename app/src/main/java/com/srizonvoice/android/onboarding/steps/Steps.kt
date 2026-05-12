package com.srizonvoice.android.onboarding.steps

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.srizonvoice.android.onboarding.PermissionsSnapshot
import kotlinx.coroutines.launch

@Composable
private fun StepScaffold(
    title: String,
    subtitle: String,
    content: @Composable () -> Unit,
    actions: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.systemBars)
            .imePadding()
            .padding(horizontal = 24.dp)
            .padding(top = 32.dp, bottom = 24.dp),
    ) {
        // Header + content scroll together so the actions stay anchored to the
        // bottom of whatever visible area remains (full screen, or compressed
        // above the IME when the keyboard is open).
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
            )
            Spacer(Modifier.height(12.dp))
            content()
        }
        Spacer(Modifier.height(20.dp))
        Column(modifier = Modifier.fillMaxWidth()) {
            actions()
        }
    }
}

@Composable
fun WelcomeStep(onContinue: () -> Unit) {
    StepScaffold(
        title = "Welcome to SrizonVoice",
        subtitle = "Handsfree dictation that drops Gemini's final text into any text field on your phone.",
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Bullet("You'll add your own Gemini API key (BYOK) — your audio never goes through us.")
                Bullet("Gemini can transcribe as-is, correct dictated speech, or translate into a target language.")
                Bullet("This setup walks you through every permission we need, one at a time.")
            }
        },
        actions = {
            Button(onClick = onContinue, modifier = Modifier.fillMaxWidth()) { Text("Get started") }
        },
    )
}

@Composable
fun GeminiKeyStep(
    initialKey: String,
    onValidate: suspend (String) -> Boolean,
    onContinue: (String) -> Unit,
) {
    var value by remember { mutableStateOf(initialKey) }
    var validating by remember { mutableStateOf(false) }
    var validated by remember { mutableStateOf(initialKey.isNotBlank()) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current

    StepScaffold(
        title = "Add your Gemini API key",
        subtitle = "We'll send your audio directly to Gemini for transcription, correction, and translation.",
        content = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it; validated = false; errorMessage = null },
                label = { Text("Gemini API key") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                visualTransformation = if (validated) VisualTransformation.None else PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = {
                        scope.launch {
                            validating = true
                            errorMessage = null
                            val ok = onValidate(value.trim())
                            validating = false
                            validated = ok
                            if (!ok) errorMessage = "Invalid API key. Check Settings."
                        }
                    },
                    enabled = !validating && value.isNotBlank(),
                ) { Text(if (validated) "Validated" else "Validate") }
                Spacer(Modifier.size(12.dp))
                if (validating) CircularProgressIndicator(modifier = Modifier.size(20.dp))
                if (validated && !validating) Icon(Icons.Filled.CheckCircle, null, tint = Color(0xFF1EAB6E))
            }
            errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (!validated) {
                TextButton(
                    onClick = { uriHandler.openUri("https://aistudio.google.com/apikey") },
                ) {
                    Text("Get an API key from Google AI Studio")
                }
            }
        },
        actions = {
            Button(
                onClick = { onContinue(value.trim()) },
                enabled = validated,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Continue") }
        },
    )
}

@Composable
fun MicrophoneStep(granted: Boolean, onRequest: () -> Unit, onContinue: () -> Unit) {
    StepScaffold(
        title = "Microphone access",
        subtitle = "We capture audio at 16 kHz mono and send it to Gemini. Nothing is stored after the final text comes back.",
        content = { GrantStatusRow(granted, "Microphone permission") },
        actions = {
            if (!granted) Button(onClick = onRequest, modifier = Modifier.fillMaxWidth()) { Text("Grant permission") }
            else Button(onClick = onContinue, modifier = Modifier.fillMaxWidth()) { Text("Continue") }
        },
    )
}

@Composable
fun OverlayStep(granted: Boolean, onOpenSettings: () -> Unit, onContinue: () -> Unit) {
    StepScaffold(
        title = "Display over other apps",
        subtitle = "The floating bubble lives on top of every app so you can dictate without switching back to SrizonVoice.",
        content = { GrantStatusRow(granted, "Display over other apps") },
        actions = {
            if (!granted) Button(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) { Text("Open system setting") }
            else Button(onClick = onContinue, modifier = Modifier.fillMaxWidth()) { Text("Continue") }
        },
    )
}

@Composable
fun AccessibilityStep(granted: Boolean, onOpenSettings: () -> Unit, onContinue: () -> Unit) {
    StepScaffold(
        title = "Accessibility service",
        subtitle = "Lets us insert your transcript into the focused text field of any app. We use this only when you finish a recording.",
        content = {
            GrantStatusRow(granted, "Accessibility service")
            Text(
                "Find SrizonVoice under Settings → Accessibility → Installed services and toggle it on.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            )
        },
        actions = {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (!granted) Button(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) { Text("Open Accessibility settings") }
                TextButton(onClick = onContinue, modifier = Modifier.fillMaxWidth()) {
                    Text(if (granted) "Continue" else "Skip for now (clipboard fallback only)")
                }
            }
        },
    )
}

@Composable
fun DoneStep(snapshot: PermissionsSnapshot, onFinish: () -> Unit) {
    StepScaffold(
        title = "All set",
        subtitle = "You can rerun this setup any time from Settings → Run setup again.",
        content = {
            Card {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusLine("Microphone", snapshot.microphone)
                    HorizontalDivider()
                    StatusLine("Display over other apps", snapshot.overlay)
                    HorizontalDivider()
                    StatusLine("Accessibility service", snapshot.accessibility)
                }
            }
        },
        actions = {
            Button(onClick = onFinish, modifier = Modifier.fillMaxWidth()) { Text("Start dictating") }
        },
    )
}

@Composable
private fun GrantStatusRow(granted: Boolean, label: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (granted) Icon(Icons.Filled.CheckCircle, null, tint = Color(0xFF1EAB6E))
        else Icon(Icons.Filled.RadioButtonUnchecked, null, tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.weight(1f))
        Text(
            if (granted) "Granted" else "Not yet",
            style = MaterialTheme.typography.labelMedium,
            color = if (granted) Color(0xFF1EAB6E) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
    }
}

@Composable
private fun StatusLine(label: String, granted: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (granted) Icon(Icons.Filled.CheckCircle, null, tint = Color(0xFF1EAB6E))
        else Icon(Icons.Filled.RadioButtonUnchecked, null, tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
        Spacer(Modifier.size(12.dp))
        Text(label)
    }
}

@Composable
private fun Bullet(text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Text("•  ", style = MaterialTheme.typography.bodyLarge)
        Text(text, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Start)
    }
}
