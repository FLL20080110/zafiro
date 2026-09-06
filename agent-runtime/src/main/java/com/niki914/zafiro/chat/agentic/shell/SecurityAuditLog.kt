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
    SENSITIVE_APP_POLICY_ENABLED,
    SENSITIVE_APP_POLICY_DISABLED,
    MESSAGE_REPLY_SENT,
    MESSAGE_REPLY_BLOCKED,
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
)

/**
 * Bounded local security audit trail.
 *
 * Recording remains in-memory and synchronous so security decisions never wait for disk I/O.
 * The Android app layer may restore a minimized persisted snapshot through [restorePersisted]
 * and subscribe to [events] for asynchronous app-private persistence. No upload path exists
 * here. Shell commands are never retained in plaintext or as a preview; when correlation is
 * useful, only their SHA-256 fingerprint is kept.
 */
object SecurityAuditLog {
    const val MAX_EVENTS = 200
    private const val MAX_REASON_CHARS = 160

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
            reason = reason?.takeIf(String::isNotBlank)?.take(MAX_REASON_CHARS),
            commandHashSha256 = normalizedCommand?.let(::sha256),
        )
        synchronized(lock) {
            eventFlow.value = (eventFlow.value + event).takeLast(MAX_EVENTS)
        }
    }

    /**
     * Records a sensitive-app policy toggle without retaining the app label or package name.
     * The audit trail only needs the fact that protection changed; package identifiers stay in
     * the dedicated local settings store and do not become part of historical audit metadata.
     */
    fun recordSensitiveAppPolicyChange(paused: Boolean) {
        record(
            kind = if (paused) {
                SecurityAuditKind.SENSITIVE_APP_POLICY_ENABLED
            } else {
                SecurityAuditKind.SENSITIVE_APP_POLICY_DISABLED
            },
            riskLevel = SecurityRiskLevel.INFO,
            policyCode = if (paused) {
                "SENSITIVE_APP_PAUSE_ENABLED"
            } else {
                "SENSITIVE_APP_PAUSE_DISABLED"
            },
            reason = if (paused) {
                "Sensitive app pause policy enabled."
            } else {
                "Sensitive app pause policy disabled."
            },
        )
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

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
}
