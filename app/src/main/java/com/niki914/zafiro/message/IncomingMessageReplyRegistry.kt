package com.niki914.zafiro.message

import android.app.Notification
import android.app.RemoteInput
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import com.niki914.zafiro.repo.MessageAssistantSettings
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Short-lived in-memory registry for notification RemoteInput actions.
 *
 * PendingIntents/RemoteInputs are never persisted. A handle expires after a short window and the
 * send path always re-checks MessageAssistantSettings before dispatching.
 */
object IncomingMessageReplyRegistry {
    private const val HANDLE_TTL_MS = 5 * 60 * 1000L

    private data class Entry(
        val action: Notification.Action,
        val createdAtElapsedMs: Long,
    )

    private val entries = ConcurrentHashMap<String, Entry>()

    fun register(action: Notification.Action): String? {
        val remoteInputs = action.remoteInputs.orEmpty()
        if (remoteInputs.none { it.allowFreeFormInput && it.resultKey.isNotBlank() }) return null
        pruneExpired()
        val id = UUID.randomUUID().toString()
        entries[id] = Entry(action, SystemClock.elapsedRealtime())
        return id
    }

    suspend fun send(message: IncomingChatMessage, replyText: String): Result<Unit> {
        val text = replyText.trim()
        if (text.isEmpty()) return Result.failure(IllegalArgumentException("Reply text is empty"))

        val decision = MessageAssistantSettings.evaluate(message)
        if (decision != MessageAssistantSettings.Decision.AUTO_REPLY_ALLOWED) {
            return Result.failure(IllegalStateException("Reply blocked by policy: $decision"))
        }

        val handleId = message.replyHandleId?.takeIf(String::isNotBlank)
            ?: return Result.failure(IllegalStateException("No system reply handle"))
        val entry = entries[handleId]
            ?: return Result.failure(IllegalStateException("Reply handle unavailable or expired"))
        if (SystemClock.elapsedRealtime() - entry.createdAtElapsedMs > HANDLE_TTL_MS) {
            entries.remove(handleId)
            return Result.failure(IllegalStateException("Reply handle expired"))
        }

        val inputs = entry.action.remoteInputs.orEmpty()
            .filter { it.allowFreeFormInput && it.resultKey.isNotBlank() }
        if (inputs.isEmpty()) return Result.failure(IllegalStateException("No free-form RemoteInput"))

        return runCatching {
            val results = Bundle().apply {
                inputs.forEach { input -> putCharSequence(input.resultKey, text) }
            }
            val intent = Intent().addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            RemoteInput.addResultsToIntent(inputs.toTypedArray(), intent, results)
            entry.action.actionIntent.send(null, 0, intent)
            entries.remove(handleId)
        }
    }

    private fun pruneExpired() {
        val now = SystemClock.elapsedRealtime()
        entries.entries.removeIf { now - it.value.createdAtElapsedMs > HANDLE_TTL_MS }
    }
}
