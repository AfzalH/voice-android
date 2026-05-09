package com.srizonvoice.android.trigger.tile

import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.srizonvoice.android.R
import com.srizonvoice.android.SrizonApp
import com.srizonvoice.android.recording.RecordingState
import com.srizonvoice.android.trigger.bubble.BubbleService

/**
 * Quick Settings tile (spec §5d). Tapping it toggles the recording bubble.
 * State pulls from the shared [RecordingCoordinator] so the tile stays in sync
 * with the bubble UI.
 */
class DictationTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        refresh()
    }

    override fun onClick() {
        super.onClick()
        val app = applicationContext as SrizonApp
        if (!Settings.canDrawOverlays(this)) {
            // No overlay permission → unlock + jump back to the app's overlay setup.
            unlockAndRun {
                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
            }
            return
        }
        val state = app.recordingCoordinator.state.value
        val intent = Intent(this, BubbleService::class.java).apply {
            action = BubbleService.ACTION_TOGGLE_RECORDING
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
        if (state is RecordingState.Recording) {
            // Tile second-tap stops the in-flight recording.
            app.recordingCoordinator.stopAndTranscribe()
        }
        refresh()
    }

    private fun refresh() {
        val tile = qsTile ?: return
        val app = applicationContext as SrizonApp
        val state = app.recordingCoordinator.state.value
        tile.label = getString(R.string.tile_label)
        tile.icon = Icon.createWithResource(this, R.drawable.ic_mic)
        tile.state = when (state) {
            is RecordingState.Recording, is RecordingState.Transcribing -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }
        tile.contentDescription = getString(R.string.tile_label)
        tile.updateTile()
    }
}
