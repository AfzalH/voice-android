package com.srizonvoice.android

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.srizonvoice.android.onboarding.OnboardingActivity
import com.srizonvoice.android.settings.SettingsScreen
import com.srizonvoice.android.tracer.TracerBulletScreen
import com.srizonvoice.android.trigger.bubble.BubbleService
import com.srizonvoice.android.ui.SrizonTheme

/**
 * Entry point. Routes to onboarding on first run, otherwise shows the tracer-bullet
 * dictation screen (M1) which doubles as the manual-test surface for the recording
 * coordinator. The Settings screen is reachable from inside the tracer bullet UI.
 */
class MainActivity : ComponentActivity() {

    private val app by lazy { application as SrizonApp }

    private val requestRecordAudio = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* state change picked up on next composition */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SrizonTheme {
                val settingsState by app.settingsRepository.state.collectAsState(initial = null)

                LaunchedEffect(settingsState) {
                    val state = settingsState ?: return@LaunchedEffect
                    if (!state.onboardingComplete) {
                        startActivity(Intent(this@MainActivity, OnboardingActivity::class.java))
                        finish()
                        return@LaunchedEffect
                    }
                    ensureBubbleRunning(this@MainActivity)
                }

                if (settingsState == null || settingsState?.onboardingComplete != true) {
                    Surface(modifier = Modifier.fillMaxSize()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                } else {
                    TracerBulletScreen(
                        coordinator = app.recordingCoordinator,
                        settings = app.settingsRepository,
                        keys = app.secureKeyStore,
                        onOpenSettings = {
                            startActivity(Intent(this, SettingsHostActivity::class.java))
                        },
                        onRequestMicPermission = { requestRecordAudio.launch(Manifest.permission.RECORD_AUDIO) },
                        hasMicPermission = {
                            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                                PackageManager.PERMISSION_GRANTED
                        },
                    )
                }
            }
        }
    }
}

/**
 * Starts the floating-bubble foreground service if the user has granted overlay
 * permission and it isn't already running. Idempotent — calling on every launch
 * is safe; an already-running service ignores the new start intent.
 */
internal fun ensureBubbleRunning(context: Context) {
    if (!Settings.canDrawOverlays(context)) return
    val intent = Intent(context, BubbleService::class.java)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
    else context.startService(intent)
}

/** Hosts the settings screen as its own activity so back-stack semantics are clean. */
class SettingsHostActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as SrizonApp
        setContent {
            SrizonTheme {
                SettingsScreen(
                    settings = app.settingsRepository,
                    keys = app.secureKeyStore,
                    onClose = { finish() },
                    onRerunOnboarding = {
                        startActivity(Intent(this, OnboardingActivity::class.java))
                        finish()
                    },
                )
            }
        }
    }
}
