package com.niki914.zafiro.repo

import com.niki914.zafiro.repo.SettingsJsonCodecUtils.boolean
import com.niki914.zafiro.repo.SettingsJsonCodecUtils.int
import com.niki914.zafiro.repo.SettingsJsonCodecUtils.long
import com.niki914.zafiro.repo.SettingsJsonCodecUtils.parseObject
import com.niki914.zafiro.repo.SettingsJsonCodecUtils.string
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal data class AppStateSettings(
    val onboardingCompleted: Boolean = false,
    val startupAssistantUi: String = "auto",
    val lastOpenedAgentId: String = "main",
    val lastOpenedConversationId: String = "",
    /** BCP-47 tag；空串 = 跟随系统语言。 */
    val languageTag: String = "",
    /** 冷启动是否恢复上次会话；false = 默认进入新对话。 */
    val loadLastConversationOnStartup: Boolean = true,
    /** 主题深浅色模式；system/light/dark。 */
    val themeMode: String = "dark",
    /** 主题种子色 ARGB hex；空串 = 跟随壁纸动态色。 */
    val themeSeedColor: String = "FF52DBC9",
    /** 流式空闲超时秒数；0 = 不超时。 */
    val llmIdleTimeoutSeconds: Long = 60L,
    /** 传输层自动重试次数。 */
    val llmRetryMaxAttempts: Int = 3,
    /** 隐私模式：禁用云端 LLM、MCP 与记忆写入等外发/持久化能力。 */
    val privacyModeEnabled: Boolean = false,
    /** 用户标记为敏感应用的包名，逗号分隔；包名语法本身不含逗号。 */
    val sensitiveAppPackagesCsv: String = "",
    /** 消息助手模式：off / suggest / auto_reply。 */
    val messageAssistantMode: String = "off",
    /** 允许消息助手处理的应用包名，逗号分隔。 */
    val messageAssistantPackagesCsv: String = "com.tencent.mm,com.tencent.mobileqq,com.tencent.tim",
    /** 允许自动回复的可信会话键，换行分隔；建议回复模式不要求白名单。 */
    val messageAssistantTrustedConversations: String = "",
)

internal object AppStateSettingsCodec {
    fun parse(json: String): AppStateSettings {
        val root = parseObject(json)
        return AppStateSettings(
            onboardingCompleted = root.boolean(ONBOARDING_COMPLETED_KEY, default = false),
            startupAssistantUi = root.string(STARTUP_ASSISTANT_UI_KEY).ifBlank { "auto" },
            lastOpenedAgentId = root.string(LAST_OPENED_AGENT_ID_KEY).ifBlank { "main" },
            lastOpenedConversationId = root.string(LAST_OPENED_CONVERSATION_ID_KEY),
            languageTag = root.string(LANGUAGE_TAG_KEY),
            loadLastConversationOnStartup = root.boolean(
                LOAD_LAST_CONVERSATION_KEY,
                default = true
            ),
            themeMode = root.string(THEME_MODE_KEY).ifBlank { "dark" },
            themeSeedColor = root.string(THEME_SEED_COLOR_KEY).ifBlank { "FF52DBC9" },
            llmIdleTimeoutSeconds = root.long(LLM_IDLE_TIMEOUT_KEY, default = 60L),
            llmRetryMaxAttempts = root.int(LLM_RETRY_ATTEMPTS_KEY, default = 3),
            privacyModeEnabled = root.boolean(PRIVACY_MODE_ENABLED_KEY, default = false),
            sensitiveAppPackagesCsv = root.string(SENSITIVE_APP_PACKAGES_KEY),
            messageAssistantMode = root.string(MESSAGE_ASSISTANT_MODE_KEY).ifBlank { "off" },
            messageAssistantPackagesCsv = root.string(MESSAGE_ASSISTANT_PACKAGES_KEY)
                .ifBlank { DEFAULT_MESSAGE_ASSISTANT_PACKAGES },
            messageAssistantTrustedConversations = root.string(MESSAGE_ASSISTANT_TRUSTED_CONVERSATIONS_KEY),
        )
    }

    fun encode(state: AppStateSettings): String {
        return JsonObject(
            mapOf(
                ONBOARDING_COMPLETED_KEY to JsonPrimitive(state.onboardingCompleted),
                STARTUP_ASSISTANT_UI_KEY to JsonPrimitive(state.startupAssistantUi),
                LAST_OPENED_AGENT_ID_KEY to JsonPrimitive(state.lastOpenedAgentId),
                LAST_OPENED_CONVERSATION_ID_KEY to JsonPrimitive(state.lastOpenedConversationId),
                LANGUAGE_TAG_KEY to JsonPrimitive(state.languageTag),
                LOAD_LAST_CONVERSATION_KEY to JsonPrimitive(state.loadLastConversationOnStartup),
                THEME_MODE_KEY to JsonPrimitive(state.themeMode),
                THEME_SEED_COLOR_KEY to JsonPrimitive(state.themeSeedColor),
                LLM_IDLE_TIMEOUT_KEY to JsonPrimitive(state.llmIdleTimeoutSeconds),
                LLM_RETRY_ATTEMPTS_KEY to JsonPrimitive(state.llmRetryMaxAttempts),
                PRIVACY_MODE_ENABLED_KEY to JsonPrimitive(state.privacyModeEnabled),
                SENSITIVE_APP_PACKAGES_KEY to JsonPrimitive(state.sensitiveAppPackagesCsv),
                MESSAGE_ASSISTANT_MODE_KEY to JsonPrimitive(state.messageAssistantMode),
                MESSAGE_ASSISTANT_PACKAGES_KEY to JsonPrimitive(state.messageAssistantPackagesCsv),
                MESSAGE_ASSISTANT_TRUSTED_CONVERSATIONS_KEY to JsonPrimitive(state.messageAssistantTrustedConversations),
            )
        ).toString()
    }

    private const val ONBOARDING_COMPLETED_KEY = "onboarding_completed"
    private const val STARTUP_ASSISTANT_UI_KEY = "startup_assistant_ui"
    private const val LAST_OPENED_AGENT_ID_KEY = "last_opened_agent_id"
    private const val LAST_OPENED_CONVERSATION_ID_KEY = "last_opened_conversation_id"
    private const val LANGUAGE_TAG_KEY = "language_tag"
    private const val LOAD_LAST_CONVERSATION_KEY = "load_last_conversation_on_startup"
    private const val THEME_MODE_KEY = "theme_mode"
    private const val THEME_SEED_COLOR_KEY = "theme_seed_color"
    private const val LLM_IDLE_TIMEOUT_KEY = "llm_idle_timeout_seconds"
    private const val LLM_RETRY_ATTEMPTS_KEY = "llm_retry_max_attempts"
    private const val PRIVACY_MODE_ENABLED_KEY = "privacy_mode_enabled"
    private const val SENSITIVE_APP_PACKAGES_KEY = "sensitive_app_packages"
    private const val MESSAGE_ASSISTANT_MODE_KEY = "message_assistant_mode"
    private const val MESSAGE_ASSISTANT_PACKAGES_KEY = "message_assistant_packages"
    private const val MESSAGE_ASSISTANT_TRUSTED_CONVERSATIONS_KEY = "message_assistant_trusted_conversations"
    private const val DEFAULT_MESSAGE_ASSISTANT_PACKAGES = "com.tencent.mm,com.tencent.mobileqq,com.tencent.tim"
}
