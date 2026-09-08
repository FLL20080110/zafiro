package com.niki914.zafiro.repo

import com.niki914.zafiro.message.IncomingChatMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageAssistantSettingsTest {
    private val baseMessage = IncomingChatMessage(
        packageName = "com.tencent.mm",
        sender = "Alice",
        conversation = "Alice",
        text = "hello",
        postedAtMs = 1L,
        systemReplyAvailable = true,
        sensitive = false,
        replyHandleId = "ephemeral",
    )

    private fun policy(
        mode: MessageAssistantSettings.Mode = MessageAssistantSettings.Mode.AUTO_REPLY,
        enabledPackages: Set<String> = setOf("com.tencent.mm"),
        trustedConversations: Set<String> = setOf("com.tencent.mm|Alice"),
        privacyModeEnabled: Boolean = false,
        accessibilityFallbackEnabled: Boolean = false,
    ) = MessageAssistantSettings.Snapshot(
        mode = mode,
        enabledPackages = enabledPackages,
        trustedConversations = trustedConversations,
        privacyModeEnabled = privacyModeEnabled,
        accessibilityFallbackEnabled = accessibilityFallbackEnabled,
    )

    @Test
    fun accessibilityFallbackIsOptInByDefault() {
        assertFalse(policy().accessibilityFallbackEnabled)
        assertTrue(policy(accessibilityFallbackEnabled = true).accessibilityFallbackEnabled)
    }

    @Test
    fun autoReplyRequiresAllLocalAuthorizationGates() {
        assertEquals(
            MessageAssistantSettings.Decision.AUTO_REPLY_ALLOWED,
            MessageAssistantSettings.decide(policy(), baseMessage),
        )
    }

    @Test
    fun sensitiveMessageAlwaysBlocksAutomation() {
        assertEquals(
            MessageAssistantSettings.Decision.BLOCKED_SENSITIVE,
            MessageAssistantSettings.decide(policy(), baseMessage.copy(sensitive = true)),
        )
    }

    @Test
    fun privacyModeBlocksAutomation() {
        assertEquals(
            MessageAssistantSettings.Decision.BLOCKED_PRIVACY,
            MessageAssistantSettings.decide(policy(privacyModeEnabled = true), baseMessage),
        )
    }

    @Test
    fun untrustedConversationBlocksAutomation() {
        assertEquals(
            MessageAssistantSettings.Decision.BLOCKED_UNTRUSTED,
            MessageAssistantSettings.decide(policy(trustedConversations = emptySet()), baseMessage),
        )
    }

    @Test
    fun missingSystemReplyCapabilityBlocksAutomation() {
        assertEquals(
            MessageAssistantSettings.Decision.BLOCKED_NO_SYSTEM_REPLY,
            MessageAssistantSettings.decide(
                policy(),
                baseMessage.copy(systemReplyAvailable = false, replyHandleId = null),
            ),
        )
    }

    @Test
    fun suggestModeNeverAuthorizesSending() {
        assertEquals(
            MessageAssistantSettings.Decision.SUGGEST_ONLY,
            MessageAssistantSettings.decide(
                policy(mode = MessageAssistantSettings.Mode.SUGGEST),
                baseMessage,
            ),
        )
    }

    @Test
    fun disabledPackageIsIgnored() {
        assertEquals(
            MessageAssistantSettings.Decision.IGNORE,
            MessageAssistantSettings.decide(policy(enabledPackages = emptySet()), baseMessage),
        )
    }
}
