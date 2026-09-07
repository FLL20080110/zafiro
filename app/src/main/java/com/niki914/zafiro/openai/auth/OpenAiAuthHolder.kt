package com.niki914.zafiro.openai.auth

import android.content.Context

/**
 * Process-wide owner for the experimental ChatGPT/Codex OAuth repository.
 *
 * Saved LLM configs contain only [MANAGED_API_KEY_SENTINEL] to indicate that
 * runtime credentials should be resolved here. Long-lived OAuth credentials
 * remain encrypted in [OpenAiTokenStore]; access tokens stay in memory.
 */
object OpenAiAuthHolder {
    const val MANAGED_API_KEY_SENTINEL = "zafiro:codex-oauth"
    const val CODEX_RESPONSES_ENDPOINT = "https://chatgpt.com/backend-api/codex/responses"

    @Volatile
    private var repository: OpenAiAuthRepository? = null

    fun init(context: Context) {
        if (repository != null) return
        synchronized(this) {
            if (repository == null) {
                repository = OpenAiAuthRepository(context.applicationContext)
            }
        }
    }

    fun requireRepository(): OpenAiAuthRepository {
        return repository ?: error("OpenAiAuthHolder has not been initialized")
    }

    fun isManagedOAuth(provider: String?, apiKey: String?): Boolean {
        return provider == "openai" && apiKey == MANAGED_API_KEY_SENTINEL
    }
}
