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
 * Process-local security audit trail.
 *
 * The log is intentionally bounded and local. It never uploads events and never stores a
 * second full copy of a shell command: only a SHA-256 digest plus a short, redacted preview
 * are retained. A future persistent audit repository can subscribe to [events] without
 * changing the security decision path.
 */
object SecurityAuditLog {
    private const val MAX_EVENTS = 200
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
