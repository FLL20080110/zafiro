package com.niki914.zafiro.chat.agentic.buildin

import com.niki914.zafiro.settings.RuntimeEnvironment
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
        if (privacyModeBlocks(tool.name)) {
            return BuiltinToolResult.failure(
                code = "PRIVACY_MODE_NETWORK_TOOL_BLOCKED",
                message = "Tool '${tool.name}' is disabled while privacy mode is enabled.",
                hint = "Disable privacy mode before using tools that can create external network connections.",
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

    private suspend fun privacyModeBlocks(toolName: String): Boolean {
        if (toolName !in PRIVACY_BLOCKED_NETWORK_TOOLS) return false

        return try {
            !RuntimeEnvironment.requireSettingsGateway()
                .readPrivacyPolicy()
                .allowNetworkTools
        } catch (_: Throwable) {
            // Fail closed for tools that can create an external network path if
            // the privacy policy is temporarily unavailable or unreadable.
            true
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
    }
}
