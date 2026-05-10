package com.srizonvoice.android.insertion

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.srizonvoice.android.SrizonApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Accessibility service that owns text insertion *and* publishes a few global
 * signals the rest of the app uses:
 *
 *  - Subscribes to [com.srizonvoice.android.recording.RecordingCoordinator]'s
 *    transcript flow and inserts each transcript into the focused text field
 *    via [TextInserter].
 *  - Watches the active windows list for an [AccessibilityWindowInfo.TYPE_INPUT_METHOD]
 *    entry and pushes the result into [SrizonApp.imeOpen]. The bubble service
 *    uses that to hide the floating bubble while no keyboard is on screen.
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
        publishImeState()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        // Re-evaluate keyboard state on any window-shape change (incl. IME show/hide)
        // and on focus changes (so we catch the moment a text field gains focus).
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> publishImeState()
        }
    }

    /**
     * Two-pronged keyboard detection:
     *  1. Primary: any active window of type [AccessibilityWindowInfo.TYPE_INPUT_METHOD].
     *     Both Gboard and Samsung Keyboard register their window with this type.
     *  2. Fallback: a focused editable node anywhere in the active window.
     *     Catches IMEs that don't register a separate window (rare, but
     *     defensive) and bridges the brief moment between focus and IME show.
     */
    private fun publishImeState() {
        val windowsList = runCatching { windows }.getOrNull()
        val hasImeWindow = windowsList?.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD } ?: false
        val focusedEditable = runCatching {
            rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.isEditable == true
        }.getOrNull() ?: false
        val open = hasImeWindow || focusedEditable

        if (BuildConfigFlags.LOG_IME_DETECTION) {
            Log.d(
                TAG,
                "publishImeState: open=$open hasImeWindow=$hasImeWindow focusedEditable=$focusedEditable " +
                    "windows=${windowsList?.joinToString { "type=${it.type}/id=${it.id}" }}",
            )
        }

        val app = applicationContext as SrizonApp
        if (app.imeOpen.value != open) app.imeOpen.value = open
    }

    override fun onInterrupt() { /* No-op */ }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        scope?.cancel()
        scope = null
        if (instance === this) instance = null
        // Clear the keyboard-visible signal — accessibility is gone, we can't trust it.
        (applicationContext as SrizonApp).imeOpen.value = false
        return super.onUnbind(intent)
    }

    private object BuildConfigFlags {
        // Flip to true while debugging IME detection. logcat: `adb logcat -s Srizon:D`.
        const val LOG_IME_DETECTION = false
    }

    companion object {
        private const val TAG = "Srizon"

        @Volatile
        private var instance: SrizonAccessibilityService? = null
        fun isConnected(): Boolean = instance != null
    }
}
