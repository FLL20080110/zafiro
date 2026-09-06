package com.niki914.zafiro.message

import android.content.Context
import android.os.SystemClock
import com.niki914.logging.Logger
import com.niki914.zafiro.chat.EphemeralLlmClient
import com.niki914.zafiro.chat.agentic.accessibility.SensitivePageGuard
import com.niki914.zafiro.chat.agentic.shell.SecurityAuditKind
import com.niki914.zafiro.chat.agentic.shell.SecurityAuditLog
import com.niki914.zafiro.chat.agentic.shell.SecurityRiskLevel
import com.niki914.zafiro.repo.MessageAssistantSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Process-local orchestration for notification-based chat assistance. */
object MessageAssistantCoordinator {
    private const val LOG_TAG = "niki914_nexus_MessageAssistantCoordinator"
    private const val AUTO_REPLY_COOLDOWN_MS = 30_000L
    private const val SUGGESTION_TTL_MS = 5 * 60 * 1000L
    private const val MAX_REPLY_CHARS = 500

    data class Suggestion(
        val id: String,
        val packageName: String,
        val conversation: String,
        val sourcePostedAtMs: Long,
        val text: String,
        val generatedAtMs: Long,
        val autoSent: Boolean,
        val manualSendAvailable: Boolean,
        val accessibilityFillAvailable: Boolean,
    )

    private data class PendingSuggestion(
        val message: IncomingChatMessage,
        val text: String,
        val createdAtElapsedMs: Long,
        val accessibilitySessionId: Long = 0L,
    )

    private val mutableLatestSuggestion = MutableStateFlow<Suggestion?>(null)
    val latestSuggestion: StateFlow<Suggestion?> = mutableLatestSuggestion.asStateFlow()
    private val lastAutoReplyAtByConversation = ConcurrentHashMap<String, Long>()
    private val pendingSuggestions = ConcurrentHashMap<String, PendingSuggestion>()

    fun start(scope: CoroutineScope, context: Context) {
        val appContext = context.applicationContext
        scope.launch { IncomingMessageBus.events.collectLatest { message -> handle(appContext, message) } }
    }

    fun clearTransientState() {
        mutableLatestSuggestion.value = null
        lastAutoReplyAtByConversation.clear()
        pendingSuggestions.clear()
    }

    fun invalidateAccessibilitySuggestions(context: Context) {
        val appContext = context.applicationContext
        val removed = pendingSuggestions.entries
            .filter { !it.value.message.systemReplyAvailable }
            .map { it.key to it.value }
        removed.forEach { (id, pending) ->
            if (pendingSuggestions.remove(id, pending)) {
                MessageAssistantSuggestionNotifier.dismiss(
                    appContext,
                    pending.message.packageName,
                    pending.message.conversation,
                )
            }
        }
        if (mutableLatestSuggestion.value?.accessibilityFillAvailable == true) {
            mutableLatestSuggestion.value = null
        }
    }

    suspend fun useSuggestion(context: Context, suggestionId: String): Result<Unit> {
        pruneSuggestions()
        val pending = pendingSuggestions.remove(suggestionId)
            ?: return Result.failure(IllegalStateException("Suggestion unavailable or expired"))
        val result = if (pending.message.systemReplyAvailable) {
            IncomingMessageReplyRegistry.sendApproved(pending.message, pending.text)
        } else {
            fillSuggestion(pending)
        }
        if (result.isSuccess) {
            val usedRemoteInput = pending.message.systemReplyAvailable
            SecurityAuditLog.record(
                kind = if (usedRemoteInput) SecurityAuditKind.MESSAGE_REPLY_SENT else SecurityAuditKind.PERMISSION_ALLOWED,
                riskLevel = SecurityRiskLevel.INFO,
                toolName = "message_assistant",
                policyCode = if (usedRemoteInput) "MESSAGE_MANUAL_REPLY_SENT" else "MESSAGE_SUGGESTION_FILLED",
                reason = if (usedRemoteInput) {
                    "User approved a one-time message assistant system reply."
                } else {
                    "User approved filling a generated suggestion into the current chat input."
                },
            )
            MessageAssistantSuggestionNotifier.dismiss(
                context.applicationContext,
                pending.message.packageName,
                pending.message.conversation,
            )
        } else {
            SecurityAuditLog.record(
                kind = SecurityAuditKind.MESSAGE_REPLY_BLOCKED,
                riskLevel = SecurityRiskLevel.LOW,
                toolName = "message_assistant",
                policyCode = "MESSAGE_MANUAL_ACTION_BLOCKED",
                reason = "User-approved message suggestion action was rejected by current local policy or UI state.",
            )
        }
        return result
    }

    private suspend fun fillSuggestion(pending: PendingSuggestion): Result<Unit> {
        val policy = MessageAssistantSettings.snapshot()
        if (!policy.accessibilityFallbackEnabled) {
            return Result.failure(IllegalStateException("Accessibility fill blocked: compatibility mode disabled"))
        }
        if (policy.mode == MessageAssistantSettings.Mode.OFF || pending.message.packageName !in policy.enabledPackages) {
            return Result.failure(IllegalStateException("Accessibility fill blocked: assistant disabled"))
        }
        if (policy.privacyModeEnabled) return Result.failure(IllegalStateException("Accessibility fill blocked: privacy mode"))
        if (pending.message.sensitive) return Result.failure(IllegalStateException("Accessibility fill blocked: sensitive message"))

        val sensitivePageDecision = SensitivePageGuard.evaluateCurrent()
        if (sensitivePageDecision.blocked) {
            return Result.failure(IllegalStateException("Accessibility fill blocked: sensitive page"))
        }

        val current = ChatAccessibilityFallback.snapshot.value
        if (current.packageName != pending.message.packageName ||
            !current.readyForManualFallback ||
            pending.accessibilitySessionId <= 0L ||
            current.sessionId != pending.accessibilitySessionId
        ) {
            return Result.failure(IllegalStateException("Accessibility fill blocked: chat session changed or input unavailable"))
        }
        return if (ChatAccessibilityFallback.fillCurrentInput(
                pending.message.packageName,
                pending.accessibilitySessionId,
                pending.text,
            )) {
            Result.success(Unit)
        } else {
            Result.failure(IllegalStateException("Accessibility fill failed"))
        }
    }

    private suspend fun handle(context: Context, message: IncomingChatMessage) {
        val initialPolicy = runCatching { MessageAssistantSettings.snapshot() }
            .getOrElse {
                Logger.w(LOG_TAG, "policy load failed ${it.message}")
                return
            }
        val initialDecision = MessageAssistantSettings.decide(initialPolicy, message)
        if (initialDecision != MessageAssistantSettings.Decision.SUGGEST_ONLY &&
            initialDecision != MessageAssistantSettings.Decision.AUTO_REPLY_ALLOWED
        ) {
            if (initialDecision != MessageAssistantSettings.Decision.IGNORE) recordBlocked(initialDecision)
            return
        }

        val generated = runCatching {
            EphemeralLlmClient.generateText(query = buildQuery(message), systemPrompt = SYSTEM_PROMPT)
        }.map(::sanitizeGeneratedReply).getOrElse {
            Logger.w(LOG_TAG, "reply generation failed ${it.message}")
            return
        }
        if (generated.isBlank()) return

        var autoSent = false
        if (initialDecision == MessageAssistantSettings.Decision.AUTO_REPLY_ALLOWED) {
            if (!allowAutoReplyNow(message)) {
                recordBlocked(MessageAssistantSettings.Decision.BLOCKED_UNTRUSTED, "AUTO_REPLY_COOLDOWN")
            } else {
                val sendResult = IncomingMessageReplyRegistry.send(message, generated)
                autoSent = sendResult.isSuccess
                if (autoSent) {
                    lastAutoReplyAtByConversation[MessageAssistantSettings.conversationKey(message)] = System.currentTimeMillis()
                    SecurityAuditLog.record(
                        kind = SecurityAuditKind.MESSAGE_REPLY_SENT,
                        riskLevel = SecurityRiskLevel.INFO,
                        toolName = "message_assistant",
                        policyCode = "MESSAGE_AUTO_REPLY_SENT",
                        reason = "Message assistant sent an authorized system reply.",
                    )
                } else {
                    SecurityAuditLog.record(
                        kind = SecurityAuditKind.MESSAGE_REPLY_BLOCKED,
                        riskLevel = SecurityRiskLevel.LOW,
                        toolName = "message_assistant",
                        policyCode = "MESSAGE_REPLY_SEND_FAILED",
                        reason = "Message assistant reply dispatch failed or was rejected.",
                    )
                }
            }
        }

        pruneSuggestions()
        val suggestionId = UUID.randomUUID().toString()
        val manualSendAvailable = !autoSent && message.systemReplyAvailable
        val fallback = ChatAccessibilityFallback.snapshot.value
        val accessibilityFillAvailable = initialPolicy.accessibilityFallbackEnabled &&
            !autoSent && !message.systemReplyAvailable &&
            fallback.packageName == message.packageName && fallback.readyForManualFallback
        if (manualSendAvailable || accessibilityFillAvailable) {
            pendingSuggestions[suggestionId] = PendingSuggestion(
                message = message,
                text = generated,
                createdAtElapsedMs = SystemClock.elapsedRealtime(),
                accessibilitySessionId = if (accessibilityFillAvailable) fallback.sessionId else 0L,
            )
        }
        val suggestion = Suggestion(
            id = suggestionId,
            packageName = message.packageName,
            conversation = message.conversation,
            sourcePostedAtMs = message.postedAtMs,
            text = generated,
            generatedAtMs = System.currentTimeMillis(),
            autoSent = autoSent,
            manualSendAvailable = manualSendAvailable,
            accessibilityFillAvailable = accessibilityFillAvailable,
        )
        mutableLatestSuggestion.value = suggestion
        MessageAssistantSuggestionNotifier.show(context, suggestion)
    }

    private fun recordBlocked(decision: MessageAssistantSettings.Decision, overrideCode: String? = null) {
        val code = overrideCode ?: when (decision) {
            MessageAssistantSettings.Decision.BLOCKED_SENSITIVE -> "MESSAGE_SENSITIVE_BLOCKED"
            MessageAssistantSettings.Decision.BLOCKED_PRIVACY -> "MESSAGE_PRIVACY_BLOCKED"
            MessageAssistantSettings.Decision.BLOCKED_UNTRUSTED -> "MESSAGE_UNTRUSTED_BLOCKED"
            MessageAssistantSettings.Decision.BLOCKED_NO_SYSTEM_REPLY -> "MESSAGE_NO_SYSTEM_REPLY"
            else -> "MESSAGE_POLICY_BLOCKED"
        }
        SecurityAuditLog.record(
            kind = SecurityAuditKind.MESSAGE_REPLY_BLOCKED,
            riskLevel = if (decision == MessageAssistantSettings.Decision.BLOCKED_SENSITIVE) SecurityRiskLevel.HIGH else SecurityRiskLevel.LOW,
            toolName = "message_assistant",
            policyCode = code,
            reason = "Message assistant reply was blocked by local policy.",
        )
    }

    private fun allowAutoReplyNow(message: IncomingChatMessage): Boolean {
        val key = MessageAssistantSettings.conversationKey(message)
        val last = lastAutoReplyAtByConversation[key] ?: return true
        return System.currentTimeMillis() - last >= AUTO_REPLY_COOLDOWN_MS
    }

    private fun pruneSuggestions() {
        val now = SystemClock.elapsedRealtime()
        pendingSuggestions.entries.removeIf { now - it.value.createdAtElapsedMs > SUGGESTION_TTL_MS }
    }

    internal fun sanitizeGeneratedReply(value: String): String = value.trim().take(MAX_REPLY_CHARS)

    private fun buildQuery(message: IncomingChatMessage): String = buildString {
        appendLine("Conversation: ${message.conversation}")
        if (message.sender.isNotBlank()) appendLine("Sender: ${message.sender}")
        append("Message: ${message.text}")
    }

    private val SYSTEM_PROMPT = """
        You compose a single short reply to an incoming chat message.
        Return only the reply text, with no analysis, markdown, labels, tool calls, or metadata.
        Match the language and tone of the incoming message. Do not claim actions were performed.
        If the message asks for passwords, verification codes, payment credentials, banking data,
        authentication secrets, or other sensitive credentials, refuse briefly instead of helping.
    """.trimIndent()
}
