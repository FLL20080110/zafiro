package com.niki914.zafiro.message

import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

/**
 * In-memory capability detector and explicit text-fill bridge for chat-app Accessibility fallback.
 *
 * Visible chat text is never retained. The fill handler is installed only while Zafiro's
 * AccessibilityService is alive and re-reads the current foreground root at invocation time.
 * This fallback can fill an editable box after explicit user action, but never clicks Send.
 *
 * Fail-closed rules:
 * - exactly one visible, enabled editable target must exist;
 * - the supported foreground package must still match; and
 * - the accessibility session id captured when the suggestion was created must still match.
 *
 * Window-state/window-set changes advance the session id. This invalidates suggestions after a
 * conversation/window switch without retaining chat titles or visible message text.
 */
object ChatAccessibilityFallback {
    private const val MAX_VISITED_NODES = 400

    data class Snapshot(
        val packageName: String = "",
        val editableInputCount: Int = 0,
        val sendButtonAvailable: Boolean = false,
        val sessionId: Long = 0L,
        val updatedAtElapsedMs: Long = 0L,
    ) {
        val editableInputAvailable: Boolean
            get() = editableInputCount == 1

        val ambiguousEditableInputs: Boolean
            get() = editableInputCount > 1

        val readyForManualFallback: Boolean
            get() = AccessibilityFallbackPolicy.canFill(
                expectedPackage = packageName,
                expectedSessionId = sessionId,
                currentPackage = packageName,
                currentSessionId = sessionId,
                editableInputCount = editableInputCount,
            )
    }

    private val sessionCounter = AtomicLong(0L)
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

    fun fillCurrentInput(expectedPackage: String, expectedSessionId: Long, text: String): Boolean {
        val normalizedText = text.trim()
        if (normalizedText.isEmpty()) return false

        val current = mutableSnapshot.value
        if (!AccessibilityFallbackPolicy.canFill(
                expectedPackage = expectedPackage,
                expectedSessionId = expectedSessionId,
                currentPackage = current.packageName,
                currentSessionId = current.sessionId,
                editableInputCount = current.editableInputCount,
            )
        ) {
            return false
        }
        return fillHandler?.invoke(expectedPackage, normalizedText) == true
    }

    fun update(
        packageName: String?,
        root: AccessibilityNodeInfo?,
        conversationBoundary: Boolean = false,
    ) {
        val normalizedPackage = packageName.orEmpty()
        if (normalizedPackage !in SUPPORTED_PACKAGES || root == null) {
            clear()
            return
        }

        val previous = mutableSnapshot.value
        val packageChanged = previous.packageName.isNotEmpty() && previous.packageName != normalizedPackage
        val sessionId = when {
            previous.sessionId <= 0L -> sessionCounter.incrementAndGet()
            conversationBoundary || packageChanged -> sessionCounter.incrementAndGet()
            else -> previous.sessionId
        }

        var editableCount = 0
        var sendButton = false
        var visited = 0
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)

        while (queue.isNotEmpty() && visited < MAX_VISITED_NODES) {
            val node = queue.removeFirst()
            visited += 1

            if (isEditableInput(node)) editableCount += 1
            if (!sendButton && isSendControl(node)) sendButton = true
            if (editableCount > 2) editableCount = 2

            for (index in 0 until node.childCount) {
                node.getChild(index)?.let(queue::addLast)
            }
        }

        mutableSnapshot.value = Snapshot(
            packageName = normalizedPackage,
            editableInputCount = editableCount,
            sendButtonAvailable = sendButton,
            sessionId = sessionId,
            updatedAtElapsedMs = SystemClock.elapsedRealtime(),
        )
    }

    fun fillEditableInput(root: AccessibilityNodeInfo?, text: String): Boolean {
        val normalizedText = text.trim()
        if (root == null || normalizedText.isEmpty()) return false

        var visited = 0
        val candidates = ArrayList<AccessibilityNodeInfo>(2)
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)

        while (queue.isNotEmpty() && visited < MAX_VISITED_NODES) {
            val node = queue.removeFirst()
            visited += 1
            if (isEditableInput(node)) {
                candidates += node
                if (candidates.size > 1) return false
            }
            for (index in 0 until node.childCount) {
                node.getChild(index)?.let(queue::addLast)
            }
        }

        val target = candidates.singleOrNull() ?: return false
        val args = android.os.Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                normalizedText,
            )
        }
        return target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    fun clear() {
        mutableSnapshot.value = Snapshot()
    }

    private fun isEditableInput(node: AccessibilityNodeInfo): Boolean {
        if (!node.isVisibleToUser || !node.isEnabled) return false
        return node.isEditable ||
            node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT }
    }

    private fun isSendControl(node: AccessibilityNodeInfo): Boolean {
        if (!node.isVisibleToUser || !node.isEnabled || !node.isClickable) return false
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
