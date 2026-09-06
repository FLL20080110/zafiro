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
    ): Boolean {
        return isVisibleToUser &&
            isEnabled &&
            (isEditable || supportsSetText)
    }
}
