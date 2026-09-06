package com.niki914.zafiro.message

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecentConversationRegistryTest {

    @After
    fun tearDown() {
        RecentConversationRegistry.clearForTest()
    }

    @Test
    fun observeDeduplicatesByConversationAndKeepsLatestFirst() {
        RecentConversationRegistry.observe(message("com.tencent.mm", "Alice", 1L, "secret one"))
        RecentConversationRegistry.observe(message("com.tencent.mobileqq", "Bob", 2L, "secret two"))
        RecentConversationRegistry.observe(message("com.tencent.mm", "Alice", 3L, "secret three"))

        val entries = RecentConversationRegistry.entries.value
        assertEquals(2, entries.size)
        assertEquals("com.tencent.mm|Alice", entries[0].conversationKey)
        assertEquals(3L, entries[0].lastSeenAtMs)
        assertEquals("com.tencent.mobileqq|Bob", entries[1].conversationKey)
    }

    @Test
    fun registryNeverStoresMessageBodyOrSender() {
        val secretBody = "OTP 123456 should never be retained"
        val secretSender = "Sensitive Sender"
        RecentConversationRegistry.observe(
            IncomingChatMessage(
                packageName = "com.tencent.tim",
                sender = secretSender,
                conversation = "Trusted Chat",
                text = secretBody,
                postedAtMs = 10L,
            )
        )

        val serializedView = RecentConversationRegistry.entries.value.joinToString("|")
        assertFalse(serializedView.contains(secretBody))
        assertFalse(serializedView.contains(secretSender))
        assertTrue(serializedView.contains("Trusted Chat"))
    }

    @Test
    fun registryIsBounded() {
        repeat(40) { index ->
            RecentConversationRegistry.observe(
                message("com.tencent.mm", "Chat $index", index.toLong(), "body $index")
            )
        }

        val entries = RecentConversationRegistry.entries.value
        assertEquals(32, entries.size)
        assertEquals("Chat 39", entries.first().conversation)
        assertEquals("Chat 8", entries.last().conversation)
    }

    private fun message(
        packageName: String,
        conversation: String,
        postedAtMs: Long,
        body: String,
    ) = IncomingChatMessage(
        packageName = packageName,
        sender = "sender",
        conversation = conversation,
        text = body,
        postedAtMs = postedAtMs,
    )
}
