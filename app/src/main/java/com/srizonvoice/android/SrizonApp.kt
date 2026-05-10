package com.srizonvoice.android

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import com.srizonvoice.android.recording.RecordingCoordinator
import com.srizonvoice.android.settings.SecureKeyStore
import com.srizonvoice.android.settings.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow

class SrizonApp : Application() {

    val secureKeyStore: SecureKeyStore by lazy { SecureKeyStore(this) }
    val settingsRepository: SettingsRepository by lazy { SettingsRepository(this) }
    val recordingCoordinator: RecordingCoordinator by lazy {
        RecordingCoordinator(
            appContext = this,
            settings = settingsRepository,
            keys = secureKeyStore,
        )
    }

    /** Reactive "is any system IME currently visible" — driven by
     *  [com.srizonvoice.android.insertion.SrizonAccessibilityService] looking
     *  for an `AccessibilityWindowInfo.TYPE_INPUT_METHOD` window. The bubble
     *  service uses this (gated on a settings toggle) to hide the floating
     *  bubble when the user isn't actively in a text field. */
    val imeOpen = MutableStateFlow(false)

    override fun onCreate() {
        super.onCreate()
        appInstance = this
        registerNotificationChannels()
    }

    private fun registerNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        // The recording foreground service has to be tied to a channel even
        // though the user typically never sees the notification (we don't
        // declare POST_NOTIFICATIONS so the system suppresses the visible
        // chrome on Android 13+ — the service itself still runs).
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_RECORDING,
                "Recording",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Active while SrizonVoice's bubble is running."
                setShowBadge(false)
            },
        )
    }

    companion object {
        const val CHANNEL_RECORDING = "srizon.recording"

        private lateinit var appInstance: SrizonApp
        fun get(): SrizonApp = appInstance
    }
}
