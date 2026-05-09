package com.srizonvoice.android.trigger.bubble

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.srizonvoice.android.MainActivity
import com.srizonvoice.android.R
import com.srizonvoice.android.SrizonApp
import com.srizonvoice.android.data.RecordingMode
import com.srizonvoice.android.recording.RecordingState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Hosts the floating dictation bubble. Started as a foreground service with
 * `FOREGROUND_SERVICE_TYPE_MICROPHONE` (API 34+ rule, included from day one) so
 * the mic capture in [com.srizonvoice.android.recording.RecordingCoordinator] is
 * legally allowed even when the app is backgrounded.
 *
 * Trigger gestures (mirroring spec §5a):
 *  - ACTION_DOWN → start recording.
 *  - ACTION_UP   → stop and transcribe (if released far from cancel zone).
 *  - Drag near the top of the screen → release there to cancel without transcribing.
 *
 * The bubble is a single AppCompose-free [BubbleView] so it has no dependency on a
 * view-tree LifecycleOwner — keeps the overlay path simple on API 31+ overlays
 * where Compose-in-overlay still requires manual lifecycle owners.
 */
class BubbleService : Service() {

    private lateinit var windowManager: WindowManager
    private var bubbleView: BubbleView? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    private val supervisor = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Main + supervisor)
    private var stateJob: Job? = null
    private var errorJob: Job? = null
    private var modeJob: Job? = null
    private var snapJob: Job? = null

    /** Held while the user is pressing in PTT mode — fires after the activation
     * delay to actually begin recording. Canceled if the user moves before then. */
    private var pttArmJob: Job? = null

    /** True once [pttArmJob] fired and recording was started, so ACTION_UP knows
     * whether to call stop / cancel or just no-op. */
    private var pttArmed = false

    private var isCancelHovering = false
    private var hasMovedBeyondSlop = false

    /** Where the touch landed on the bubble surface. Used to dispatch the inline
     * Done/Cancel buttons in handsfree wide mode. Captured at ACTION_DOWN, locked
     * for the entire touch sequence so dragging out of a button doesn't fire it. */
    private var touchZone: TouchZone = TouchZone.MIDDLE

    private enum class TouchZone { LEFT_BUTTON, RIGHT_BUTTON, MIDDLE }

    /** Cached recording mode — touch events run synchronously, can't await DataStore. */
    @Volatile
    private var currentMode: RecordingMode = RecordingMode.HANDSFREE

    /** Cached idle opacity — applied when the bubble is in idle state. While the
     *  waveform is active (recording/transcribing) we override to 1.0 so the
     *  Cancel/Done buttons read at full strength. */
    @Volatile
    private var idleOpacity: Float = 0.5f

    private val touchSlopPx by lazy { ViewConfiguration.get(this).scaledTouchSlop }

    /** True when we shifted the bubble left to keep its right edge fixed while widening. */
    private var shiftedLeftForWideState = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startInForeground()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        addBubble()
        observeCoordinator()
        observeRecordingMode()
    }

    private fun observeRecordingMode() {
        modeJob?.cancel()
        val app = applicationContext as SrizonApp
        modeJob = scope.launch {
            app.settingsRepository.state.collect { settings ->
                currentMode = settings.recordingMode
                idleOpacity = settings.bubbleOpacity
                bubbleView?.setRecordingMode(settings.recordingMode)
                applyOpacity()
            }
        }
    }

    /**
     * Sets the bubble's view alpha based on whether the wave is active.
     *  - Wide (recording / transcribing): full opacity, so Cancel/Done buttons
     *    and the waveform read clearly even if the user has dimmed the idle
     *    bubble all the way down.
     *  - Idle: user-configured [idleOpacity] from Settings.
     */
    private fun applyOpacity() {
        val view = bubbleView ?: return
        val app = applicationContext as SrizonApp
        val state = app.recordingCoordinator.state.value
        val isWide = state is RecordingState.Recording || state is RecordingState.Transcribing
        view.alpha = if (isWide) 1f else idleOpacity
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE_RECORDING -> toggleFromTile()
            ACTION_STOP -> stopSelf()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        bubbleView?.let { runCatching { windowManager.removeView(it) } }
        bubbleView = null
        scope.cancel()
        super.onDestroy()
    }

    private fun startInForeground() {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopBubble = PendingIntent.getService(
            this,
            1,
            Intent(this, BubbleService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notif: Notification = NotificationCompat.Builder(this, SrizonApp.CHANNEL_RECORDING)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(getString(R.string.bubble_notification_title))
            .setContentText(getString(R.string.bubble_notification_text))
            .setContentIntent(openApp)
            .addAction(0, "Hide bubble", stopBubble)
            .setOngoing(true)
            .setSilent(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun addBubble() {
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 32
            y = 200
        }

        val view = BubbleView(this)
        view.setOnTouchListener { _, event -> handleTouch(event) }
        layoutParams = params
        bubbleView = view
        windowManager.addView(view, params)
    }

    private fun handleTouch(event: MotionEvent): Boolean {
        val params = layoutParams ?: return false
        val view = bubbleView ?: return false
        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Cancel any in-flight snap animation — the user is taking control again.
                snapJob?.cancel()
                snapJob = null
                pttArmJob?.cancel()
                pttArmJob = null
                pttArmed = false
                view.dragState.startX = params.x
                view.dragState.startY = params.y
                view.dragState.touchX = event.rawX
                view.dragState.touchY = event.rawY
                isCancelHovering = false
                hasMovedBeyondSlop = false
                touchZone = computeTouchZone(view, event.x)
                when (touchZone) {
                    TouchZone.LEFT_BUTTON, TouchZone.RIGHT_BUTTON -> {
                        // Inline button hit — don't move the bubble, don't start PTT.
                        // We'll fire the action on ACTION_UP if the touch stays in zone.
                    }
                    TouchZone.MIDDLE -> {
                        if (currentMode == RecordingMode.PUSH_TO_TALK) {
                            pttArmJob = scope.launch {
                                delay(PTT_ACTIVATION_DELAY_MS)
                                if (hasRecordPermission()) {
                                    pttArmed = true
                                    startRecording()
                                }
                            }
                        }
                        // Hands-free middle: defer to ACTION_UP — tap toggles, drag moves.
                    }
                }
                true
            }
            MotionEvent.ACTION_MOVE -> {
                if (touchZone == TouchZone.MIDDLE) {
                    handleMiddleMove(view, params, event)
                }
                // Inline-button touches don't drag — nothing to do here.
                true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                pttArmJob?.cancel()
                pttArmJob = null
                when (touchZone) {
                    TouchZone.LEFT_BUTTON -> cancelRecording()
                    TouchZone.RIGHT_BUTTON -> stopAndTranscribe()
                    TouchZone.MIDDLE -> handleTouchEnd()
                }
                view.setCancelHover(false)
                isCancelHovering = false
                touchZone = TouchZone.MIDDLE
                // Always try to snap on release. If recording just started (handsfree
                // tap, or PTT activation), state is non-idle and scheduleSnap defers
                // to the state observer for the post-wave snap.
                scheduleSnap()
                true
            }
            else -> false
        }
    }

    private fun handleTouchEnd() {
        when (currentMode) {
            RecordingMode.PUSH_TO_TALK -> {
                if (!pttArmed) {
                    // The user released before the activation delay elapsed (or
                    // moved out of slop and cancelled it). Just a tap or a drag —
                    // no recording happened, nothing to stop.
                    return
                }
                // PTT: activation fired and recording is in flight. Release ends it.
                if (isCancelHovering) cancelRecording() else stopAndTranscribe()
            }
            RecordingMode.HANDSFREE -> {
                if (isCancelHovering) {
                    // Drag-to-cancel-zone always wins, even mid-drag.
                    cancelRecording()
                    return
                }
                if (hasMovedBeyondSlop) {
                    // The user moved the bubble. Don't toggle recording state.
                    return
                }
                // Pure tap → toggle.
                val app = applicationContext as SrizonApp
                when (app.recordingCoordinator.state.value) {
                    is RecordingState.Idle, is RecordingState.Error -> {
                        if (hasRecordPermission()) startRecording()
                    }
                    is RecordingState.Recording -> stopAndTranscribe()
                    is RecordingState.Transcribing -> {
                        // Ignore taps mid-transcription — the request is in flight.
                    }
                }
            }
        }
    }

    private fun isInCancelZone(rawY: Float): Boolean {
        val display = resources.displayMetrics
        return rawY < display.heightPixels * CANCEL_ZONE_FRACTION
    }

    private fun computeTouchZone(view: BubbleView, x: Float): TouchZone {
        if (!view.hasInlineButtons()) return TouchZone.MIDDLE
        val zone = view.inlineButtonZoneWidth()
        return when {
            x < zone -> TouchZone.LEFT_BUTTON
            x > view.width - zone -> TouchZone.RIGHT_BUTTON
            else -> TouchZone.MIDDLE
        }
    }

    private fun handleMiddleMove(
        view: BubbleView,
        params: WindowManager.LayoutParams,
        event: MotionEvent,
    ) {
        val dxRaw = event.rawX - view.dragState.touchX
        val dyRaw = event.rawY - view.dragState.touchY
        if (!hasMovedBeyondSlop &&
            (kotlin.math.abs(dxRaw) > touchSlopPx || kotlin.math.abs(dyRaw) > touchSlopPx)
        ) {
            hasMovedBeyondSlop = true
            // PTT: if the user moves before the activation delay elapses,
            // they're repositioning, not recording. Cancel the pending start.
            if (currentMode == RecordingMode.PUSH_TO_TALK && !pttArmed) {
                pttArmJob?.cancel()
                pttArmJob = null
            }
        }
        val dx = dxRaw.roundToInt()
        val dy = dyRaw.roundToInt()
        val screenH = windowManager.currentWindowMetrics.bounds.height()
        val screenW = windowManager.currentWindowMetrics.bounds.width()
        val viewWidth = currentBubbleWidth()
        params.x = (view.dragState.startX + dx).coerceIn(0, screenW - viewWidth)
        val maxY = screenH - BubbleView.PILL_PX - edgeGestureMargin()
        params.y = (view.dragState.startY + dy).coerceIn(edgeGestureMargin(), maxY)
        runCatching { windowManager.updateViewLayout(view, params) }
        isCancelHovering = isInCancelZone(event.rawY)
        view.setCancelHover(isCancelHovering)
    }

    /** Symmetric vertical buffer used at both the top and bottom of the screen.
     *
     * Derived from the device's status-bar height + a small constant, then halved.
     * Using the status bar (not the nav bar) for the bottom too keeps the gap visually
     * symmetric — on gesture-nav devices `navigation_bar_height` reports near zero,
     * which made the bottom clamp invisible. */
    private fun edgeGestureMargin(): Int {
        val statusBarId = resources.getIdentifier("status_bar_height", "dimen", "android")
        val statusBar = if (statusBarId > 0) resources.getDimensionPixelSize(statusBarId) else 0
        val buffer = (24 * resources.displayMetrics.density).toInt()
        return (statusBar + buffer) / 2
    }

    private fun currentBubbleWidth(): Int {
        val app = applicationContext as SrizonApp
        val state = app.recordingCoordinator.state.value
        return if (state is RecordingState.Recording || state is RecordingState.Transcribing) {
            BubbleView.WIDE_PX
        } else {
            BubbleView.PILL_PX
        }
    }

    /**
     * Schedules a delayed, animated snap of the bubble to whichever side (left/right)
     * its center is currently nearer to.
     *
     *  - If currently idle, schedule the snap immediately (after [SNAP_START_DELAY_MS]).
     *  - If currently in wave mode, no-op — the state observer will call back when
     *    the wave closes and snap from there.
     *
     * Cancelable: a fresh ACTION_DOWN cancels [snapJob] so the user's touch wins.
     */
    private fun scheduleSnap() {
        snapJob?.cancel()
        val app = applicationContext as SrizonApp
        val state = app.recordingCoordinator.state.value
        val isWide = state is RecordingState.Recording || state is RecordingState.Transcribing
        if (isWide) {
            // Wait for the state observer to call us back when wave mode ends.
            return
        }
        snapJob = scope.launch {
            delay(SNAP_START_DELAY_MS)
            animateSnap()
        }
    }

    private suspend fun animateSnap() {
        val view = bubbleView ?: return
        val params = layoutParams ?: return
        val bounds = windowManager.currentWindowMetrics.bounds
        val screenW = bounds.width()
        val screenH = bounds.height()
        val gap = (EDGE_GAP_DP * resources.displayMetrics.density).toInt()
        val width = BubbleView.PILL_PX
        val centerX = params.x + width / 2
        val onRightHalf = centerX > screenW / 2

        val targetX = if (onRightHalf) screenW - width - gap else gap
        val maxY = screenH - width - edgeGestureMargin()
        val targetY = params.y.coerceIn(edgeGestureMargin(), maxY)

        val startX = params.x
        val startY = params.y
        val startTime = System.currentTimeMillis()

        while (true) {
            val elapsed = (System.currentTimeMillis() - startTime).toFloat()
            val t = (elapsed / SNAP_ANIMATION_MS).coerceIn(0f, 1f)
            val eased = easeOutCubic(t)
            params.x = (startX + (targetX - startX) * eased).toInt()
            params.y = (startY + (targetY - startY) * eased).toInt()
            view.dragState.startX = params.x
            view.dragState.startY = params.y
            runCatching { windowManager.updateViewLayout(view, params) }
            if (t >= 1f) break
            delay(FRAME_INTERVAL_MS)
        }
    }

    private fun easeOutCubic(t: Float): Float {
        val inv = 1f - t
        return 1f - inv * inv * inv
    }

    private fun hasRecordPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun startRecording() {
        val app = applicationContext as SrizonApp
        app.recordingCoordinator.startRecording()
    }

    private fun stopAndTranscribe() {
        val app = applicationContext as SrizonApp
        app.recordingCoordinator.stopAndTranscribe()
    }

    private fun cancelRecording() {
        val app = applicationContext as SrizonApp
        app.recordingCoordinator.cancel()
    }

    private fun toggleFromTile() {
        val app = applicationContext as SrizonApp
        val state = app.recordingCoordinator.state.value
        if (state is RecordingState.Recording) stopAndTranscribe()
        else if (state is RecordingState.Idle) startRecording()
    }

    private fun observeCoordinator() {
        val app = applicationContext as SrizonApp
        stateJob?.cancel()
        stateJob = scope.launch {
            var previous: RecordingState = RecordingState.Idle
            app.recordingCoordinator.state.collect { state ->
                adjustForWidthChange(previous, state)
                val wasIdle = previous is RecordingState.Idle
                val nowIdle = state is RecordingState.Idle
                if (!wasIdle && nowIdle) {
                    // Just returned to idle (from recording / transcribing / error) —
                    // schedule the snap so the bubble drifts to a side.
                    scheduleSnap()
                }
                previous = state
                bubbleView?.render(state)
                applyOpacity()
            }
        }
        errorJob?.cancel()
        errorJob = scope.launch {
            app.recordingCoordinator.errors.collect { msg -> bubbleView?.flashError(msg) }
        }
    }

    /** When the bubble widens (idle → recording/transcribing) and is sitting on the
     * right half of the screen, shift its X position left by [BubbleView.WIDTH_DELTA]
     * so the wider pill grows to the *left* instead of running off the right edge.
     * Reverses the shift on the way back to idle.
     *
     * Whenever [params.x] is mutated here, we also update [BubbleView.dragState.startX]
     * by the same amount. Without that sync, an ACTION_MOVE arriving after the shift
     * would recompute `params.x = startX + dx` using the *pre-shift* baseline and
     * effectively undo the shift, leaving the bubble off-screen on un-shift. */
    private fun adjustForWidthChange(from: RecordingState, to: RecordingState) {
        val params = layoutParams ?: return
        val view = bubbleView ?: return
        val wasWide = from is RecordingState.Recording || from is RecordingState.Transcribing
        val isWide = to is RecordingState.Recording || to is RecordingState.Transcribing
        if (wasWide == isWide) return

        if (!wasWide && isWide) {
            val screenW = windowManager.currentWindowMetrics.bounds.width()
            val centerX = params.x + BubbleView.PILL_PX / 2
            if (centerX > screenW / 2) {
                params.x -= BubbleView.WIDTH_DELTA
                view.dragState.startX -= BubbleView.WIDTH_DELTA
                shiftedLeftForWideState = true
            }
        } else if (wasWide && !isWide && shiftedLeftForWideState) {
            params.x += BubbleView.WIDTH_DELTA
            view.dragState.startX += BubbleView.WIDTH_DELTA
            shiftedLeftForWideState = false
        }
        runCatching { windowManager.updateViewLayout(view, params) }
    }

    companion object {
        const val ACTION_TOGGLE_RECORDING = "srizon.bubble.toggle"
        const val ACTION_STOP = "srizon.bubble.stop"
        private const val NOTIF_ID = 42
        private const val CANCEL_ZONE_FRACTION = 0.12f
        private const val EDGE_GAP_DP = 12
        private const val SNAP_START_DELAY_MS = 300L
        private const val SNAP_ANIMATION_MS = 600f
        private const val FRAME_INTERVAL_MS = 16L
        private const val PTT_ACTIVATION_DELAY_MS = 400L
    }
}
