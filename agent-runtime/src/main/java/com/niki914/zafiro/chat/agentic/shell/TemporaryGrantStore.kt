package com.niki914.zafiro.chat.agentic.shell

import android.os.SystemClock
import java.security.MessageDigest

/**
 * Process-local, monotonic-clock TTL grants for narrowly scoped tool approvals.
 *
 * Grants intentionally do not survive process restarts and the scope key is a
 * SHA-256 digest so raw commands are not duplicated in the grant cache.
 */
object TemporaryGrantStore {
    const val FIVE_MINUTES_MS: Long = 5 * 60 * 1000L

    private data class Grant(
        val expiresAtElapsedRealtimeMs: Long,
    )

    private val lock = Any()
    private val grants = mutableMapOf<String, Grant>()

    fun isGranted(scopeKey: String): Boolean {
        val now = SystemClock.elapsedRealtime()
        synchronized(lock) {
            pruneExpiredLocked(now)
            return grants[scopeKey]?.expiresAtElapsedRealtimeMs?.let { it > now } == true
        }
    }

    fun grant(scopeKey: String, durationMs: Long) {
        require(durationMs > 0L) { "durationMs must be > 0" }
        val now = SystemClock.elapsedRealtime()
        synchronized(lock) {
            pruneExpiredLocked(now)
            grants[scopeKey] = Grant(
                expiresAtElapsedRealtimeMs = now + durationMs,
            )
        }
    }

    fun remainingMillis(scopeKey: String): Long {
        val now = SystemClock.elapsedRealtime()
        synchronized(lock) {
            pruneExpiredLocked(now)
            return ((grants[scopeKey]?.expiresAtElapsedRealtimeMs ?: now) - now)
                .coerceAtLeast(0L)
        }
    }

    fun revoke(scopeKey: String) {
        synchronized(lock) {
            grants.remove(scopeKey)
        }
    }

    fun clear() {
        synchronized(lock) {
            grants.clear()
        }
    }

    /** Build a non-reversible cache key from the exact approval scope. */
    fun scopeKey(vararg parts: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        parts.forEachIndexed { index, part ->
            if (index > 0) digest.update(0.toByte())
            digest.update(part.toByteArray(Charsets.UTF_8))
        }
        return digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    private fun pruneExpiredLocked(now: Long) {
        grants.entries.removeAll { (_, grant) -> grant.expiresAtElapsedRealtimeMs <= now }
    }
}
