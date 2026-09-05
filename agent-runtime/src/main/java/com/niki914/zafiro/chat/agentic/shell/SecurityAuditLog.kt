package com.niki914.zafiro.chat.agentic.shell

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

enum class SecurityAuditEventType {
    PRIVILEGED_REQUEST,
    PRIVILEGED_ALLOWED,
    PRIVILEGED_DENIED,
    EMERGENCY_STOP_ACTIVATED,
    EMERGENCY_STOP_CLEARED,
}

data class SecurityAuditEvent(
    val id: String = UUID.randomUUID().toString(),
    val type: SecurityAuditEventType,
    val timestampEpochMs: Long = System.currentTimeMillis(),
    val toolName: String? = null,
    val executionIdentity: String? = null,
    val command: String? = null,
    val riskLevel: ToolPermissionRiskLevel? = null,
    val detail: String? = null,
)

/**
 * Small process-local security audit stream. It intentionally stores no model
 * secrets or OAuth tokens. A later persistence layer can subscribe to [events]
 * and write selected records to encrypted/local storage.
 */
object SecurityAuditLog {
    private const val MAX_EVENTS = 200
    private val lock = Any()
    private val buffer = ArrayDeque<SecurityAuditEvent>(MAX_EVENTS)
    private val eventsFlow = MutableStateFlow<List<SecurityAuditEvent>>(emptyList())

    val events: StateFlow<List<SecurityAuditEvent>> = eventsFlow.asStateFlow()

    fun record(event: SecurityAuditEvent) {
        synchronized(lock) {
            if (buffer.size >= MAX_EVENTS) buffer.removeFirst()
            buffer.addLast(event)
            eventsFlow.value = buffer.toList()
        }
    }

    fun clear() {
        synchronized(lock) {
            buffer.clear()
            eventsFlow.value = emptyList()
        }
    }
}
