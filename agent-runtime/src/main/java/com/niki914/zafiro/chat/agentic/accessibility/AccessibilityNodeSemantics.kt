package com.niki914.zafiro.chat.agentic.accessibility

import android.view.accessibility.AccessibilityNodeInfo

/**
 * Compatibility semantics for text-entry widgets.
 *
 * Some apps expose a custom EditText that can handle ACTION_SET_TEXT but do not
 * consistently set AccessibilityNodeInfo.isEditable. Treating those nodes as
 * editable lets the agent discover and use the real input control without
 * falling back to shell `input text` (which is poor for Unicode and can expose
 * message contents in command arguments).
 */
internal fun AccessibilityNodeInfo.supportsTextEditing(): Boolean {
    if (isEditable) return true

    val classNameText = className?.toString().orEmpty()
    if (classNameText.contains("EditText", ignoreCase = true)) return true

    return actionList.any { action -> action.id == AccessibilityNodeInfo.ACTION_SET_TEXT }
}
