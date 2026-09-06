package com.niki914.zafiro.repo

import com.niki914.store.StoreDescriptorRegistry
import com.niki914.zafiro.chat.agentic.accessibility.SensitiveAppPolicyRegistry

/**
 * Persistent user policy for apps that should pause AI screen interaction.
 *
 * Package names are persisted locally in APP_STATE and mirrored into the runtime's
 * process-local registry. No package list is sent to a model or network service.
 */
object SensitiveAppSettings {
    suspend fun packages(): Set<String> {
        val state = AppStateSettingsCodec.parse(
            XRepo.readJson(StoreDescriptorRegistry.APP_STATE_ID)
        )
        return decode(state.sensitiveAppPackagesCsv)
    }

    suspend fun setPaused(packageName: String, paused: Boolean): Set<String> {
        val normalized = packageName.trim()
        if (normalized.isEmpty()) return packages()

        var updated: Set<String> = emptySet()
        XRepo.updateJson(StoreDescriptorRegistry.APP_STATE_ID) { json ->
            val current = AppStateSettingsCodec.parse(json)
            val values = decode(current.sensitiveAppPackagesCsv).toMutableSet()
            if (paused) values += normalized else values -= normalized
            updated = values.toSortedSet()
            AppStateSettingsCodec.encode(
                current.copy(sensitiveAppPackagesCsv = encode(updated))
            )
        }
        syncRuntime(updated)
        return updated
    }

    suspend fun reloadRuntime(): Set<String> {
        val values = packages()
        syncRuntime(values)
        return values
    }

    private fun syncRuntime(values: Set<String>) {
        SensitiveAppPolicyRegistry.replaceAll(
            values.associateWith { SensitiveAppPolicyRegistry.Policy.PAUSE_AI }
        )
    }

    private fun decode(csv: String): Set<String> {
        return csv.split(',')
            .asSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .toSortedSet()
    }

    private fun encode(values: Set<String>): String {
        return values.asSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
            .sorted()
            .joinToString(",")
    }
}
