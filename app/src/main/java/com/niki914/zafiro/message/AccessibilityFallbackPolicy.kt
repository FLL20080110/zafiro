package com.niki914.zafiro.message

/** Pure policy used to decide whether an Accessibility suggestion may still target the UI. */
object AccessibilityFallbackPolicy {
    val supportedPackages = setOf(
        "com.tencent.mm",
        "com.tencent.mobileqq",
        "com.tencent.tim",
    )

    fun canFill(
        expectedPackage: String,
        expectedSessionId: Long,
        currentPackage: String,
        currentSessionId: Long,
        editableInputCount: Int,
    ): Boolean {
        return expectedPackage in supportedPackages &&
            currentPackage == expectedPackage &&
            expectedSessionId > 0L &&
            currentSessionId == expectedSessionId &&
            editableInputCount == 1
    }

    fun isEditableCandidate(
        isVisibleToUser: Boolean,
        isEnabled: Boolean,
        isEditable: Boolean,
        supportsSetText: Boolean,
        semanticLabel: String = "",
    ): Boolean {
        if (!isVisibleToUser || !isEnabled || (!isEditable && !supportsSetText)) return false
        return candidateScore(semanticLabel) >= 0
    }

    /**
     * Conservative semantic confidence for chat-input candidates.
     *
     * Positive values are chat-like hints, zero is neutral, and negative values are fields that
     * are clearly unrelated to composing a chat message. A neutral field can still be used only
     * when it is the single eligible editable node in the supported foreground app.
     */
    internal fun candidateScore(value: String): Int {
        val normalized = value.trim().lowercase()
        if (normalized.isEmpty()) return 0
        if (REJECT_MARKERS.any { marker -> marker in normalized }) return -100
        return CHAT_INPUT_MARKERS.count { marker -> marker in normalized }.coerceAtMost(3)
    }

    internal fun looksLikeSearchField(value: String): Boolean = candidateScore(value) < 0 &&
        SEARCH_MARKERS.any { marker -> marker in value.trim().lowercase() }

    private val SEARCH_MARKERS = setOf(
        "搜索",
        "查找",
        "search",
        "search_src_text",
        "search_edit",
        "search_input",
    )

    private val NON_CHAT_FORM_MARKERS = setOf(
        "password",
        "passwd",
        "密码",
        "邮箱",
        "email",
        "手机号",
        "phone number",
        "telephone",
        "用户名",
        "username",
        "账号",
        "account",
        "地址",
        "address",
    )

    private val REJECT_MARKERS = SEARCH_MARKERS + NON_CHAT_FORM_MARKERS

    private val CHAT_INPUT_MARKERS = setOf(
        "输入消息",
        "输入内容",
        "说点什么",
        "回复",
        "消息",
        "message",
        "reply",
        "type a message",
        "enter message",
        "chat_input",
        "input_message",
    )
}
