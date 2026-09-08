package com.niki914.okia.transport

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class ModelProxyHttpEngineTest {
    @Test
    fun blankProxyKeepsDirectTransport() {
        assertNull(modelProxyHttpEngine("   "))
    }

    @Test
    fun clashHttpProxyIsAccepted() {
        assertNotNull(modelProxyHttpEngine("http://127.0.0.1:7890"))
    }

    @Test
    fun clashSocks5ProxyIsAccepted() {
        assertNotNull(modelProxyHttpEngine("socks5://127.0.0.1:7891"))
    }

    @Test
    fun missingPortIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            modelProxyHttpEngine("http://127.0.0.1")
        }
    }

    @Test
    fun credentialsAreRejectedSoTheyCannotLeakThroughProxyUrls() {
        assertThrows(IllegalArgumentException::class.java) {
            modelProxyHttpEngine("http://user:secret@127.0.0.1:7890")
        }
    }

    @Test
    fun subscriptionLikePathIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            modelProxyHttpEngine("http://127.0.0.1:7890/subscribe")
        }
    }

    @Test
    fun secretBearingQueryIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            modelProxyHttpEngine("http://127.0.0.1:7890/?token=secret")
        }
    }

    @Test
    fun fragmentIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            modelProxyHttpEngine("http://127.0.0.1:7890/#secret")
        }
    }

    @Test
    fun unsupportedSchemeIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            modelProxyHttpEngine("https://127.0.0.1:7890")
        }
    }
}
