package com.srizonvoice.android.insertion

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Text insertion strategy.
 *
 * 1. Find focused editable node.
 * 2. **Primary path: ACTION_PASTE.** Snapshot the clipboard, write the transcript,
 *    fire the field's own paste action, restore the clipboard 500 ms later. Goes
 *    through the field's normal text-insertion code path, so multi-line behavior,
 *    composing-region handling, IME state, and placeholder replacement all work
 *    the way the user expects. (Previously this was a fallback after
 *    ACTION_SET_TEXT — but that wholesale-replace path corrupts the IME's
 *    composing state on multi-line fields, breaking subsequent newline entry.)
 * 3. **Fallback: ACTION_SET_TEXT** for nodes that don't support paste (rare —
 *    some custom views or read-only-looking fields with workarounds). Hint-aware
 *    merge so a placeholder rendered into `text` doesn't get prepended.
 * 4. **No focused field:** copy to clipboard silently.
 *
 * Note on character-by-character typing: from an Accessibility Service on Android,
 * we can't inject keystrokes (`INJECT_EVENTS` is system-only). Real per-character
 * typing requires shipping our own IME, which is on the v2 roadmap.
 */
class TextInserter(
    private val context: Context,
    private val rootProvider: () -> AccessibilityNodeInfo?,
) {
    private val main = Handler(Looper.getMainLooper())
    private val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    fun insert(text: String) {
        if (text.isBlank()) return
        val focused = findFocusedNode()
        if (focused == null) {
            copyToClipboard(text)
            return
        }
        // Snapshot once so the success path can restore the user's prior clipboard
        // *only* if we actually inserted text. If everything fails, we want the
        // transcript to stay on the clipboard for manual paste — restoring would
        // race with our own `copyToClipboard` fallback and clear it ~500 ms later.
        val priorClipboard = snapshotClipboard()
        if (tryPaste(focused, text)) {
            scheduleClipboardRestore(priorClipboard)
            return
        }
        if (focused.isEditable && trySetTextWithMerge(focused, text)) {
            scheduleClipboardRestore(priorClipboard)
            return
        }
        // Couldn't insert anywhere. Make sure the transcript ends up on the
        // clipboard (tryPaste may have already done this; this is a safety net).
        copyToClipboard(text)
    }

    /**
     * Returns the focused node, preferring the input-focused one and falling back
     * to the accessibility-focused one. Note: we don't filter by [isEditable] here
     * — many WebView text fields don't report `isEditable=true` at the inner node
     * but are still pasteable (we walk up the tree in [tryPaste]).
     */
    private fun findFocusedNode(): AccessibilityNodeInfo? {
        val root = rootProvider() ?: return null
        return root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
    }

    private fun trySetTextWithMerge(node: AccessibilityNodeInfo, text: String): Boolean {
        val raw = node.text?.toString().orEmpty()
        val hint = node.hintText?.toString().orEmpty()
        // Some apps surface the placeholder as `text` when the field is empty.
        // If `text` matches `hint` (or the field is annotated as showing-hint via
        // `isShowingHintText` on API 26+), treat the existing content as empty so
        // we don't merge "Search…" + transcript = "Search…hello".
        val showingHint = node.isShowingHintText
        val existing = if (raw.isEmpty() || raw == hint || showingHint) "" else raw

        val cursor = if (existing.isEmpty()) {
            0
        } else {
            node.textSelectionEnd.coerceAtLeast(0).coerceAtMost(existing.length)
        }
        val merged = existing.substring(0, cursor) + text + existing.substring(cursor)

        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, merged) }
        val ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (!ok) return false

        val newCursor = cursor + text.length
        val selectionArgs = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, newCursor)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, newCursor)
        }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selectionArgs)
        return true
    }

    /**
     * Tries to paste [text] into [node] or an ancestor up to [PASTE_ANCESTOR_LIMIT]
     * levels up. The caller is responsible for scheduling clipboard restoration
     * only on success.
     *
     * Two refinements over the naive "just call performAction":
     *  - Focus the node before pasting. Some WebView virtual inputs only honor
     *    ACTION_PASTE while they're the focused element.
     *  - Only invoke paste on nodes that explicitly list ACTION_PASTE in their
     *    [AccessibilityNodeInfo.actionList]. Without this filter, ancestors
     *    sometimes return `true` from `performAction(ACTION_PASTE)` without
     *    actually pasting anything — the clipboard ends up populated, the user
     *    sees nothing in the field, and we incorrectly claim success.
     */
    private fun tryPaste(node: AccessibilityNodeInfo, text: String): Boolean {
        clipboard.setPrimaryClip(ClipData.newPlainText("transcript", text))

        // Best-effort focus on the leaf — needed by some WebView fields, harmless elsewhere.
        runCatching { node.performAction(AccessibilityNodeInfo.ACTION_FOCUS) }

        var current: AccessibilityNodeInfo? = node
        var levels = 0
        while (current != null && levels < PASTE_ANCESTOR_LIMIT) {
            if (current.supportsAction(AccessibilityNodeInfo.ACTION_PASTE) &&
                current.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            ) {
                return true
            }
            current = current.parent
            levels++
        }
        return false
    }

    private fun scheduleClipboardRestore(saved: ClipData?) {
        main.postDelayed({ runCatching { restoreClipboard(saved) } }, CLIPBOARD_RESTORE_DELAY_MS)
    }

    private fun AccessibilityNodeInfo.supportsAction(actionId: Int): Boolean =
        actionList.any { it.id == actionId }

    private fun copyToClipboard(text: String) {
        clipboard.setPrimaryClip(ClipData.newPlainText("transcript", text))
    }

    private fun snapshotClipboard(): ClipData? = clipboard.primaryClip

    private fun restoreClipboard(saved: ClipData?) {
        if (saved == null) clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
        else clipboard.setPrimaryClip(saved)
    }

    private companion object {
        const val CLIPBOARD_RESTORE_DELAY_MS = 500L
        const val PASTE_ANCESTOR_LIMIT = 8
    }
}
