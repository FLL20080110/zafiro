package com.niki914.zafiro.message

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-local index of recently observed chat conversations.
 *
 * Only the package name, normalized conversation label and last-seen timestamp are kept. Message
 * bodies and sender names are intentionally excluded, and nothing in this registry is persisted.
 */
object RecentConversationRegistry {
    private const val MAX_ENTRIES = 32

    data class Entry(
        val packageName: String,
        val conversation: String,
        val lastSeenAtMs: Long,
    ) {
        val conversationKey: String
            get() = "$packageName|$conversation"
    }

    private val mutableEntries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = mutableEntries.asStateFlow()

    @Synchronized
    fun observe(message: IncomingChatMessage) {
        val packageName = message.packageName.trim()
        val conversation = message.conversation.trim()
        if (packageName.isEmpty() || conversation.isEmpty()) return

        val key = "$packageName|$conversation"
        val next = buildList {
            add(Entry(packageName, conversation, message.postedAtMs))
            mutableEntries.value
                .asSequence()
                .filterNot { it.conversationKey == key }
                .take(MAX_ENTRIES - 1)
                .forEach(::add)
        }
        mutableEntries.value = next
    }

    @Synchronized
    fun clear() {
        mutableEntries.value = emptyList()
    }

    @Synchronized
    internal fun clearForTest() = clear()
}
