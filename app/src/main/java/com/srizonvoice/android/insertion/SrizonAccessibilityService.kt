package com.srizonvoice.android.insertion

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import com.srizonvoice.android.SrizonApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Accessibility service that owns text insertion. Subscribes to the
 * [com.srizonvoice.android.recording.RecordingCoordinator]'s transcript flow and
 * inserts each transcript into the focused text field via [TextInserter].
 */
class SrizonAccessibilityService : AccessibilityService() {

    private var scope: CoroutineScope? = null
    private val supervisor = SupervisorJob()
    private lateinit var inserter: TextInserter

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        inserter = TextInserter(applicationContext) { rootInActiveWindow }
        val job: Job = supervisor
        val app = applicationContext as SrizonApp
        scope = CoroutineScope(Dispatchers.Main + job).also { s ->
            s.launch { app.recordingCoordinator.transcripts.collect { event -> inserter.insert(event.text) } }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) { /* No-op — we only act on transcript events. */ }

    override fun onInterrupt() { /* No-op */ }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        scope?.cancel()
        scope = null
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    companion object {
        @Volatile
        private var instance: SrizonAccessibilityService? = null
        fun isConnected(): Boolean = instance != null
    }
}
