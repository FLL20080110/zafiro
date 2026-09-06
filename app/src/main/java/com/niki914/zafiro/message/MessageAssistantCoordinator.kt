package com.niki914.zafiro.message

import com.niki914.logging.Logger
import com.niki914.zafiro.chat.EphemeralLlmClient
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

    fun start(scope: CoroutineScope) {
        scope.launch {
            IncomingMessageBus.events.collectLatest { message ->
                handle(message)
            }
        }
    }

    private suspend fun handle(message: IncomingChatMessage) {
        val initialDecision = runCatching { MessageAssistantSettings.evaluate(message) }
            .getOrElse {
                Logger.w(LOG_TAG, "policy evaluation failed ${it.message}")
                return
            }
        if (initialDecision != MessageAssistantSettings.Decision.SUGGEST_ONLY &&
            initialDecision != MessageAssistantSettings.Decision.AUTO_REPLY_ALLOWED
        ) {
            return
        }

        val generated = runCatching {
            EphemeralLlmClient.generateText(
                query = buildQuery(message),
                systemPrompt = SYSTEM_PROMPT,
            )
        }.getOrElse {
            Logger.w(LOG_TAG, "reply generation failed ${it.message}")
            return
        }

        var autoSent = false
        if (initialDecision == MessageAssistantSettings.Decision.AUTO_REPLY_ALLOWED &&
            allowAutoReplyNow(message)
        ) {
            autoSent = IncomingMessageReplyRegistry.send(message, generated).isSuccess
            if (autoSent) {
                lastAutoReplyAtByConversation[MessageAssistantSettings.conversationKey(message)] =
                    System.currentTimeMillis()
            }
        }

        mutableLatestSuggestion.value = Suggestion(
            packageName = message.packageName,
            conversation = message.conversation,
            sourcePostedAtMs = message.postedAtMs,
            text = generated,
            generatedAtMs = System.currentTimeMillis(),
            autoSent = autoSent,
        )
    }

    private fun allowAutoReplyNow(message: IncomingChatMessage): Boolean {
        val key = MessageAssistantSettings.conversationKey(message)
        val last = lastAutoReplyAtByConversation[key] ?: return true
        return System.currentTimeMillis() - last >= AUTO_REPLY_COOLDOWN_MS
    }

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
