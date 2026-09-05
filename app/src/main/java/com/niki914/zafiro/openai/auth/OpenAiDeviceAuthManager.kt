package com.niki914.zafiro.openai.auth

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Experimental implementation of the ChatGPT/Codex device-code flow observed
 * in open-source desktop clients such as CC Switch.
 *
 * These endpoints are not a documented generic OpenAI Android OAuth API. Keep
 * the normal OpenAI API-key path available as the stable fallback.
 */
class OpenAiDeviceAuthManager(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(30, TimeUnit.SECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build(),
) {
    suspend fun startDeviceFlow(): OpenAiDeviceCodeSession = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("client_id", CODEX_CLIENT_ID)
            .toString()
            .toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder()
            .url(DEVICE_AUTH_USERCODE_URL)
            .header("User-Agent", USER_AGENT)
            .post(body)
            .build()
        val json = executeJson(request)
        val deviceAuthId = json.requiredString("device_auth_id")
        val userCode = json.requiredString("user_code")
        val intervalSeconds = json.optFlexibleLong("interval")?.coerceAtLeast(1L)
            ?: DEFAULT_POLL_INTERVAL_SECONDS
        val expiresIn = json.optFlexibleLong("expires_in")?.coerceAtLeast(30L)
            ?: DEFAULT_DEVICE_CODE_EXPIRES_SECONDS
        OpenAiDeviceCodeSession(
            deviceAuthId = deviceAuthId,
            userCode = userCode,
            verificationUri = DEVICE_VERIFICATION_URL,
            intervalSeconds = intervalSeconds,
            expiresAtEpochMs = System.currentTimeMillis() + expiresIn * 1_000L,
        )
    }

    suspend fun pollDeviceFlow(session: OpenAiDeviceCodeSession): OpenAiDevicePollResult =
        withContext(Dispatchers.IO) {
            if (System.currentTimeMillis() >= session.expiresAtEpochMs) {
                return@withContext OpenAiDevicePollResult.Expired
            }
            val body = JSONObject()
                .put("device_auth_id", session.deviceAuthId)
                .put("user_code", session.userCode)
                .toString()
                .toRequestBody(JSON_MEDIA_TYPE)
            val request = Request.Builder()
                .url(DEVICE_AUTH_TOKEN_URL)
                .header("User-Agent", USER_AGENT)
                .post(body)
                .build()

            client.newCall(request).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                val json = raw.toJsonOrEmpty()
                val authorizationCode = json.optNullableString("authorization_code")
                val verifier = json.optNullableString("code_verifier")
                if (response.isSuccessful && authorizationCode != null && verifier != null) {
                    return@withContext OpenAiDevicePollResult.Authorized(
                        authorizationCode = authorizationCode,
                        codeVerifier = verifier,
                    )
                }

                val error = sequenceOf(
                    json.optNullableString("error"),
                    json.optNullableString("code"),
                    json.optNullableString("message"),
                    raw.takeIf { it.isNotBlank() },
                ).filterNotNull().joinToString(" ").lowercase()

                return@withContext when {
                    "authorization_pending" in error || "pending" in error ->
                        OpenAiDevicePollResult.Pending
                    "access_denied" in error || "denied" in error ->
                        OpenAiDevicePollResult.AccessDenied
                    "expired" in error -> OpenAiDevicePollResult.Expired
                    response.code == 404 || response.code == 408 || response.code == 429 ->
                        OpenAiDevicePollResult.Pending
                    else -> OpenAiDevicePollResult.Failed(
                        "Device authorization failed (HTTP ${response.code})."
                    )
                }
            }
        }

    suspend fun exchangeAuthorizationCode(
        authorizationCode: String,
        codeVerifier: String,
    ): OpenAiTokenResponse = withContext(Dispatchers.IO) {
        val form = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("code", authorizationCode)
            .add("redirect_uri", DEVICE_REDIRECT_URI)
            .add("client_id", CODEX_CLIENT_ID)
            .add("code_verifier", codeVerifier)
            .build()
        executeTokenRequest(form)
    }

    suspend fun refresh(refreshToken: String): OpenAiTokenResponse = withContext(Dispatchers.IO) {
        val form = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("refresh_token", refreshToken)
            .add("client_id", CODEX_CLIENT_ID)
            .add("scope", "openid profile email")
            .build()
        executeTokenRequest(form)
    }

    fun identityFromIdToken(idToken: String?): OpenAiAccountIdentity? {
        if (idToken.isNullOrBlank()) return null
        return runCatching {
            val parts = idToken.split('.')
            require(parts.size >= 2) { "Invalid id_token" }
            val payload = Base64.decode(
                parts[1],
                Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
            ).toString(Charsets.UTF_8)
            val claims = JSONObject(payload)
            val email = claims.optNullableString("email")
            val topLevelAccountId = claims.optNullableString("chatgpt_account_id")
            val authClaim = claims.optJSONObject("https://api.openai.com/auth")
            val nestedAccountId = authClaim?.optNullableString("chatgpt_account_id")
            val chatgptAccountId = topLevelAccountId ?: nestedAccountId
            OpenAiAccountIdentity(
                localAccountId = chatgptAccountId ?: email ?: UUID.randomUUID().toString(),
                email = email,
                chatgptAccountId = chatgptAccountId,
            )
        }.getOrNull()
    }

    private fun executeTokenRequest(form: FormBody): OpenAiTokenResponse {
        val request = Request.Builder()
            .url(OAUTH_TOKEN_URL)
            .header("User-Agent", USER_AGENT)
            .post(form)
            .build()
        val json = executeJson(request)
        val accessToken = json.requiredString("access_token")
        val expiresIn = json.optFlexibleLong("expires_in")?.coerceAtLeast(30L)
            ?: DEFAULT_ACCESS_TOKEN_EXPIRES_SECONDS
        return OpenAiTokenResponse(
            accessToken = accessToken,
            refreshToken = json.optNullableString("refresh_token"),
            idToken = json.optNullableString("id_token"),
            expiresAtEpochMs = System.currentTimeMillis() + expiresIn * 1_000L,
        )
    }

    private fun executeJson(request: Request): JSONObject {
        client.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val safeMessage = raw.toJsonOrEmpty().optNullableString("error_description")
                    ?: raw.toJsonOrEmpty().optNullableString("error")
                    ?: "HTTP ${response.code}"
                throw IOException("OpenAI auth request failed: $safeMessage")
            }
            if (raw.isBlank()) throw IOException("OpenAI auth response was empty")
            return JSONObject(raw)
        }
    }

    private fun String.toJsonOrEmpty(): JSONObject =
        runCatching { JSONObject(this) }.getOrElse { JSONObject() }

    private fun JSONObject.requiredString(name: String): String {
        return optNullableString(name)
            ?: throw IOException("OpenAI auth response is missing '$name'")
    }

    private fun JSONObject.optNullableString(name: String): String? {
        if (!has(name) || isNull(name)) return null
        return optString(name).trim().takeIf { it.isNotEmpty() && it != "null" }
    }

    private fun JSONObject.optFlexibleLong(name: String): Long? {
        if (!has(name) || isNull(name)) return null
        return when (val value = opt(name)) {
            is Number -> value.toLong()
            is String -> value.toLongOrNull()
            else -> null
        }
    }

    private companion object {
        const val CODEX_CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann"
        const val DEVICE_AUTH_USERCODE_URL =
            "https://auth.openai.com/api/accounts/deviceauth/usercode"
        const val DEVICE_AUTH_TOKEN_URL =
            "https://auth.openai.com/api/accounts/deviceauth/token"
        const val OAUTH_TOKEN_URL = "https://auth.openai.com/oauth/token"
        const val DEVICE_VERIFICATION_URL = "https://auth.openai.com/codex/device"
        const val DEVICE_REDIRECT_URI = "https://auth.openai.com/deviceauth/callback"
        const val USER_AGENT = "zafiro-experimental-codex-oauth/1.0"
        const val DEFAULT_POLL_INTERVAL_SECONDS = 5L
        const val DEFAULT_DEVICE_CODE_EXPIRES_SECONDS = 900L
        const val DEFAULT_ACCESS_TOKEN_EXPIRES_SECONDS = 3_600L
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

/**
 * App-level account facade. Refresh tokens remain encrypted in [OpenAiTokenStore],
 * while the short-lived access token exists only in this process memory.
 */
class OpenAiAuthRepository(
    context: Context,
    private val manager: OpenAiDeviceAuthManager = OpenAiDeviceAuthManager(),
    private val tokenStore: OpenAiTokenStore = OpenAiTokenStore(context),
) {
    private val refreshMutex = Mutex()

    @Volatile
    private var accessCache: OpenAiRuntimeCredential? = null

    suspend fun startLogin(): OpenAiDeviceCodeSession = manager.startDeviceFlow()

    suspend fun pollLogin(session: OpenAiDeviceCodeSession): OpenAiLoginResult {
        return when (val poll = manager.pollDeviceFlow(session)) {
            OpenAiDevicePollResult.Pending -> OpenAiLoginResult.Pending
            OpenAiDevicePollResult.AccessDenied -> OpenAiLoginResult.AccessDenied
            OpenAiDevicePollResult.Expired -> OpenAiLoginResult.Expired
            is OpenAiDevicePollResult.Failed -> OpenAiLoginResult.Failed(poll.message)
            is OpenAiDevicePollResult.Authorized -> runCatching {
                val token = manager.exchangeAuthorizationCode(
                    authorizationCode = poll.authorizationCode,
                    codeVerifier = poll.codeVerifier,
                )
                val identity = manager.identityFromIdToken(token.idToken)
                    ?: OpenAiAccountIdentity(
                        localAccountId = UUID.randomUUID().toString(),
                        email = null,
                        chatgptAccountId = null,
                    )
                val refreshToken = token.refreshToken
                    ?: return OpenAiLoginResult.Failed(
                        "Login succeeded but no refresh token was returned."
                    )
                tokenStore.save(
                    StoredOpenAiAccount(
                        localAccountId = identity.localAccountId,
                        email = identity.email,
                        chatgptAccountId = identity.chatgptAccountId,
                        refreshToken = refreshToken,
                        idToken = token.idToken,
                    )
                )
                accessCache = OpenAiRuntimeCredential(
                    accessToken = token.accessToken,
                    chatgptAccountId = identity.chatgptAccountId,
                    email = identity.email,
                    expiresAtEpochMs = token.expiresAtEpochMs,
                )
                OpenAiLoginResult.Success(identity)
            }.getOrElse { throwable ->
                OpenAiLoginResult.Failed(throwable.message ?: "OpenAI login failed")
            }
        }
    }

    fun currentAccount(): OpenAiAccountIdentity? {
        val stored = tokenStore.load() ?: return null
        return OpenAiAccountIdentity(
            localAccountId = stored.localAccountId,
            email = stored.email,
            chatgptAccountId = stored.chatgptAccountId,
        )
    }

    suspend fun getRuntimeCredential(): OpenAiRuntimeCredential? = refreshMutex.withLock {
        val cached = accessCache
        if (cached != null && cached.expiresAtEpochMs - System.currentTimeMillis() > REFRESH_BUFFER_MS) {
            return@withLock cached
        }

        val stored = tokenStore.load() ?: return@withLock null
        val refreshed = manager.refresh(stored.refreshToken)
        val refreshedIdentity = manager.identityFromIdToken(refreshed.idToken)
        val nextStored = stored.copy(
            email = refreshedIdentity?.email ?: stored.email,
            chatgptAccountId = refreshedIdentity?.chatgptAccountId ?: stored.chatgptAccountId,
            refreshToken = refreshed.refreshToken ?: stored.refreshToken,
            idToken = refreshed.idToken ?: stored.idToken,
        )
        tokenStore.save(nextStored)
        OpenAiRuntimeCredential(
            accessToken = refreshed.accessToken,
            chatgptAccountId = nextStored.chatgptAccountId,
            email = nextStored.email,
            expiresAtEpochMs = refreshed.expiresAtEpochMs,
        ).also { accessCache = it }
    }

    fun logout() {
        accessCache = null
        tokenStore.clear()
    }

    private companion object {
        const val REFRESH_BUFFER_MS = 60_000L
    }
}
