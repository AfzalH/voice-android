package com.srizonvoice.android

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import com.srizonvoice.android.recording.RecordingCoordinator
import com.srizonvoice.android.settings.SecureKeyStore
import com.srizonvoice.android.settings.SettingsRepository

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
