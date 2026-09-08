package com.niki914.zafiro.openai.auth

/**
 * Experimental ChatGPT/Codex device-login support.
 *
 * This is intentionally kept separate from the normal OpenAI API-key provider:
 * the upstream device-auth/Codex endpoints are not a documented generic Android
 * OAuth API and may change without notice.
 */
data class OpenAiDeviceCodeSession(
    val deviceAuthId: String,
    val userCode: String,
    val verificationUri: String,
    val intervalSeconds: Long,
    val expiresAtEpochMs: Long,
)

data class OpenAiAccountIdentity(
    val localAccountId: String,
    val email: String?,
    val chatgptAccountId: String?,
)

data class OpenAiTokenResponse(
    val accessToken: String,
    val refreshToken: String?,
    val idToken: String?,
    val expiresAtEpochMs: Long,
)

data class StoredOpenAiAccount(
    val localAccountId: String,
    val email: String?,
    val chatgptAccountId: String?,
    val refreshToken: String,
    val idToken: String?,
)

data class OpenAiRuntimeCredential(
    val accessToken: String,
    val chatgptAccountId: String?,
    val email: String?,
    val expiresAtEpochMs: Long,
)

sealed interface OpenAiDevicePollResult {
    data object Pending : OpenAiDevicePollResult
    data object AccessDenied : OpenAiDevicePollResult
    data object Expired : OpenAiDevicePollResult
    data class Authorized(
        val authorizationCode: String,
        val codeVerifier: String,
    ) : OpenAiDevicePollResult

    data class Failed(val message: String) : OpenAiDevicePollResult
}

sealed interface OpenAiLoginResult {
    data object Pending : OpenAiLoginResult
    data object AccessDenied : OpenAiLoginResult
    data object Expired : OpenAiLoginResult
    data class Success(val account: OpenAiAccountIdentity) : OpenAiLoginResult
    data class Failed(val message: String) : OpenAiLoginResult
}
