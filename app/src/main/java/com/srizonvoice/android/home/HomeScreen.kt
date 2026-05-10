@file:OptIn(ExperimentalMaterial3Api::class)

package com.srizonvoice.android.home

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.srizonvoice.android.R
import com.srizonvoice.android.api.GeminiClient
import com.srizonvoice.android.api.GroqClient
import com.srizonvoice.android.data.LanguageOption
import com.srizonvoice.android.data.RecordingMode
import com.srizonvoice.android.data.TranscriptionModel
import com.srizonvoice.android.onboarding.PermissionWatcher
import com.srizonvoice.android.onboarding.PermissionsSnapshot
import com.srizonvoice.android.settings.SecureKeyStore
import com.srizonvoice.android.settings.SettingsRepository
import com.srizonvoice.android.settings.SettingsState
import kotlinx.coroutines.launch

/**
 * The main app screen after onboarding. A single page with a branded header
 * up top (live status / instruction) and a tabbed settings surface below.
 *
 * The Settings gear on the floating bubble routes here too — it's the same
 * destination, no separate "settings activity".
 */
@Composable
fun HomeScreen(
    settings: SettingsRepository,
    keys: SecureKeyStore,
    onRerunOnboarding: () -> Unit,
) {
    val current by settings.state.collectAsState(initial = null)

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        val snapshot = current
        if (snapshot == null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            HomeContent(
                current = snapshot,
                settings = settings,
                keys = keys,
                onRerunOnboarding = onRerunOnboarding,
            )
        }
    }
}

@Composable
private fun HomeContent(
    current: SettingsState,
    settings: SettingsRepository,
    keys: SecureKeyStore,
    onRerunOnboarding: () -> Unit,
) {
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    val context = LocalContext.current
    val watcher = remember { PermissionWatcher(context) }
    var perms by remember { mutableStateOf(watcher.snapshot()) }

    // Refresh permissions whenever the user comes back from a system settings
    // page so the header banner and Permissions tab reflect any new grants.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                perms = watcher.snapshot()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val scope = rememberCoroutineScope()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.systemBars),
    ) {
        Header(
            perms = perms,
            successBannerDismissed = current.setupBannerDismissed,
            onDismissSuccessBanner = { scope.launch { settings.setSetupBannerDismissed(true) } },
        )
        TabBar(selected = selectedTab, onSelect = { selectedTab = it })
        when (selectedTab) {
            0 -> DictationTab(current, settings)
            1 -> AITab(current, settings, keys)
            else -> PermissionsTab(
                perms = perms,
                onRefresh = { perms = watcher.snapshot() },
                onRerunOnboarding = onRerunOnboarding,
            )
        }
    }
}

// ── Header ─────────────────────────────────────────────────────────────────

@Composable
private fun Header(
    perms: PermissionsSnapshot,
    successBannerDismissed: Boolean,
    onDismissSuccessBanner: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Image(
                painter = painterResource(R.drawable.srizon_logo),
                contentDescription = null,
                modifier = Modifier.size(36.dp),
            )
            Text(
                "SrizonVoice",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }
        StatusBanner(
            perms = perms,
            successBannerDismissed = successBannerDismissed,
            onDismissSuccessBanner = onDismissSuccessBanner,
        )
    }
}

@Composable
private fun StatusBanner(
    perms: PermissionsSnapshot,
    successBannerDismissed: Boolean,
    onDismissSuccessBanner: () -> Unit,
) {
    val allGranted = perms.microphone && perms.overlay && perms.accessibility
    when {
        allGranted && !successBannerDismissed -> SuccessBanner(onDismiss = onDismissSuccessBanner)
        !allGranted -> WarningBanner(perms = perms)
        // allGranted && dismissed → render nothing.
    }
}

@Composable
private fun SuccessBanner(onDismiss: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = SUCCESS_BG,
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "✅  Setup complete! Tap the floating bubble to start dictating.",
                style = MaterialTheme.typography.bodyMedium,
                color = SUCCESS_FG,
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 10.dp),
            )
            IconButton(onClick = onDismiss) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "Dismiss",
                    tint = SUCCESS_FG,
                )
            }
        }
    }
}

@Composable
private fun WarningBanner(perms: PermissionsSnapshot) {
    val missing = listOfNotNull(
        "Microphone".takeIf { !perms.microphone },
        "Display over other apps".takeIf { !perms.overlay },
        "Accessibility".takeIf { !perms.accessibility },
    )
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = WARNING_BG,
    ) {
        Text(
            text = "⚠️  Setup needs attention. Open the Permissions tab to grant: ${missing.joinToString()}.",
            style = MaterialTheme.typography.bodyMedium,
            color = WARNING_FG,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
        )
    }
}

private val SUCCESS_BG = Color(0xFFE7F5EC)  // soft mint
private val SUCCESS_FG = Color(0xFF1B5E20)  // forest green
private val WARNING_BG = Color(0xFFFFF4E5)  // soft cream
private val WARNING_FG = Color(0xFF7A4A00)  // burnt amber

// ── Tabs ───────────────────────────────────────────────────────────────────

private val TAB_LABELS = listOf("Dictation", "AI", "Permissions")
private val TAB_ICONS = listOf(
    Icons.Filled.GraphicEq,
    Icons.Filled.AutoAwesome,
    Icons.Filled.VerifiedUser,
)

@Composable
private fun TabBar(selected: Int, onSelect: (Int) -> Unit) {
    PrimaryTabRow(selectedTabIndex = selected) {
        TAB_LABELS.forEachIndexed { index, label ->
            Tab(
                selected = selected == index,
                onClick = { onSelect(index) },
                text = { Text(label) },
                icon = { Icon(TAB_ICONS[index], contentDescription = null) },
            )
        }
    }
}

// ── Dictation tab ──────────────────────────────────────────────────────────

@Composable
private fun DictationTab(state: SettingsState, settings: SettingsRepository) {
    val scope = rememberCoroutineScope()
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 12.dp, bottom = 24.dp),
    ) {
        item {
            LanguagePicker(
                selected = state.language,
                recents = state.recentLanguages,
                onSelect = { lang -> scope.launch { settings.setLanguage(lang) } },
            )
        }
        item {
            ModelPicker(
                selected = state.transcriptionModel,
                onSelect = { scope.launch { settings.setTranscriptionModel(it) } },
            )
        }
        item {
            ModeRow(
                selected = state.recordingMode,
                onSelect = { scope.launch { settings.setRecordingMode(it) } },
            )
        }
        item {
            HandsfreeMaxRow(
                minutes = state.handsfreeMaxMinutes,
                onChange = { scope.launch { settings.setHandsfreeMaxMinutes(it) } },
            )
        }
        item {
            OpacityRow(
                opacity = state.bubbleOpacity,
                onChange = { scope.launch { settings.setBubbleOpacity(it) } },
            )
        }
        item {
            ToggleRow(
                label = "Show bubble only when the keyboard is open",
                checked = state.showBubbleOnlyWhenKeyboard,
                onCheckedChange = { scope.launch { settings.setShowBubbleOnlyWhenKeyboard(it) } },
            )
        }
    }
}

// ── AI tab ─────────────────────────────────────────────────────────────────

@Composable
private fun AITab(state: SettingsState, settings: SettingsRepository, keys: SecureKeyStore) {
    val scope = rememberCoroutineScope()
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 12.dp, bottom = 24.dp),
    ) {
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
        item {
            ToggleRow(
                label = "Clean up transcripts with an LLM",
                checked = state.postProcessingEnabled,
                onCheckedChange = { scope.launch { settings.setPostProcessingEnabled(it) } },
            )
        }
        item {
            ToggleRow(
                label = "Use Gemini (otherwise uses Groq's own model)",
                checked = state.useGemini,
                enabled = state.postProcessingEnabled,
                onCheckedChange = { scope.launch { settings.setUseGemini(it) } },
            )
        }
        item {
            ToggleRow(
                label = "Show a translate button on the bubble",
                checked = state.translationEnabled,
                enabled = state.postProcessingEnabled,
                onCheckedChange = { scope.launch { settings.setTranslationEnabled(it) } },
            )
        }
        if (state.postProcessingEnabled && state.translationEnabled) {
            item {
                TargetLanguagePicker(
                    selected = state.targetLanguage,
                    recents = state.recentTargetLanguages,
                    sourceLanguage = state.language,
                    onSelect = { scope.launch { settings.setTargetLanguage(it) } },
                )
            }
            item {
                ToggleRow(
                    label = "Include cleaned original alongside the translation",
                    checked = state.translationIncludeSource,
                    onCheckedChange = { scope.launch { settings.setTranslationIncludeSource(it) } },
                )
            }
        }
        item {
            PromptEditor(
                value = state.postProcessingPrompt,
                onSave = { scope.launch { settings.setPostProcessingPrompt(it) } },
            )
        }
    }
}

// ── Permissions tab ────────────────────────────────────────────────────────

@Composable
private fun PermissionsTab(
    perms: PermissionsSnapshot,
    onRefresh: () -> Unit,
    onRerunOnboarding: () -> Unit,
) {
    val context = LocalContext.current
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PermissionsCard(
            snapshot = perms,
            onRefresh = onRefresh,
            onOpenOverlay = { openOverlaySettings(context) },
            onOpenAccessibility = {
                context.startActivity(
                    Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            },
            onRerunOnboarding = onRerunOnboarding,
        )
    }
}

// ── Cards ──────────────────────────────────────────────────────────────────

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

    SectionCard(title = title) {
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LanguagePicker(
    selected: LanguageOption,
    recents: List<LanguageOption>,
    onSelect: (LanguageOption) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    SectionCard(title = "Language") {
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
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TargetLanguagePicker(
    selected: LanguageOption,
    recents: List<LanguageOption>,
    sourceLanguage: LanguageOption,
    onSelect: (LanguageOption) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    SectionCard(title = "Translate into") {
        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
            OutlinedTextField(
                value = selected.displayName,
                onValueChange = {},
                readOnly = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier.fillMaxWidth().menuAnchor(),
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
        if (selected == sourceLanguage) {
            Text(
                "Same as the dictation language — tapping the bubble's translate button will be a no-op.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
        } else {
            Text(
                "Tap the bubble's translate button to clean and translate from ${sourceLanguage.displayName} into ${selected.displayName}. The check (✓) button stays direct dictation.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelPicker(selected: TranscriptionModel, onSelect: (TranscriptionModel) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    SectionCard(title = "Whisper model") {
        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
            OutlinedTextField(
                value = "${selected.displayName}  (${selected.id})",
                onValueChange = {},
                readOnly = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier.fillMaxWidth().menuAnchor(),
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
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

@Composable
private fun ToggleRow(
    label: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
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
    SectionCard(title = "Cleanup prompt") {
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it; saved = false },
            modifier = Modifier.fillMaxWidth(),
            minLines = 3,
            maxLines = 8,
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
        )
        Button(onClick = { onSave(draft); saved = true }, enabled = !saved) {
            Text(if (saved) "Saved" else "Save prompt")
        }
    }
}

@Composable
private fun ModeRow(selected: RecordingMode, onSelect: (RecordingMode) -> Unit) {
    SectionCard(title = "Recording mode") {
        RecordingMode.entries.forEach { mode ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = selected == mode, onClick = { onSelect(mode) })
                Spacer(Modifier.size(8.dp))
                Text(mode.displayName)
            }
        }
    }
}

@Composable
private fun HandsfreeMaxRow(minutes: Int, onChange: (Int) -> Unit) {
    SectionCard(title = "Handsfree max minutes") {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(1, 3, 5, 10, 15).forEach { value ->
                AssistChip(onClick = { onChange(value) }, label = { Text("$value") }, enabled = minutes != value)
            }
            Text("$minutes min", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun OpacityRow(opacity: Float, onChange: (Float) -> Unit) {
    SectionCard(title = "Bubble opacity") {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Idle bubble", style = MaterialTheme.typography.bodyMedium)
            Text("${(opacity * 100).toInt()}%", style = MaterialTheme.typography.bodyMedium)
        }
        Slider(
            value = opacity,
            onValueChange = onChange,
            valueRange = 0.1f..1f,
            steps = 8,
        )
        Text(
            "While dictating, the bubble is always shown at 100%.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
    }
}

@Composable
private fun PermissionsCard(
    snapshot: PermissionsSnapshot,
    onRefresh: () -> Unit,
    onOpenOverlay: () -> Unit,
    onOpenAccessibility: () -> Unit,
    onRerunOnboarding: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Permissions", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onRefresh) { Icon(Icons.Filled.Refresh, contentDescription = "Refresh") }
            }
            PermLine("Microphone", snapshot.microphone, action = null)
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
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (granted) Icon(Icons.Filled.Check, null, tint = MaterialTheme.colorScheme.primary)
        else Icon(Icons.Filled.Close, null, tint = MaterialTheme.colorScheme.error)
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        action?.invoke()
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

private fun openOverlaySettings(context: Context) {
    val intent = Intent(
        AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION,
        Uri.parse("package:${context.packageName}"),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(intent)
}
