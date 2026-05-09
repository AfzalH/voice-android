package com.srizonvoice.android.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings as AndroidSettings
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.srizonvoice.android.api.GeminiClient
import com.srizonvoice.android.api.GroqClient
import com.srizonvoice.android.data.LanguageOption
import com.srizonvoice.android.data.RecordingMode
import com.srizonvoice.android.data.TranscriptionModel
import com.srizonvoice.android.onboarding.PermissionWatcher
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: SettingsRepository,
    keys: SecureKeyStore,
    onClose: () -> Unit,
    onRerunOnboarding: () -> Unit,
) {
    val context = LocalContext.current
    val state by settings.state.collectAsState(initial = null)
    val scope = rememberCoroutineScope()

    val watcher = remember { PermissionWatcher(context) }
    var perms by remember { mutableStateOf(watcher.snapshot()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                actions = {
                    IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Close") }
                },
            )
        },
    ) { padding ->
        val current = state
        if (current == null) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { SectionHeader("API keys") }
            item {
                ApiKeyCard(
                    title = "Groq API key",
                    initialValue = keys.groqApiKey,
                    onSave = { keys.groqApiKey = it },
                    validate = { GroqClient().validateKey(it) },
                )
            }
            item {
                ApiKeyCard(
                    title = "Gemini API key (optional)",
                    initialValue = keys.geminiApiKey,
                    onSave = { keys.geminiApiKey = it },
                    validate = { GeminiClient().validateKey(it) },
                )
            }

            item { SectionHeader("Transcription") }
            item {
                LanguagePicker(
                    selected = current.language,
                    recents = current.recentLanguages,
                    onSelect = { lang -> scope.launch { settings.setLanguage(lang) } },
                )
            }
            item {
                ModelPicker(
                    selected = current.transcriptionModel,
                    onSelect = { scope.launch { settings.setTranscriptionModel(it) } },
                )
            }

            item { SectionHeader("Post-processing") }
            item {
                ToggleRow(
                    label = "Clean up transcripts with an LLM",
                    checked = current.postProcessingEnabled,
                    onCheckedChange = { scope.launch { settings.setPostProcessingEnabled(it) } },
                )
            }
            item {
                ToggleRow(
                    label = "Use Gemini (otherwise uses Groq's own model)",
                    checked = current.useGemini,
                    enabled = current.postProcessingEnabled,
                    onCheckedChange = { scope.launch { settings.setUseGemini(it) } },
                )
            }
            item {
                PromptEditor(
                    value = current.postProcessingPrompt,
                    onSave = { scope.launch { settings.setPostProcessingPrompt(it) } },
                )
            }

            item { SectionHeader("Behavior") }
            item {
                ModeRow(
                    selected = current.recordingMode,
                    onSelect = { scope.launch { settings.setRecordingMode(it) } },
                )
            }
            item {
                HandsfreeMaxRow(
                    minutes = current.handsfreeMaxMinutes,
                    onChange = { scope.launch { settings.setHandsfreeMaxMinutes(it) } },
                )
            }
            item {
                OpacityRow(
                    opacity = current.bubbleOpacity,
                    onChange = { scope.launch { settings.setBubbleOpacity(it) } },
                )
            }

            item { SectionHeader("Permissions") }
            item {
                PermissionsCard(
                    snapshot = perms,
                    onRefresh = { perms = watcher.snapshot() },
                    onOpenOverlay = { openOverlaySettings(context) },
                    onOpenAccessibility = {
                        context.startActivity(
                            Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    },
                    onRerunOnboarding = onRerunOnboarding,
                )
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun ApiKeyCard(
    title: String,
    initialValue: String,
    onSave: (String) -> Unit,
    validate: suspend (String) -> Boolean,
) {
    var value by remember { mutableStateOf(initialValue) }
    var validating by remember { mutableStateOf(false) }
    var validated by remember { mutableStateOf(initialValue.isNotBlank()) }
    val scope = rememberCoroutineScope()

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            OutlinedTextField(
                value = value,
                onValueChange = { value = it; validated = false },
                singleLine = true,
                visualTransformation = if (validated) VisualTransformation.None else PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Paste your key") },
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = {
                        scope.launch {
                            validating = true
                            val ok = validate(value.trim())
                            validating = false
                            validated = ok
                            if (ok) onSave(value.trim())
                        }
                    },
                    enabled = !validating && value.isNotBlank(),
                ) { Text(if (validated) "Saved" else "Validate & save") }
                Spacer(Modifier.size(12.dp))
                if (validating) CircularProgressIndicator(modifier = Modifier.size(20.dp))
                if (validated && !validating) Icon(Icons.Filled.Check, null, tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LanguagePicker(
    selected: LanguageOption,
    recents: List<LanguageOption>,
    onSelect: (LanguageOption) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Language", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
                OutlinedTextField(
                    value = selected.displayName,
                    onValueChange = {},
                    readOnly = true,
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(),
                )
                ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    LanguageOption.entries.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option.displayName) },
                            onClick = { expanded = false; onSelect(option) },
                        )
                    }
                }
            }
            if (recents.isNotEmpty()) {
                Text("Recently used", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    recents.forEach { lang ->
                        AssistChip(onClick = { onSelect(lang) }, label = { Text(lang.displayName) })
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelPicker(
    selected: TranscriptionModel,
    onSelect: (TranscriptionModel) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Whisper model", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
                OutlinedTextField(
                    value = "${selected.displayName}  (${selected.id})",
                    onValueChange = {},
                    readOnly = true,
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                    modifier = Modifier.fillMaxWidth().menuAnchor(),
                )
                ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    TranscriptionModel.entries.forEach { option ->
                        DropdownMenuItem(
                            text = { Text("${option.displayName} (${option.id})") },
                            onClick = { expanded = false; onSelect(option) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ToggleRow(
    label: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
        }
    }
}

@Composable
private fun PromptEditor(value: String, onSave: (String) -> Unit) {
    var draft by remember { mutableStateOf(value) }
    var saved by remember { mutableStateOf(true) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Cleanup prompt", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it; saved = false },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
                maxLines = 8,
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { onSave(draft); saved = true },
                    enabled = !saved,
                ) { Text(if (saved) "Saved" else "Save prompt") }
            }
        }
    }
}

@Composable
private fun ModeRow(selected: RecordingMode, onSelect: (RecordingMode) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Recording mode", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            RecordingMode.entries.forEach { mode ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.RadioButton(selected = selected == mode, onClick = { onSelect(mode) })
                    Spacer(Modifier.size(8.dp))
                    Text(mode.displayName)
                }
            }
        }
    }
}

@Composable
private fun OpacityRow(opacity: Float, onChange: (Float) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Bubble opacity",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text("${(opacity * 100).toInt()}%", style = MaterialTheme.typography.bodyMedium)
            }
            Slider(
                value = opacity,
                onValueChange = onChange,
                valueRange = 0.1f..1f,
                steps = 8, // 0.1 .. 1.0 in 10% increments → 9 stops
            )
        }
    }
}

@Composable
private fun HandsfreeMaxRow(minutes: Int, onChange: (Int) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Handsfree max minutes", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(1, 3, 5, 10, 15).forEach { value ->
                    AssistChip(onClick = { onChange(value) }, label = { Text("$value") }, enabled = minutes != value)
                }
                Text("$minutes min", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun PermissionsCard(
    snapshot: com.srizonvoice.android.onboarding.PermissionsSnapshot,
    onRefresh: () -> Unit,
    onOpenOverlay: () -> Unit,
    onOpenAccessibility: () -> Unit,
    onRerunOnboarding: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Permissions", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onRefresh) { Icon(Icons.Filled.Refresh, contentDescription = "Refresh") }
            }
            PermLine("Microphone", snapshot.microphone, action = null)
            HorizontalDivider()
            PermLine("Notifications", snapshot.notifications, action = null)
            HorizontalDivider()
            PermLine("Display over other apps", snapshot.overlay) {
                TextButton(onClick = onOpenOverlay) { Text("Open setting") }
            }
            HorizontalDivider()
            PermLine("Accessibility service", snapshot.accessibility) {
                TextButton(onClick = onOpenAccessibility) { Text("Open setting") }
            }
            HorizontalDivider()
            TextButton(onClick = onRerunOnboarding, modifier = Modifier.fillMaxWidth()) { Text("Run setup again") }
        }
    }
}

@Composable
private fun PermLine(label: String, granted: Boolean, action: (@Composable () -> Unit)?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (granted) Icon(Icons.Filled.Check, null, tint = MaterialTheme.colorScheme.primary)
        else Icon(Icons.Filled.Close, null, tint = MaterialTheme.colorScheme.error)
        Spacer(Modifier.size(12.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        action?.invoke()
    }
}

private fun openOverlaySettings(context: Context) {
    val intent = Intent(
        AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION,
        Uri.parse("package:${context.packageName}"),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(intent)
}
