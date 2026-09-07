package com.niki914.zafiro.repo

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OpenAiCredentialGuardTest {
    @Test
    fun officialOpenAiEndpoint_rejectsInternalCredential() {
        assertFailsWith<IllegalArgumentException> {
            OpenAiCredentialGuard.requireUsable(
                endpoint = "https://api.openai.com/v1/responses",
                apiKey = "zafiro:cached-auth",
            )
        }
    }

    @Test
    fun officialOpenAiEndpoint_isCaseInsensitiveAndStillProtected() {
        assertFailsWith<IllegalArgumentException> {
            OpenAiCredentialGuard.requireUsable(
                endpoint = "https://API.OPENAI.COM/v1/chat/completions",
                apiKey = "  ZAFIRO:session-auth  ",
            )
        }
    }

    @Test
    fun customOpenAiCompatibleEndpoint_keepsCustomCredentialFormats() {
        OpenAiCredentialGuard.requireUsable(
            endpoint = "https://llm.example.com/v1/chat/completions",
            apiKey = "zafiro:custom-provider-token",
        )
    }

    @Test
    fun spoofedOpenAiHostname_isNotTreatedAsOfficialEndpoint() {
        assertFalse(
            OpenAiCredentialGuard.isOfficialOpenAiEndpoint(
                "https://api.openai.com.evil.example/v1/responses",
            ),
        )
        assertFalse(
            OpenAiCredentialGuard.isOfficialOpenAiEndpoint(
                "https://api.openai.com@evil.example/v1/responses",
            ),
        )
    }

    @Test
    fun exactOpenAiHostname_isRecognized() {
        assertTrue(
            OpenAiCredentialGuard.isOfficialOpenAiEndpoint(
                "https://api.openai.com/v1/responses",
            ),
        )
    }

    @Test
    fun normalOpenAiApiKey_isAccepted() {
        OpenAiCredentialGuard.requireUsable(
            endpoint = "https://api.openai.com/v1/responses",
            apiKey = "sk-test-placeholder",
        )
    }
}
