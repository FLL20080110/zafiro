package com.niki914.zafiro.chat

import com.niki914.okia.Okia
import com.niki914.okia.TurnOptions
import com.niki914.okia.error.RetryPolicy
import com.niki914.okia.loop.TurnResult
import com.niki914.okia.protocol.AnthropicMessagesProtocol
import com.niki914.okia.protocol.OpenAIChatCompletionCompat
import com.niki914.okia.protocol.OpenAIChatCompletionProtocol
import com.niki914.okia.protocol.OpenAIResponsesProtocol
import com.niki914.okia.tooling.DefaultToolRegistry
import com.niki914.zafiro.chat.agentic.accessibility.SensitivePageGuard
import com.niki914.zafiro.chat.agentic.stream.LlmStreamEventMapper
import com.niki914.zafiro.settings.RuntimeEnvironment
import com.niki914.zafiro.settings.model.LlmProtocol
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json

/**
 * One-shot LLM execution that is deliberately isolated from [LLMController]'s conversation tree.
 *
 * It has no tools, does not restore/export the main conversation and is never exposed as a memory
 * writer. This is intended for narrow background transformations such as composing a chat reply.
 */
object EphemeralLlmClient {
    private const val NO_IDLE_TIMEOUT_SECONDS = Long.MAX_VALUE / 1000

    suspend fun generateText(
        query: String,
        systemPrompt: String,
        agentId: String = "message-assistant",
    ): String {
        val normalizedQuery = query.trim()
        require(normalizedQuery.isNotEmpty()) { "Ephemeral query is blank" }

        val sensitivePage = SensitivePageGuard.evaluateCurrent()
        check(!sensitivePage.blocked) { "Sensitive page blocks ephemeral model access" }

        val gateway = RuntimeEnvironment.awaitSettingsGateway()
        val privacyPolicy = gateway.readPrivacyPolicy()
        check(privacyPolicy.allowCloudLlm) { "Privacy mode blocks cloud model access" }

        val llmConfig = gateway.readLlmConfig(agentId)
        check(llmConfig.endpoint.isNotBlank() && llmConfig.model.isNotBlank()) {
            LLMController.CONFIG_REQUIRED_MESSAGE
        }
        val protocol = LlmProtocol.fromWire(llmConfig.protocol)
        val endpoint = llmConfig.endpoint.ifBlank { defaultEndpoint(protocol) }
        val wireProtocol = when (protocol) {
            LlmProtocol.DeepSeek -> OpenAIChatCompletionProtocol()
            LlmProtocol.OpenAiChatCompletions ->
                OpenAIChatCompletionProtocol(Json, OpenAIChatCompletionCompat())
            LlmProtocol.OpenAiResponses -> OpenAIResponsesProtocol()
            LlmProtocol.AnthropicMessages -> AnthropicMessagesProtocol()
        }

        val session = Okia.open(wireProtocol, null) {
            this.endpoint = endpoint
            apiKey = llmConfig.apiKey
            model = llmConfig.model
            idleTimeoutSeconds = llmConfig.idleTimeoutSeconds ?: NO_IDLE_TIMEOUT_SECONDS
            retryPolicy = RetryPolicy(maxAttempts = llmConfig.retryMaxAttempts)
            // Critical isolation boundary: background reply composition can never call local/MCP
            // tools, even if tools are enabled for the user's normal Agent conversation.
            toolRegistry = DefaultToolRegistry()
        }

        var latestText = ""
        val startedAtMs = System.currentTimeMillis()
        try {
            val result = session.send(
                text = normalizedQuery,
                options = TurnOptions(systemPrompt = systemPrompt),
            ) { event ->
                when (val mapped = LlmStreamEventMapper.map(event, startedAtMs)) {
                    is LlmStreamEvent.TextDelta -> latestText = mapped.fullText
                    is LlmStreamEvent.Error -> {
                        throw mapped.throwable
                            ?: IllegalStateException(mapped.message ?: "Ephemeral LLM turn failed")
                    }
                    else -> Unit
                }
            }
            if (result is TurnResult.Failed) {
                throw result.error.cause ?: IllegalStateException(result.error.message)
            }
            return latestText.trim().also {
                check(it.isNotEmpty()) { "Ephemeral LLM returned no text" }
            }
        } catch (e: CancellationException) {
            throw e
        } finally {
            session.close()
        }
    }

    private fun defaultEndpoint(protocol: LlmProtocol): String = when (protocol) {
        LlmProtocol.DeepSeek -> "https://api.deepseek.com/chat/completions"
        LlmProtocol.OpenAiChatCompletions -> "https://api.openai.com/v1/chat/completions"
        LlmProtocol.OpenAiResponses -> "https://api.openai.com/v1/responses"
        LlmProtocol.AnthropicMessages -> "https://api.anthropic.com/v1/messages"
    }
}
