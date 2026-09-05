package com.niki914.zafiro.repo

import com.niki914.zafiro.openai.auth.OpenAiAuthHolder
import com.niki914.zafiro.settings.MemoryMutationResult
import com.niki914.zafiro.settings.RuntimeSettingsGateway
import com.niki914.zafiro.settings.model.LlmProtocol
import com.niki914.zafiro.settings.model.RuntimeBuiltinToolSetting
import com.niki914.zafiro.settings.model.RuntimeCustomPyTool
import com.niki914.zafiro.settings.model.RuntimeExecutionRule
import com.niki914.zafiro.settings.model.RuntimeLlmConfig
import com.niki914.zafiro.settings.model.RuntimeLoadedSkill
import com.niki914.zafiro.settings.model.RuntimeMcpServer
import com.niki914.zafiro.settings.model.RuntimeSkillMetadata
import com.niki914.zafiro.settings.model.RuntimeToolValidation

class XRepoRuntimeGateway(
    private val repo: XRepo = XRepo,
) : RuntimeSettingsGateway {
    override suspend fun readLlmConfig(agentId: String): RuntimeLlmConfig {
        val doc = repo.llmConfigs.document()
        val active = doc.activeConfig()
        val memories = repo.agents.memoriesFor(agentId)
        val managedOAuth = active != null && OpenAiAuthHolder.isManagedOAuth(
            provider = active.provider,
            apiKey = active.apiKey,
        )
        val credential = if (managedOAuth) {
            OpenAiAuthHolder.requireRepository().getRuntimeCredential()
                ?: error("ChatGPT / Codex 尚未登录，请先在 OpenAI 配置中完成登录")
        } else {
            null
        }

        val runtimeHeaders = if (managedOAuth) {
            buildMap {
                credential?.chatgptAccountId?.takeIf(String::isNotBlank)?.let {
                    put("chatgpt-account-id", it)
                }
                // Identify this experimental client instead of pretending to be
                // the official Codex CLI.
                put("originator", "zafiro")
            }
        } else {
            emptyMap()
        }

        return RuntimeLlmConfig(
            provider = active?.provider.orEmpty(),
            endpoint = if (managedOAuth) {
                OpenAiAuthHolder.CODEX_RESPONSES_ENDPOINT
            } else {
                active?.endpoint.orEmpty()
            },
            apiKey = credential?.accessToken ?: active?.apiKey.orEmpty(),
            model = active?.model.orEmpty(),
            protocol = if (managedOAuth) {
                LlmProtocol.OpenAiResponses.wireId
            } else {
                active?.protocol.orEmpty()
            },
            headers = runtimeHeaders,
            proxy = active?.proxy.orEmpty(),
            prompt = doc.prompt,
            memories = memories,
            idleTimeoutSeconds = repo.llmIdleTimeoutSeconds().takeIf { it > 0L },
            retryMaxAttempts = repo.llmRetryMaxAttempts(),
        )
    }

    override suspend fun listMcpServers(): List<RuntimeMcpServer> = repo.mcp.list()

    override suspend fun listEnabledSkills(): List<RuntimeSkillMetadata> {
        return repo.skills.listEnabled()
    }

    override suspend fun loadSkill(id: String): RuntimeLoadedSkill? {
        return repo.skills.getDetail(id)
    }

    override suspend fun addMemory(value: String) {
        repo.memory.add(value)
    }

    override suspend fun removeMemory(oldText: String): MemoryMutationResult {
        return repo.memory.removeByText(oldText)
    }

    override suspend fun replaceMemory(oldText: String, content: String): MemoryMutationResult {
        return repo.memory.replaceByText(oldText, content)
    }

    override suspend fun listCustomPyTools(): List<RuntimeCustomPyTool> = repo.customPyTools.list()

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
        return repo.builtinTools.list()
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
}
