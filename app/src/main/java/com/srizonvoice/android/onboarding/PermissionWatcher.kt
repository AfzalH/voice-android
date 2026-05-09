package com.srizonvoice.android.onboarding

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.core.content.ContextCompat
import com.srizonvoice.android.insertion.SrizonAccessibilityService

/**
 * Live snapshot of every permission the onboarding wizard cares about.
 * Read this on every `onResume` so the user can leave to a system settings page
 * and have the wizard reflect the new state when they come back.
 */
data class PermissionsSnapshot(
    val microphone: Boolean,
    val overlay: Boolean,
    val accessibility: Boolean,
)

class PermissionWatcher(private val context: Context) {

    fun snapshot(): PermissionsSnapshot = PermissionsSnapshot(
        microphone = hasMicrophone(),
        overlay = hasOverlay(),
        accessibility = hasAccessibility(),
    )

    fun hasMicrophone(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    fun hasOverlay(): Boolean = Settings.canDrawOverlays(context)

    fun hasAccessibility(): Boolean {
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val target = ComponentName(context, SrizonAccessibilityService::class.java).flattenToString()
        // Use the user-settings string directly — `getEnabledAccessibilityServiceList` filters by
        // feedback type and can miss us depending on OEM tweaks.
        val raw = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty()
        return raw.split(":").any { entry -> entry.equals(target, ignoreCase = true) }
    }
}
