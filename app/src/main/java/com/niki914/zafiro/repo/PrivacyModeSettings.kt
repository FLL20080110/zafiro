package com.niki914.zafiro.repo

import com.niki914.store.StoreDescriptorRegistry
import com.niki914.zafiro.chat.agentic.shell.ToolPermissionCoordinator

/**
 * Local persistence facade for privacy mode.
 *
 * The runtime reads the same APP_STATE field at every relevant policy boundary,
 * so changing this setting takes effect without sending anything to a model or
 * network service. Enabling privacy mode also drops all process-local temporary
 * grants so a previous approval cannot outlive the privacy transition.
 */
object PrivacyModeSettings {
    suspend fun enabled(): Boolean {
        return AppStateSettingsCodec.parse(
            XRepo.readJson(StoreDescriptorRegistry.APP_STATE_ID)
        ).privacyModeEnabled
    }

    suspend fun setEnabled(enabled: Boolean): Boolean {
        XRepo.updateJson(StoreDescriptorRegistry.APP_STATE_ID) { json ->
            val current = AppStateSettingsCodec.parse(json)
            AppStateSettingsCodec.encode(current.copy(privacyModeEnabled = enabled))
        }
        if (enabled) {
            ToolPermissionCoordinator.clearTemporaryGrants()
        }
        return enabled()
    }
}
