package com.niki914.zafiro.message

import android.content.Context
import com.niki914.logging.Logger
import com.niki914.zafiro.chat.EphemeralLlmClient
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
import java.util.concurrent.ConcurrentHashMap

/**
 * Process-local orchestration for notification-based chat assistance.
 *
 * Message bodies and generated replies are kept in memory only. Policy is evaluated before any
 * model call and the RemoteInput send path evaluates it again immediately before dispatch.
 */
object MessageAssistantCoordinator {
    private const val LOG_TAG = "niki914_nexus_MessageAssistantCoordinator"
    private const val AUTO_REPLY_COOLDOWN_MS = 30_000L
    private const val MAX_REPLY_CHARS = 500

    data class Suggestion(
        val packageName: String,
        val conversation: String,
        val sourcePostedAtMs: Long,
        val text: String,
        val generatedAtMs: Long,
        val autoSent: Boolean,
    )

    private val mutableLatestSuggestion = MutableStateFlow<Suggestion?>(null)
    val latestSuggestion: StateFlow<Suggestion?> = mutableLatestSuggestion.asStateFlow()

    private val lastAutoReplyAtByConversation = ConcurrentHashMap<String, Long>()

    fun start(scope: CoroutineScope, context: Context) {
        val appContext = context.applicationContext
        scope.launch {
            IncomingMessageBus.events.collectLatest { message ->
                handle(appContext, message)
            }
        }
    }

    private suspend fun handle(context: Context, message: IncomingChatMessage) {
        val initialDecision = runCatching { MessageAssistantSettings.evaluate(message) }
            .getOrElse {
                Logger.w(LOG_TAG, "policy evaluation failed ${it.message}")
                return
            }
        if (initialDecision != MessageAssistantSettings.Decision.SUGGEST_ONLY &&
            initialDecision != MessageAssistantSettings.Decision.AUTO_REPLY_ALLOWED
        ) {
            if (initialDecision != MessageAssistantSettings.Decision.IGNORE) {
                recordBlocked(initialDecision)
            }
            return
        }

        val generated = runCatching {
            EphemeralLlmClient.generateText(
                query = buildQuery(message),
                systemPrompt = SYSTEM_PROMPT,
            )
        }.map(::sanitizeGeneratedReply)
            .getOrElse {
                Logger.w(LOG_TAG, "reply generation failed ${it.message}")
                return
            }

        if (generated.isBlank()) {
            Logger.w(LOG_TAG, "reply generation returned blank output")
            return
        }

        var autoSent = false
        if (initialDecision == MessageAssistantSettings.Decision.AUTO_REPLY_ALLOWED) {
            if (!allowAutoReplyNow(message)) {
                recordBlocked(MessageAssistantSettings.Decision.BLOCKED_UNTRUSTED, "AUTO_REPLY_COOLDOWN")
            } else {
                val sendResult = IncomingMessageReplyRegistry.send(message, generated)
                autoSent = sendResult.isSuccess
                if (autoSent) {
                    lastAutoReplyAtByConversation[MessageAssistantSettings.conversationKey(message)] =
                        System.currentTimeMillis()
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

        val suggestion = Suggestion(
            packageName = message.packageName,
            conversation = message.conversation,
            sourcePostedAtMs = message.postedAtMs,
            text = generated,
            generatedAtMs = System.currentTimeMillis(),
            autoSent = autoSent,
        )
        mutableLatestSuggestion.value = suggestion
        MessageAssistantSuggestionNotifier.show(context, suggestion)
    }

    private fun recordBlocked(
        decision: MessageAssistantSettings.Decision,
        overrideCode: String? = null,
    ) {
        val code = overrideCode ?: when (decision) {
            MessageAssistantSettings.Decision.BLOCKED_SENSITIVE -> "MESSAGE_SENSITIVE_BLOCKED"
            MessageAssistantSettings.Decision.BLOCKED_PRIVACY -> "MESSAGE_PRIVACY_BLOCKED"
            MessageAssistantSettings.Decision.BLOCKED_UNTRUSTED -> "MESSAGE_UNTRUSTED_BLOCKED"
            MessageAssistantSettings.Decision.BLOCKED_NO_SYSTEM_REPLY -> "MESSAGE_NO_SYSTEM_REPLY"
            else -> "MESSAGE_POLICY_BLOCKED"
        }
        SecurityAuditLog.record(
            kind = SecurityAuditKind.MESSAGE_REPLY_BLOCKED,
            riskLevel = if (decision == MessageAssistantSettings.Decision.BLOCKED_SENSITIVE) {
                SecurityRiskLevel.HIGH
            } else {
                SecurityRiskLevel.LOW
            },
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

    internal fun sanitizeGeneratedReply(value: String): String =
        value.trim().take(MAX_REPLY_CHARS)

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
