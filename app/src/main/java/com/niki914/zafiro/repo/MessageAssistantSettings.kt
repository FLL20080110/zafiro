package com.niki914.zafiro.repo

import com.niki914.store.StoreDescriptorRegistry
import com.niki914.zafiro.message.IncomingChatMessage

/**
 * Durable, local-only policy for inbound chat handling.
 *
 * The model may propose reply text, but it never decides whether sending is authorized.
 * Sending permission is derived exclusively from these persisted local settings plus the
 * message's local sensitivity/reply-capability facts.
 */
object MessageAssistantSettings {
    enum class Mode(val wireValue: String) {
        OFF("off"),
        SUGGEST("suggest"),
        AUTO_REPLY("auto_reply");

        companion object {
            fun fromWire(value: String): Mode = entries.firstOrNull { it.wireValue == value } ?: OFF
        }
    }

    data class Snapshot(
        val mode: Mode,
        val enabledPackages: Set<String>,
        val trustedConversations: Set<String>,
        val privacyModeEnabled: Boolean,
    )

    enum class Decision {
        IGNORE,
        SUGGEST_ONLY,
        AUTO_REPLY_ALLOWED,
        BLOCKED_SENSITIVE,
        BLOCKED_PRIVACY,
        BLOCKED_UNTRUSTED,
        BLOCKED_NO_SYSTEM_REPLY,
    }

    suspend fun snapshot(): Snapshot {
        val state = AppStateSettingsCodec.parse(XRepo.readJson(StoreDescriptorRegistry.APP_STATE_ID))
        return Snapshot(
            mode = Mode.fromWire(state.messageAssistantMode),
            enabledPackages = splitCsv(state.messageAssistantPackagesCsv),
            trustedConversations = state.messageAssistantTrustedConversations
                .lineSequence()
                .map(String::trim)
                .filter(String::isNotEmpty)
                .toSet(),
            privacyModeEnabled = state.privacyModeEnabled,
        )
    }

    suspend fun setMode(mode: Mode): Snapshot {
        XRepo.updateJson(StoreDescriptorRegistry.APP_STATE_ID) { json ->
            val current = AppStateSettingsCodec.parse(json)
            AppStateSettingsCodec.encode(current.copy(messageAssistantMode = mode.wireValue))
        }
        return snapshot()
    }

    suspend fun setPackageEnabled(packageName: String, enabled: Boolean): Snapshot {
        val normalized = packageName.trim()
        if (normalized.isEmpty()) return snapshot()
        XRepo.updateJson(StoreDescriptorRegistry.APP_STATE_ID) { json ->
            val current = AppStateSettingsCodec.parse(json)
            val packages = splitCsv(current.messageAssistantPackagesCsv).toMutableSet()
            if (enabled) packages += normalized else packages -= normalized
            AppStateSettingsCodec.encode(
                current.copy(messageAssistantPackagesCsv = packages.sorted().joinToString(","))
            )
        }
        return snapshot()
    }

    suspend fun setTrustedConversation(conversationKey: String, trusted: Boolean): Snapshot {
        val normalized = conversationKey.trim()
        if (normalized.isEmpty()) return snapshot()
        XRepo.updateJson(StoreDescriptorRegistry.APP_STATE_ID) { json ->
            val current = AppStateSettingsCodec.parse(json)
            val trustedSet = current.messageAssistantTrustedConversations
                .lineSequence()
                .map(String::trim)
                .filter(String::isNotEmpty)
                .toMutableSet()
            if (trusted) trustedSet += normalized else trustedSet -= normalized
            AppStateSettingsCodec.encode(
                current.copy(messageAssistantTrustedConversations = trustedSet.sorted().joinToString("\n"))
            )
        }
        return snapshot()
    }

    suspend fun evaluate(message: IncomingChatMessage): Decision {
        val policy = snapshot()
        if (policy.mode == Mode.OFF || message.packageName !in policy.enabledPackages) {
            return Decision.IGNORE
        }
        if (message.sensitive) return Decision.BLOCKED_SENSITIVE
        if (policy.privacyModeEnabled) return Decision.BLOCKED_PRIVACY
        if (policy.mode == Mode.SUGGEST) return Decision.SUGGEST_ONLY
        if (!message.systemReplyAvailable) return Decision.BLOCKED_NO_SYSTEM_REPLY
        if (conversationKey(message) !in policy.trustedConversations) {
            return Decision.BLOCKED_UNTRUSTED
        }
        return Decision.AUTO_REPLY_ALLOWED
    }

    fun conversationKey(message: IncomingChatMessage): String =
        "${message.packageName}|${message.conversation.trim()}"

    private fun splitCsv(value: String): Set<String> = value
        .split(',')
        .asSequence()
        .map(String::trim)
        .filter(String::isNotEmpty)
        .toSet()
}
