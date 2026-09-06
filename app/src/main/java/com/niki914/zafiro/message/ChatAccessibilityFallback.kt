package com.niki914.zafiro.message

import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-memory capability detector for the chat-app Accessibility fallback.
 *
 * This first-stage bridge deliberately does not retain visible chat text. It only records whether
 * the foreground QQ/WeChat/TIM window exposes an editable input and a plausible send control.
 * RemoteInput remains the preferred reply path; this is only compatibility groundwork.
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
            get() = packageName in SUPPORTED_PACKAGES &&
                editableInputAvailable && sendButtonAvailable
    }

    private val mutableSnapshot = MutableStateFlow(Snapshot())
    val snapshot: StateFlow<Snapshot> = mutableSnapshot.asStateFlow()

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

            if (node.isEditable || node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT }) {
                editable = true
            }
            if (isSendControl(node)) {
                sendButton = true
            }

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

    fun clear() {
        mutableSnapshot.value = Snapshot()
    }

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
