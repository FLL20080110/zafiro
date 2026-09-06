package com.niki914.zafiro.chat.agentic.python

import com.niki914.zafiro.chat.LocalTool
import com.niki914.zafiro.chat.agentic.shell.SecurityAuditKind
import com.niki914.zafiro.chat.agentic.shell.SecurityAuditLog
import com.niki914.zafiro.chat.agentic.shell.SecurityRiskLevel
import com.niki914.zafiro.settings.RuntimeEnvironment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * CustomPyTool 执行器：把 LLM 的参数 JSON 经 [CustomPyToolHarness.buildRunner] 拼接后
 * 交给 [PyRuntime.exec]（:python 进程）。输出即 stdout（runtime.py 已做
 * 50KB 截断）。结果用 {"ok":...} JSON 约定，与 BuiltinToolResult 对齐，
 * 由 LocalToolResultClassifier 拆 Success/Failure。
 */
class CustomPyToolExecutor(
    private val exec: suspend (code: String, timeoutMs: Long) -> String = PyRuntime::exec,
) {
    suspend fun execute(tool: LocalTool.Py, argumentsJson: String): String {
        if (privacyModeBlocksPython()) {
            SecurityAuditLog.record(
                kind = SecurityAuditKind.PRIVACY_BLOCKED,
                riskLevel = SecurityRiskLevel.HIGH,
                toolName = tool.name,
                policyCode = "PRIVACY_MODE_BLOCKED",
                reason = "Custom Python execution was blocked because privacy mode disables network-capable arbitrary code.",
            )
            return failureJson(
                tool.name,
                "PRIVACY_MODE_BLOCKED",
                "Custom Python tools are disabled while privacy mode blocks network-capable arbitrary code.",
            )
        }

        val args = parseArguments(argumentsJson)
        return try {
            val output = exec(CustomPyToolHarness.buildRunner(tool.code, args), tool.timeoutMs)
            JsonObject(
                mapOf(
                    "ok" to JsonPrimitive(true),
                    "tool" to JsonPrimitive(tool.name),
                    "stdout" to JsonPrimitive(output),
                )
            ).toString()
        } catch (e: TimeoutCancellationException) {
            failureJson(
                tool.name,
                "TIMEOUT",
                "Execution timed out after ${tool.timeoutMs / 1000}s."
            )
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            failureJson(tool.name, "PYTHON_ERROR", t.message ?: "Python execution failed.")
        }
    }

    private suspend fun privacyModeBlocksPython(): Boolean {
        return try {
            !RuntimeEnvironment.requireSettingsGateway()
                .readPrivacyPolicy()
                .allowNetworkTools
        } catch (throwable: Throwable) {
            if (throwable is CancellationException) throw throwable
            // Fail closed: arbitrary Python must not run when privacy state is unavailable.
            true
        }
    }

    private fun parseArguments(argumentsJson: String): String {
        if (argumentsJson.isBlank()) return "{}"
        val element = try {
            Json.parseToJsonElement(argumentsJson)
        } catch (_: Exception) {
            return "{}"
        }
        return if (element is JsonObject) argumentsJson else "{}"
    }

    private fun failureJson(name: String, code: String, message: String): String {
        return JsonObject(
            mapOf(
                "ok" to JsonPrimitive(false),
                "tool" to JsonPrimitive(name),
                "code" to JsonPrimitive(code),
                "message" to JsonPrimitive(message),
            )
        ).toString()
    }
}
