package com.niki914.zafiro.message

import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-memory capability detector and explicit text-fill bridge for chat-app Accessibility fallback.
 *
 * Visible chat text is never retained. The fill handler is installed only while Zafiro's
 * AccessibilityService is alive and re-reads the current foreground root at invocation time.
 * This fallback can fill an editable box after explicit user action, but never clicks Send.
 */
object ChatAccessibilityFallback {
    private const val MAX_VISITED_NODES = 400

    data class Snapshot(
        val packageName: String = "",
        val editableInputAvailable: Boolean = false,
        val sendButtonAvailable: Boolean = false,
        val updatedAtElapsedMs: Long = 0L,
    ) {
        val readyForManualFallback: Boolean
            get() = packageName in SUPPORTED_PACKAGES && editableInputAvailable
    }

    private val mutableSnapshot = MutableStateFlow(Snapshot())
    val snapshot: StateFlow<Snapshot> = mutableSnapshot.asStateFlow()

    @Volatile
    private var fillHandler: ((expectedPackage: String, text: String) -> Boolean)? = null

    fun installFillHandler(handler: (expectedPackage: String, text: String) -> Boolean) {
        fillHandler = handler
    }

    fun clearFillHandler() {
        fillHandler = null
    }

    fun fillCurrentInput(expectedPackage: String, text: String): Boolean {
        val normalizedText = text.trim()
        if (expectedPackage !in SUPPORTED_PACKAGES || normalizedText.isEmpty()) return false
        return fillHandler?.invoke(expectedPackage, normalizedText) == true
    }

    fun update(packageName: String?, root: AccessibilityNodeInfo?) {
        val normalizedPackage = packageName.orEmpty()
        if (normalizedPackage !in SUPPORTED_PACKAGES || root == null) {
            clear()
            return
        }

        var editable = false
        var sendButton = false
        var visited = 0
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)

        while (queue.isNotEmpty() && visited < MAX_VISITED_NODES && !(editable && sendButton)) {
            val node = queue.removeFirst()
            visited += 1

            if (isEditableInput(node)) editable = true
            if (isSendControl(node)) sendButton = true

            for (index in 0 until node.childCount) {
                node.getChild(index)?.let(queue::addLast)
            }
        }

        mutableSnapshot.value = Snapshot(
            packageName = normalizedPackage,
            editableInputAvailable = editable,
            sendButtonAvailable = sendButton,
            updatedAtElapsedMs = SystemClock.elapsedRealtime(),
        )
    }

    fun fillEditableInput(root: AccessibilityNodeInfo?, text: String): Boolean {
        val normalizedText = text.trim()
        if (root == null || normalizedText.isEmpty()) return false

        var visited = 0
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty() && visited < MAX_VISITED_NODES) {
            val node = queue.removeFirst()
            visited += 1
            if (isEditableInput(node)) {
                val args = android.os.Bundle().apply {
                    putCharSequence(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                        normalizedText,
                    )
                }
                if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return true
            }
            for (index in 0 until node.childCount) {
                node.getChild(index)?.let(queue::addLast)
            }
        }
        return false
    }

    fun clear() {
        mutableSnapshot.value = Snapshot()
    }

    private fun isEditableInput(node: AccessibilityNodeInfo): Boolean =
        node.isEditable || node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT }

    private fun isSendControl(node: AccessibilityNodeInfo): Boolean {
        if (!node.isClickable) return false
        val candidates = sequenceOf(node.text, node.contentDescription)
            .mapNotNull { it?.toString()?.trim()?.lowercase() }
            .filter(String::isNotEmpty)
        return candidates.any { label ->
            label == "发送" || label == "send" || label == "发送消息" || label == "send message"
        }
    }

    val SUPPORTED_PACKAGES = setOf(
        "com.tencent.mm",
        "com.tencent.mobileqq",
        "com.tencent.tim",
    )
}
