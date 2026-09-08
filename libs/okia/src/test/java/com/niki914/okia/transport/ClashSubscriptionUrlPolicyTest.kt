package com.niki914.okia.transport

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ClashSubscriptionUrlPolicyTest {
    @Test
    fun acceptsHttpsSubscriptionAndPreservesSecretForStorageOnly() {
        val url = "https://example.com/sub?token=private-token"
        assertEquals(url, ClashSubscriptionUrlPolicy.validate(url))
    }

    @Test
    fun redactionNeverExposesPathQueryOrToken() {
        val label = ClashSubscriptionUrlPolicy.redactedLabel(
            "https://example.com/private/path?token=private-token",
        )
        assertEquals("example.com · 已配置", label)
    }

    @Test
    fun rejectsRemoteHttp() {
        assertFailsWith<IllegalArgumentException> {
            ClashSubscriptionUrlPolicy.validate("http://example.com/sub")
        }
    }

    @Test
    fun rejectsEmbeddedCredentials() {
        assertFailsWith<IllegalArgumentException> {
            ClashSubscriptionUrlPolicy.validate("https://user:pass@example.com/sub")
        }
    }

    @Test
    fun rejectsMissingHost() {
        assertFailsWith<IllegalArgumentException> {
            ClashSubscriptionUrlPolicy.validate("https:///sub")
        }
    }

    @Test
    fun rejectsFragment() {
        assertFailsWith<IllegalArgumentException> {
            ClashSubscriptionUrlPolicy.validate("https://example.com/sub#secret")
        }
    }
}
