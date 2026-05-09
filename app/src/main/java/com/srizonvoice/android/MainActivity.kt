package com.srizonvoice.android

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.srizonvoice.android.home.HomeScreen
import com.srizonvoice.android.onboarding.OnboardingActivity
import com.srizonvoice.android.trigger.bubble.BubbleService
import com.srizonvoice.android.ui.SrizonTheme

/**
 * Single-screen host. After onboarding, this is the only Activity the user sees.
 * The `HomeScreen` composable contains the welcome / status header and a tabbed
 * settings surface — there's no separate Settings activity. The bubble's gear
 * button just routes back here.
 */
class MainActivity : ComponentActivity() {

    private val app by lazy { application as SrizonApp }

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
                    HomeScreen(
                        settings = app.settingsRepository,
                        keys = app.secureKeyStore,
                        onRerunOnboarding = {
                            startActivity(Intent(this, OnboardingActivity::class.java))
                            finish()
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
