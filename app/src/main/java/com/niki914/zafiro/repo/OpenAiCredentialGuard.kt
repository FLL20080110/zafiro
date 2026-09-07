package com.niki914.zafiro.repo

import java.net.URI

/**
 * Prevents Zafiro-internal/session credentials from being sent to OpenAI's API endpoint as if they
 * were API keys. Custom OpenAI-compatible endpoints are intentionally left alone because they may
 * define their own credential formats.
 */
internal object OpenAiCredentialGuard {
    internal const val ERROR_MESSAGE =
        "当前保存的凭据不是 OpenAI API Key，请在模型配置中填写有效的 OpenAI API Key。"

    fun requireUsable(endpoint: String, apiKey: String) {
        if (!isOfficialOpenAiEndpoint(endpoint)) return
        if (apiKey.trim().startsWith("zafiro:", ignoreCase = true)) {
            throw IllegalArgumentException(ERROR_MESSAGE)
        }
    }

    internal fun isOfficialOpenAiEndpoint(endpoint: String): Boolean {
        val uri = runCatching { URI(endpoint.trim()) }.getOrNull() ?: return false
        return uri.host?.equals("api.openai.com", ignoreCase = true) == true
    }
}
