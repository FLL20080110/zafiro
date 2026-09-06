package com.niki914.zafiro.message

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Process-local stream for normalized incoming chat notifications.
 *
 * Message contents are intentionally kept in memory only here: this layer does not persist,
 * audit, broadcast, or upload sender/conversation/text fields. Consumers must apply privacy
 * and user-authorization policy before forwarding content to any model or reply path.
 */
data class IncomingChatMessage(
    val packageName: String,
    val sender: String,
    val conversation: String,
    val text: String,
    val postedAtMs: Long,
)

object IncomingMessageBus {
    private val mutableEvents = MutableSharedFlow<IncomingChatMessage>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    val events: SharedFlow<IncomingChatMessage> = mutableEvents.asSharedFlow()

    internal fun publish(message: IncomingChatMessage) {
        mutableEvents.tryEmit(message)
    }
}
