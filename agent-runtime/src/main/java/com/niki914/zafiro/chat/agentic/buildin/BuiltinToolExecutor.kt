package com.niki914.zafiro.chat.agentic.buildin

import com.niki914.zafiro.settings.RuntimeEnvironment
import com.niki914.zafiro.settings.model.RuntimePrivacyPolicy
import kotlinx.coroutines.CancellationException

class BuiltinToolExecutor(
    private val registry: BuiltinToolRegistry = BuiltinToolRegistry.default(),
) {
    fun find(name: String): BuiltinTool? {
        return registry.find(name)
    }

    suspend fun execute(
        name: String,
        argumentsJson: String,
    ): String {
        val tool = find(name)
            ?: return BuiltinToolResult.failure(
                code = "LOCAL_TOOL_NOT_EXECUTABLE",
                message = "Local tool '$name' is not executable in current runtime.",
                hint = "Check builtin_tool_flags or py_meta_tools configuration.",
            ).toJsonString()

        return execute(tool = tool, argumentsJson = argumentsJson)
    }

    suspend fun execute(
        tool: BuiltinTool,
        argumentsJson: String,
    ): String {
        privacyBlockReason(tool.name)?.let { reason ->
            return BuiltinToolResult.failure(
                code = "PRIVACY_MODE_BLOCKED",
                message = reason,
                hint = "Disable privacy mode only if you explicitly want to allow this capability.",
            ).toJsonString()
        }

        if (tool is RawJsonBuiltinTool) {
            return try {
                tool.invokeRawJson(
                    BuiltinToolRequest(
                        name = tool.name,
                        argumentsJson = argumentsJson,
                    )
                )
            } catch (throwable: Throwable) {
                if (throwable is CancellationException) {
                    throw throwable
                }
                BuiltinToolResult.failure(
                    code = "UNKNOWN_ERROR",
                    message = throwable.message ?: "Builtin tool '${tool.name}' failed.",
                    hint = "Inspect the builtin tool implementation and argumentsJson.",
                ).toJsonString()
            }
        }
        return try {
            tool.invoke(
                BuiltinToolRequest(
                    name = tool.name,
                    argumentsJson = argumentsJson,
                )
            ).toJsonString()
        } catch (throwable: Throwable) {
            if (throwable is CancellationException) {
                throw throwable
            }
            BuiltinToolResult.failure(
                code = "UNKNOWN_ERROR",
                message = throwable.message ?: "Builtin tool '${tool.name}' failed.",
                hint = "Inspect the builtin tool implementation and argumentsJson.",
            ).toJsonString()
        }
    }

    private suspend fun privacyBlockReason(toolName: String): String? {
        val policy = readPrivacyPolicyFailClosed()
        if (!policy.allowNetworkTools && toolName in PRIVACY_BLOCKED_NETWORK_TOOLS) {
            return "Tool '$toolName' is disabled because privacy mode blocks network-capable or arbitrary-code tools."
        }
        if (!policy.allowSensitiveContextUpload && toolName in PRIVACY_BLOCKED_SENSITIVE_CONTEXT_TOOLS) {
            return "Tool '$toolName' is disabled because privacy mode blocks sensitive screen or image context from entering the model tool loop."
        }
        return null
    }

    private suspend fun readPrivacyPolicyFailClosed(): RuntimePrivacyPolicy {
        return try {
            RuntimeEnvironment.requireSettingsGateway().readPrivacyPolicy()
        } catch (throwable: Throwable) {
            if (throwable is CancellationException) throw throwable
            // Fail closed when policy state is unavailable.
            RuntimePrivacyPolicy(enabled = true)
        }
    }

    private companion object {
        val PRIVACY_BLOCKED_NETWORK_TOOLS = setOf(
            "terminal",
            "execute_python",
            "py_meta_tools",
            "py_download_file",
            "open_uri",
        )

        val PRIVACY_BLOCKED_SENSITIVE_CONTEXT_TOOLS = setOf(
            "screen_operation_accessibility",
            "screen_operation_shell",
            "view_image",
        )
    }
}
