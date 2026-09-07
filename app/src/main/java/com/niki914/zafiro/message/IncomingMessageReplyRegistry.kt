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
 * PendingIntents/RemoteInputs are never persisted. A handle expires after a short window and every
 * send path re-checks durable local policy immediately before dispatching.
 */
object IncomingMessageReplyRegistry {
    private const val HANDLE_TTL_MS = 5 * 60 * 1000L
    private const val MAX_ACTIVE_HANDLES = 128

    private data class Entry(
        val action: Notification.Action,
        val createdAtElapsedMs: Long,
    )

    private val entries = ConcurrentHashMap<String, Entry>()

    fun register(action: Notification.Action): String? {
        val remoteInputs = action.remoteInputs.orEmpty()
        if (remoteInputs.none { it.allowFreeFormInput && it.resultKey.isNotBlank() }) return null
        pruneExpired()
        trimToCapacity()
        val id = UUID.randomUUID().toString()
        entries[id] = Entry(action, SystemClock.elapsedRealtime())
        return id
    }

    /** Automatic replies require the full auto-reply allowlist policy. */
    suspend fun send(message: IncomingChatMessage, replyText: String): Result<Unit> {
        val decision = MessageAssistantSettings.evaluate(message)
        if (decision != MessageAssistantSettings.Decision.AUTO_REPLY_ALLOWED) {
            return Result.failure(IllegalStateException("Reply blocked by policy: $decision"))
        }
        return dispatch(message, replyText)
    }

    /**
     * Explicit user action from Zafiro's suggestion notification.
     *
     * The click itself is one-time approval, so permanent trusted-conversation membership is not
     * required. It still fails closed for disabled app/mode, privacy mode, sensitive content,
     * missing RemoteInput capability, or an expired reply handle.
     */
    suspend fun sendApproved(message: IncomingChatMessage, replyText: String): Result<Unit> {
        val policy = MessageAssistantSettings.snapshot()
        if (policy.mode == MessageAssistantSettings.Mode.OFF ||
            message.packageName !in policy.enabledPackages
        ) {
            return Result.failure(IllegalStateException("Manual reply blocked: assistant disabled"))
        }
        if (policy.privacyModeEnabled) {
            return Result.failure(IllegalStateException("Manual reply blocked: privacy mode"))
        }
        if (message.sensitive) {
            return Result.failure(IllegalStateException("Manual reply blocked: sensitive message"))
        }
        if (!message.systemReplyAvailable) {
            return Result.failure(IllegalStateException("Manual reply blocked: no system reply"))
        }
        return dispatch(message, replyText)
    }

    private fun dispatch(message: IncomingChatMessage, replyText: String): Result<Unit> {
        val text = replyText.trim()
        if (text.isEmpty()) return Result.failure(IllegalArgumentException("Reply text is empty"))

        val handleId = message.replyHandleId?.takeIf(String::isNotBlank)
            ?: return Result.failure(IllegalStateException("No system reply handle"))

        // Treat every RemoteInput handle as a one-shot capability. Consume it before touching the
        // PendingIntent so a cancelled/failed dispatch cannot accidentally leave a replayable
        // capability behind for a later retry under different UI or policy state.
        val entry = entries.remove(handleId)
            ?: return Result.failure(IllegalStateException("Reply handle unavailable, expired, or already used"))
        val ageMs = SystemClock.elapsedRealtime() - entry.createdAtElapsedMs
        if (ageMs < 0L || ageMs > HANDLE_TTL_MS) {
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
        }
    }

    fun clear() {
        entries.clear()
    }

    private fun pruneExpired() {
        val now = SystemClock.elapsedRealtime()
        entries.entries.removeIf {
            val ageMs = now - it.value.createdAtElapsedMs
            ageMs < 0L || ageMs > HANDLE_TTL_MS
        }
    }

    /**
     * Notification storms must not leave an unbounded number of live PendingIntent capabilities in
     * memory. Evict the oldest handles before admitting another one; an evicted handle simply falls
     * back to suggestion-only behavior if the user later tries to use it.
     */
    private fun trimToCapacity() {
        while (entries.size >= MAX_ACTIVE_HANDLES) {
            val oldest = entries.entries.minByOrNull { it.value.createdAtElapsedMs } ?: return
            entries.remove(oldest.key, oldest.value)
        }
    }
}
