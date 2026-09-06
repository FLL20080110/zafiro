package com.niki914.zafiro.chat.agentic.shell

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong

enum class SecurityRiskLevel { INFO, LOW, MEDIUM, HIGH, CRITICAL }

enum class SecurityAuditKind {
    PERMISSION_REQUESTED,
    PERMISSION_ALLOWED,
    PERMISSION_DENIED,
    PERMISSION_UNAVAILABLE,
    TEMPORARY_GRANT_CREATED,
    TEMPORARY_GRANT_USED,
    POLICY_BLOCKED,
    PRIVACY_BLOCKED,
    SENSITIVE_CONTEXT_BLOCKED,
    TEMPORARY_GRANTS_CLEARED,
}

data class SecurityAuditEvent(
    val id: Long,
    val timestampMs: Long,
    val kind: SecurityAuditKind,
    val riskLevel: SecurityRiskLevel,
    val toolName: String? = null,
    val ruleName: String? = null,
    val policyCode: String? = null,
    val reason: String? = null,
    val commandHashSha256: String? = null,
    val commandPreview: String? = null,
)

/**
 * Bounded local security audit trail.
 *
 * Recording remains in-memory and synchronous so security decisions never wait for disk I/O.
 * The Android app layer may restore a minimized persisted snapshot through [restorePersisted]
 * and subscribe to [events] for asynchronous app-private persistence. No upload path exists
 * here, and no second full copy of a shell command is retained: only SHA-256 plus a short,
 * redacted preview.
 */
object SecurityAuditLog {
    const val MAX_EVENTS = 200
    private const val MAX_PREVIEW_CHARS = 160

    private val idCounter = AtomicLong(0L)
    private val lock = Any()
    private val eventFlow = MutableStateFlow<List<SecurityAuditEvent>>(emptyList())

    val events: StateFlow<List<SecurityAuditEvent>> = eventFlow.asStateFlow()

    fun record(
        kind: SecurityAuditKind,
        riskLevel: SecurityRiskLevel,
        toolName: String? = null,
        ruleName: String? = null,
        policyCode: String? = null,
        reason: String? = null,
        command: String? = null,
    ) {
        val normalizedCommand = command?.trim()?.takeIf(String::isNotEmpty)
        val event = SecurityAuditEvent(
            id = idCounter.incrementAndGet(),
            timestampMs = System.currentTimeMillis(),
            kind = kind,
            riskLevel = riskLevel,
            toolName = toolName?.takeIf(String::isNotBlank),
            ruleName = ruleName?.takeIf(String::isNotBlank),
            policyCode = policyCode?.takeIf(String::isNotBlank),
            reason = reason?.takeIf(String::isNotBlank)?.take(MAX_PREVIEW_CHARS),
            commandHashSha256 = normalizedCommand?.let(::sha256),
            commandPreview = normalizedCommand?.let(::redactedPreview),
        )
        synchronized(lock) {
            eventFlow.value = (eventFlow.value + event).takeLast(MAX_EVENTS)
        }
    }

    /**
     * Restores an already-minimized local snapshot. The caller owns decoding/validation.
     * Any events recorded during startup are merged instead of being overwritten.
     */
    fun restorePersisted(events: List<SecurityAuditEvent>) {
        synchronized(lock) {
            val current = eventFlow.value
            val merged = (events + current)
                .sortedWith(compareBy<SecurityAuditEvent> { it.timestampMs }.thenBy { it.id })
                .takeLast(MAX_EVENTS)
            eventFlow.value = merged
            val maxId = merged.maxOfOrNull(SecurityAuditEvent::id) ?: 0L
            idCounter.updateAndGet { existing -> maxOf(existing, maxId) }
        }
    }

    fun clear() {
        synchronized(lock) {
            eventFlow.value = emptyList()
        }
    }

    private fun redactedPreview(command: String): String {
        var value = command.replace(Regex("\\s+"), " ").trim()
        SECRET_ASSIGNMENT_PATTERNS.forEach { pattern ->
            value = pattern.replace(value) { match ->
                val prefix = match.groupValues.getOrNull(1).orEmpty()
                "$prefix<redacted>"
            }
        }
        return value.take(MAX_PREVIEW_CHARS)
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte) }

    private val SECRET_ASSIGNMENT_PATTERNS = listOf(
        Regex("(?i)\\b((?:password|passwd|pwd|token|api[_-]?key|secret)\\s*[=:]\\s*)[^\\s;&|]+"),
        Regex("(?i)\\b((?:authorization)\\s*[:=]\\s*(?:bearer\\s+)?)\\S+"),
    )
}
