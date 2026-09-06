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
        return !looksLikeSearchField(semanticLabel)
    }

    internal fun looksLikeSearchField(value: String): Boolean {
        val normalized = value.trim().lowercase()
        if (normalized.isEmpty()) return false
        return SEARCH_MARKERS.any { marker -> marker in normalized }
    }

    private val SEARCH_MARKERS = setOf(
        "搜索",
        "查找",
        "search",
        "search_src_text",
        "search_edit",
        "search_input",
    )
}
