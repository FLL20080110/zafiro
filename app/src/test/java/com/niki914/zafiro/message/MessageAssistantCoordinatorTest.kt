package com.niki914.zafiro.message

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageAssistantCoordinatorTest {

    @Test
    fun sanitizeGeneratedReplyTrimsWhitespace() {
        assertEquals("你好", MessageAssistantCoordinator.sanitizeGeneratedReply("  你好  \n"))
    }

    @Test
    fun sanitizeGeneratedReplyCapsLength() {
        val longReply = "a".repeat(800)
        assertEquals(500, MessageAssistantCoordinator.sanitizeGeneratedReply(longReply).length)
    }

    @Test
    fun sanitizeGeneratedReplyCanRejectWhitespaceAsBlank() {
        assertEquals("", MessageAssistantCoordinator.sanitizeGeneratedReply(" \n\t "))
    }

    @Test
    fun accessibilitySuggestionExpiresAfterThirtySeconds() {
        assertFalse(
            MessageAssistantCoordinator.isPendingSuggestionExpired(
                createdAtElapsedMs = 1_000L,
                systemReplyAvailable = false,
                nowElapsedMs = 31_000L,
            )
        )
        assertTrue(
            MessageAssistantCoordinator.isPendingSuggestionExpired(
                createdAtElapsedMs = 1_000L,
                systemReplyAvailable = false,
                nowElapsedMs = 31_001L,
            )
        )
    }

    @Test
    fun remoteInputSuggestionKeepsFiveMinuteWindow() {
        assertFalse(
            MessageAssistantCoordinator.isPendingSuggestionExpired(
                createdAtElapsedMs = 1_000L,
                systemReplyAvailable = true,
                nowElapsedMs = 301_000L,
            )
        )
        assertTrue(
            MessageAssistantCoordinator.isPendingSuggestionExpired(
                createdAtElapsedMs = 1_000L,
                systemReplyAvailable = true,
                nowElapsedMs = 301_001L,
            )
        )
    }

    @Test
    fun elapsedClockRollbackFailsClosed() {
        assertTrue(
            MessageAssistantCoordinator.isPendingSuggestionExpired(
                createdAtElapsedMs = 5_000L,
                systemReplyAvailable = false,
                nowElapsedMs = 4_999L,
            )
        )
    }
}
