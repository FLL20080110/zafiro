package com.niki914.zafiro.chat.agentic.buildin

import com.niki914.zafiro.chat.agentic.shell.ToolPermissionCoordinator
import com.niki914.zafiro.chat.agentic.shell.ToolPermissionRequest
import com.niki914.zafiro.chat.agentic.shell.ToolPermissionResponse
import com.niki914.zafiro.chat.agentic.shell.ToolPermissionRiskLevel
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.util.UUID

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
        privilegedTerminalGate(tool, argumentsJson)?.let { return it }

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

    /**
     * Root and Shizuku are capability boundaries, not merely command syntax.
     * Every local terminal command which requests either identity must receive a
     * fresh explicit user approval before the tool is invoked. The terminal's
     * existing command safety policy still runs afterwards, so catastrophic
     * commands remain hard-blocked even after privilege approval.
     */
    private suspend fun privilegedTerminalGate(
        tool: BuiltinTool,
        argumentsJson: String,
    ): String? {
        if (tool.name != "terminal") return null

        val args = try {
            Json.parseToJsonElement(argumentsJson) as? JsonObject
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        } ?: return null // TerminalBuiltin will return the normal malformed-request error.

        // Action mode (read/write/submit/close) operates on an already-created
        // session and carries no identity field. Gate creation/execution only.
        if (args.stringValue("action") != null) return null

        val backend = args.stringValue("backend")?.trim()?.lowercase() ?: "local"
        if (backend != "local") return null

        val identity = args.stringValue("identity")?.trim()?.lowercase() ?: "user"
        if (identity != "root" && identity != "shizuku") return null

        val command = args.stringValue("command") ?: "(terminal command)"
        val ruleName = "Privileged ${identity.uppercase()} execution"
        val response = ToolPermissionCoordinator.confirm(
            ToolPermissionRequest(
                id = UUID.randomUUID().toString(),
                toolName = tool.name,
                command = command,
                matchedRuleName = ruleName,
                riskLevel = ToolPermissionRiskLevel.HIGH,
                executionIdentity = identity,
                reason = "The AI requested execution with elevated Android privileges.",
                reversible = null,
            )
        )

        return when (response) {
            ToolPermissionResponse.ALLOWED -> null
            ToolPermissionResponse.DENIED_BY_USER -> BuiltinToolResult.failure(
                code = "PRIVILEGED_IDENTITY_DENIED",
                message = "The user denied privileged $identity execution.",
                hint = "Retry with identity=\"user\" or ask the user to approve the privileged operation.",
            ).toJsonString()

            ToolPermissionResponse.DENIED_UNAVAILABLE -> BuiltinToolResult.failure(
                code = "PRIVILEGED_IDENTITY_CONFIRM_UNAVAILABLE",
                message = "Privileged $identity execution requires explicit user confirmation, " +
                        "but this session cannot display a confirmation prompt.",
                hint = "Use an interactive UI session or retry with identity=\"user\".",
            ).toJsonString()
        }
    }

    private fun JsonObject.stringValue(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull
}
