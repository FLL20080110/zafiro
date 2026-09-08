package com.niki914.zafiro.openai.auth

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/**
 * User-facing OpenAI/ChatGPT service error summary.
 *
 * Quota values stay nullable unless the server explicitly supplied them. This is
 * important for ChatGPT/Codex OAuth where usage-limit responses may expose only
 * a reset time and no numeric used/remaining quota.
 */
data class OpenAiServiceError(
    val type: String? = null,
    val code: String? = null,
    val planType: String? = null,
    val resetsAtEpochSeconds: Long? = null,
    val resetsInSeconds: Long? = null,
    val used: Long? = null,
    val remaining: Long? = null,
    val limit: Long? = null,
    val friendlyMessage: String,
    val rawMessage: String? = null
)

object OpenAiServiceErrorParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(raw: String?): OpenAiServiceError {
        val sanitized = sanitize(raw.orEmpty()).trim()
        val root = findJsonObject(sanitized)
        val error = (root?.get("error") as? JsonObject) ?: root

        val type = error.string("type") ?: error.string("error_type")
        val code = error.string("code")
        val message = error.string("message")
        val planType = error.string("plan_type")
        val resetsAt = error.long("resets_at")
        val resetsIn = error.long("resets_in_seconds")

        // Preserve quota only when explicitly supplied by the service. Support a
        // few common field spellings without inferring one value from another.
        val used = error.long("used") ?: error.long("used_units")
        val remaining = error.long("remaining") ?: error.long("remaining_units")
        val limit = error.long("limit") ?: error.long("total") ?: error.long("quota")

        return OpenAiServiceError(
            type = type,
            code = code,
            planType = planType,
            resetsAtEpochSeconds = resetsAt,
            resetsInSeconds = resetsIn,
            used = used,
            remaining = remaining,
            limit = limit,
            friendlyMessage = friendlyMessage(type, code, message, planType, resetsIn),
            rawMessage = sanitized.takeIf { it.isNotEmpty() }
        )
    }

    private fun friendlyMessage(
        type: String?,
        code: String?,
        message: String?,
        planType: String?,
        resetsInSeconds: Long?
    ): String {
        val key = listOfNotNull(type, code, message).joinToString(" ").lowercase()
        return when {
            "usage_limit_reached" in key -> {
                val plan = when (planType?.lowercase()) {
                    "plus" -> "ChatGPT Plus"
                    "pro" -> "ChatGPT Pro"
                    else -> "ChatGPT"
                }
                val reset = resetSuffix(resetsInSeconds)
                "$plan 使用额度已用完$reset"
            }
            "rate_limit" in key || "too many requests" in key ->
                "请求过于频繁，请稍后重试"
            "invalid_api_key" in key || "unauthorized" in key || "authentication" in key ||
                "token expired" in key || "expired token" in key ->
                "ChatGPT 登录已失效，请重新登录"
            "model_not_found" in key || "model unavailable" in key || "unsupported model" in key ->
                "当前模型不可用，请刷新模型列表或更换模型"
            "context_length" in key || "context window" in key ->
                "当前对话内容过长，请新建对话或减少上下文后重试"
            "network" in key || "connection" in key || "timeout" in key ->
                "网络连接失败，请检查网络后重试"
            !message.isNullOrBlank() -> "请求失败：${message.trim()}"
            else -> "请求失败，请稍后重试"
        }
    }

    private fun resetSuffix(seconds: Long?): String {
        if (seconds == null || seconds < 0L) return "，请稍后重试"
        if (seconds == 0L) return "，额度即将恢复"
        val minutes = (seconds + 59L) / 60L
        return if (minutes < 60L) {
            "，约 $minutes 分钟后恢复"
        } else {
            val hours = (minutes + 59L) / 60L
            "，约 $hours 小时后恢复"
        }
    }

    private fun findJsonObject(text: String): JsonObject? {
        if (text.isBlank()) return null
        val candidates = buildList {
            add(text)
            val start = text.indexOf('{')
            val end = text.lastIndexOf('}')
            if (start >= 0 && end > start) add(text.substring(start, end + 1))
        }
        for (candidate in candidates) {
            try {
                val element = json.parseToJsonElement(candidate)
                if (element is JsonObject) return element
            } catch (_: SerializationException) {
                // Fall through to a safe generic message.
            } catch (_: IllegalArgumentException) {
                // Malformed input must never crash error presentation.
            }
        }
        return null
    }

    private fun JsonObject?.string(name: String): String? =
        (this?.get(name) as? JsonPrimitive)?.contentOrNull

    private fun JsonObject?.long(name: String): Long? =
        (this?.get(name) as? JsonPrimitive)?.longOrNull

    internal fun sanitize(value: String): String {
        if (value.isEmpty()) return value
        return value
            .replace(Regex("(?i)Bearer\\s+[A-Za-z0-9._~+\\-/]+=*"), "Bearer [已隐藏]")
            .replace(
                Regex("(?i)(access_token|refresh_token|id_token)\\s*[:=]\\s*\\\"?[^\\\",}\\s]+"),
                "$1=[已隐藏]"
            )
    }
}
