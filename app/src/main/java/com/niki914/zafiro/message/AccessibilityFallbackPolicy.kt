package com.niki914.zafiro.message

/** Pure policy used to decide whether an Accessibility suggestion may still target the UI. */
object AccessibilityFallbackPolicy {
    fun canFill(
        expectedPackage: String,
        expectedSessionId: Long,
        currentPackage: String,
        currentSessionId: Long,
        editableInputCount: Int,
    ): Boolean {
        return expectedPackage in ChatAccessibilityFallback.SUPPORTED_PACKAGES &&
            currentPackage == expectedPackage &&
            expectedSessionId > 0L &&
            currentSessionId == expectedSessionId &&
            editableInputCount == 1
    }
}
