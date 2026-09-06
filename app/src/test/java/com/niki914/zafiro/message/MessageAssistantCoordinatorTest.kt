package com.niki914.zafiro.message

import org.junit.Assert.assertEquals
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
}
