package com.niki914.zafiro.chat.agentic.accessibility

/**
 * Process-local sensitive-app policy snapshot.
 *
 * The app/settings layer owns persistence and feeds package-level policy into this
 * registry. Runtime enforcement stays local and deterministic: no package name or
 * policy decision is uploaded or sent to a model.
 *
 * Reads are lock-free through an immutable volatile snapshot. Mutations are serialized so
 * concurrent settings/service refreshes cannot overwrite each other's package changes.
 */
object SensitiveAppPolicyRegistry {
    enum class Policy {
        /** Pause AI/model interaction while this package is the active window. */
        PAUSE_AI,
    }

    private val mutationLock = Any()

    @Volatile
    private var policies: Map<String, Policy> = emptyMap()

    fun replaceAll(values: Map<String, Policy>) {
        val normalized = values
            .asSequence()
            .map { (packageName, policy) -> packageName.trim() to policy }
            .filter { (packageName, _) -> packageName.isNotEmpty() }
            .toMap()
        synchronized(mutationLock) {
            policies = normalized
        }
    }

    fun set(packageName: String, policy: Policy?) {
        val normalized = packageName.trim()
        if (normalized.isEmpty()) return
        synchronized(mutationLock) {
            policies = policies.toMutableMap().apply {
                if (policy == null) remove(normalized) else put(normalized, policy)
            }.toMap()
        }
    }

    fun policyFor(packageName: String?): Policy? {
        val normalized = packageName?.trim().orEmpty()
        if (normalized.isEmpty()) return null
        return policies[normalized]
    }

    fun snapshot(): Map<String, Policy> = policies.toMap()

    fun clear() {
        synchronized(mutationLock) {
            policies = emptyMap()
        }
    }
}
