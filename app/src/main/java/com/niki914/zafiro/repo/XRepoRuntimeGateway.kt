package com.niki914.zafiro.repo

import com.niki914.store.StoreDescriptorRegistry
import com.niki914.zafiro.settings.MemoryMutationResult
import com.niki914.zafiro.settings.RuntimeSettingsGateway
import com.niki914.zafiro.settings.model.RuntimeBuiltinToolSetting
import com.niki914.zafiro.settings.model.RuntimeCustomPyTool
import com.niki914.zafiro.settings.model.RuntimeExecutionRule
import com.niki914.zafiro.settings.model.RuntimeLlmConfig
import com.niki914.zafiro.settings.model.RuntimeLoadedSkill
import com.niki914.zafiro.settings.model.RuntimeMcpServer
import com.niki914.zafiro.settings.model.RuntimePrivacyPolicy
import com.niki914.zafiro.settings.model.RuntimeSkillMetadata
import com.niki914.zafiro.settings.model.RuntimeToolValidation

class XRepoRuntimeGateway(
    private val repo: XRepo = XRepo,
) : RuntimeSettingsGateway {
    override suspend fun readPrivacyPolicy(): RuntimePrivacyPolicy {
        val state = AppStateSettingsCodec.parse(repo.readJson(StoreDescriptorRegistry.APP_STATE_ID))
        return RuntimePrivacyPolicy(enabled = state.privacyModeEnabled)
    }

    override suspend fun readLlmConfig(agentId: String): RuntimeLlmConfig {
        check(readPrivacyPolicy().allowCloudLlm) {
            "Cloud LLM access is disabled by privacy mode."
        }
        val doc = repo.llmConfigs.document()
        val active = doc.activeConfig()
        val memories = repo.agents.memoriesFor(agentId)
        return RuntimeLlmConfig(
            provider = active?.provider.orEmpty(),
            endpoint = active?.endpoint.orEmpty(),
            apiKey = active?.apiKey.orEmpty(),
            model = active?.model.orEmpty(),
            protocol = active?.protocol.orEmpty(),
            proxy = active?.proxy.orEmpty(),
            prompt = doc.prompt,
            memories = memories,
            idleTimeoutSeconds = repo.llmIdleTimeoutSeconds().takeIf { it > 0L },
            retryMaxAttempts = repo.llmRetryMaxAttempts(),
        )
    }

    override suspend fun listMcpServers(): List<RuntimeMcpServer> {
        if (!readPrivacyPolicy().allowMcp) return emptyList()
        return repo.mcp.list()
    }

    override suspend fun listEnabledSkills(): List<RuntimeSkillMetadata> {
        return repo.skills.listEnabled()
    }

    override suspend fun loadSkill(id: String): RuntimeLoadedSkill? {
        return repo.skills.getDetail(id)
    }

    override suspend fun addMemory(value: String) {
        check(readPrivacyPolicy().allowMemoryWrites) { "Memory writes are disabled by privacy mode." }
        repo.memory.add(value)
    }

    override suspend fun removeMemory(oldText: String): MemoryMutationResult {
        check(readPrivacyPolicy().allowMemoryWrites) { "Memory writes are disabled by privacy mode." }
        return repo.memory.removeByText(oldText)
    }

    override suspend fun replaceMemory(oldText: String, content: String): MemoryMutationResult {
        check(readPrivacyPolicy().allowMemoryWrites) { "Memory writes are disabled by privacy mode." }
        return repo.memory.replaceByText(oldText, content)
    }

    override suspend fun listCustomPyTools(): List<RuntimeCustomPyTool> {
        if (!readPrivacyPolicy().allowNetworkTools) return emptyList()
        return repo.customPyTools.list()
    }

    override suspend fun saveCustomPyTool(
        tool: RuntimeCustomPyTool,
        overwrite: Boolean,
    ): RuntimeToolValidation? {
        return repo.customPyTools.save(tool, overwrite)
    }

    override suspend fun deleteCustomPyTool(name: String) {
        repo.customPyTools.delete(name)
    }

    override suspend fun setCustomPyToolEnabled(name: String, enabled: Boolean) {
        repo.customPyTools.setEnabled(name, enabled)
    }

    override suspend fun listBuiltinToolSettings(): List<RuntimeBuiltinToolSetting> {
        val settings = repo.builtinTools.list()
        val policy = readPrivacyPolicy()
        if (policy.allowNetworkTools && policy.allowSensitiveContextUpload) return settings
        return settings.map { setting ->
            val blockedByNetworkPolicy =
                !policy.allowNetworkTools && setting.name in NETWORK_BLOCKED_BUILTINS
            val blockedBySensitiveContextPolicy =
                !policy.allowSensitiveContextUpload && setting.name in SENSITIVE_CONTEXT_BLOCKED_BUILTINS
            if (blockedByNetworkPolicy || blockedBySensitiveContextPolicy) {
                setting.copy(enabled = false)
            } else {
                setting
            }
        }
    }

    override suspend fun setBuiltinToolEnabled(
        name: String,
        enabled: Boolean,
    ): RuntimeToolValidation? {
        return repo.builtinTools.setEnabled(name, enabled)
    }

    override suspend fun setBuiltinToolGroupEnabled(
        groupId: String,
        enabled: Boolean,
    ): RuntimeToolValidation? {
        return repo.builtinTools.setGroupEnabled(groupId, enabled)
    }

    override suspend fun listExecutionRules(): List<RuntimeExecutionRule> {
        return repo.executionRules.list()
    }

    private companion object {
        /**
         * Built-ins that can directly create outbound network activity or execute
         * arbitrary code capable of doing so. Privacy policy disables them at the
         * runtime capability-exposure boundary regardless of persisted UI state.
         */
        val NETWORK_BLOCKED_BUILTINS = setOf(
            "terminal",
            "execute_python",
            "py_download_file",
            "open_uri",
            "py_meta_tools",
        )

        /**
         * Built-ins whose results can expose high-sensitivity device context to the
         * model: accessibility UI trees, post-action screen snapshots, and images.
         */
        val SENSITIVE_CONTEXT_BLOCKED_BUILTINS = setOf(
            "screen_operation_accessibility",
            "screen_operation_shell",
            "view_image",
        )
    }
}
