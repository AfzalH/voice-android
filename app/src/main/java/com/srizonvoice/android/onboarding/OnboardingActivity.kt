package com.srizonvoice.android.onboarding

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.srizonvoice.android.MainActivity
import com.srizonvoice.android.SrizonApp
import com.srizonvoice.android.ensureBubbleRunning
import com.srizonvoice.android.api.GeminiClient
import com.srizonvoice.android.api.GroqClient
import com.srizonvoice.android.onboarding.steps.AccessibilityStep
import com.srizonvoice.android.onboarding.steps.DoneStep
import com.srizonvoice.android.onboarding.steps.GeminiKeyStep
import com.srizonvoice.android.onboarding.steps.GroqKeyStep
import com.srizonvoice.android.onboarding.steps.MicrophoneStep
import com.srizonvoice.android.onboarding.steps.OverlayStep
import com.srizonvoice.android.onboarding.steps.WelcomeStep
import com.srizonvoice.android.ui.SrizonTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class OnboardingActivity : ComponentActivity() {

    private lateinit var watcher: PermissionWatcher
    private val state = MutableStateFlow(
        PermissionsSnapshot(
            microphone = false,
            overlay = false,
            accessibility = false,
        ),
    )

    private val requestMic = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        watcher = PermissionWatcher(this)
        refresh()

        val app = application as SrizonApp
        val groq = GroqClient()
        val gemini = GeminiClient()

        setContent {
            SrizonTheme {
                Surface {
                    val nav = rememberNavController()
                    val perms by state.collectAsState()

                    NavHost(navController = nav, startDestination = "welcome") {
                        composable("welcome") {
                            WelcomeStep(onContinue = { nav.navigate("groq") })
                        }
                        composable("groq") {
                            GroqKeyStep(
                                initialValue = app.secureKeyStore.groqApiKey,
                                onValidate = { key -> groq.validateKey(key) },
                                onContinue = { key ->
                                    app.secureKeyStore.groqApiKey = key
                                    nav.navigate("gemini")
                                },
                            )
                        }
                        composable("gemini") {
                            GeminiKeyStep(
                                initialKey = app.secureKeyStore.geminiApiKey,
                                onValidate = { key -> gemini.validateKey(key) },
                                onContinue = { useGemini, key ->
                                    if (useGemini) app.secureKeyStore.geminiApiKey = key
                                    lifecycleScope.launch { app.settingsRepository.setUseGemini(useGemini) }
                                    nav.navigate("microphone")
                                },
                            )
                        }
                        composable("microphone") {
                            MicrophoneStep(
                                granted = perms.microphone,
                                onRequest = { requestMic.launch(Manifest.permission.RECORD_AUDIO) },
                                onContinue = { nav.navigate("overlay") },
                            )
                        }
                        composable("overlay") {
                            OverlayStep(
                                granted = perms.overlay,
                                onOpenSettings = { openOverlaySettings() },
                                onContinue = { nav.navigate("accessibility") },
                            )
                        }
                        composable("accessibility") {
                            AccessibilityStep(
                                granted = perms.accessibility,
                                onOpenSettings = {
                                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                                },
                                onContinue = { nav.navigate("done") },
                            )
                        }
                        composable("done") {
                            val finishedSnapshot = remember { mutableStateOf(false) }
                            LaunchedEffect(Unit) {
                                if (!finishedSnapshot.value) {
                                    app.settingsRepository.setOnboardingComplete(true)
                                    // Re-show the success banner — last dismissal applied to
                                    // the previous setup run, not this one.
                                    app.settingsRepository.setSetupBannerDismissed(false)
                                    finishedSnapshot.value = true
                                }
                            }
                            DoneStep(
                                snapshot = perms,
                                onFinish = {
                                    ensureBubbleRunning(this@OnboardingActivity)
                                    val intent = Intent(this@OnboardingActivity, MainActivity::class.java)
                                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                                    startActivity(intent)
                                    finish()
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        state.value = watcher.snapshot()
    }

    private fun openOverlaySettings() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName"),
        )
        startActivity(intent)
    }
}
