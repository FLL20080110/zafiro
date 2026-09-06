package com.niki914.zafiro.message

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilityFallbackPolicyTest {

    @Test
    fun allowsMatchingSupportedSessionWithSingleInput() {
        assertTrue(
            AccessibilityFallbackPolicy.canFill(
                expectedPackage = "com.tencent.mm",
                expectedSessionId = 7L,
                currentPackage = "com.tencent.mm",
                currentSessionId = 7L,
                editableInputCount = 1,
            )
        )
    }

    @Test
    fun rejectsPackageMismatch() {
        assertFalse(
            AccessibilityFallbackPolicy.canFill(
                expectedPackage = "com.tencent.mm",
                expectedSessionId = 7L,
                currentPackage = "com.tencent.mobileqq",
                currentSessionId = 7L,
                editableInputCount = 1,
            )
        )
    }

    @Test
    fun rejectsMissingInput() {
        assertFalse(
            AccessibilityFallbackPolicy.canFill(
                expectedPackage = "com.tencent.mm",
                expectedSessionId = 7L,
                currentPackage = "com.tencent.mm",
                currentSessionId = 7L,
                editableInputCount = 0,
            )
        )
    }

    @Test
    fun rejectsAmbiguousInputs() {
        assertFalse(
            AccessibilityFallbackPolicy.canFill(
                expectedPackage = "com.tencent.mm",
                expectedSessionId = 7L,
                currentPackage = "com.tencent.mm",
                currentSessionId = 7L,
                editableInputCount = 2,
            )
        )
    }

    @Test
    fun rejectsStaleSessionAfterConversationSwitch() {
        assertFalse(
            AccessibilityFallbackPolicy.canFill(
                expectedPackage = "com.tencent.mm",
                expectedSessionId = 7L,
                currentPackage = "com.tencent.mm",
                currentSessionId = 8L,
                editableInputCount = 1,
            )
        )
    }

    @Test
    fun acceptsVisibleEnabledEditableNode() {
        assertTrue(
            AccessibilityFallbackPolicy.isEditableCandidate(
                isVisibleToUser = true,
                isEnabled = true,
                isEditable = true,
                supportsSetText = false,
            )
        )
    }

    @Test
    fun acceptsVisibleEnabledSetTextNodeForCustomChatViews() {
        assertTrue(
            AccessibilityFallbackPolicy.isEditableCandidate(
                isVisibleToUser = true,
                isEnabled = true,
                isEditable = false,
                supportsSetText = true,
            )
        )
    }

    @Test
    fun rejectsHiddenEditableNode() {
        assertFalse(
            AccessibilityFallbackPolicy.isEditableCandidate(
                isVisibleToUser = false,
                isEnabled = true,
                isEditable = true,
                supportsSetText = true,
            )
        )
    }

    @Test
    fun rejectsDisabledEditableNode() {
        assertFalse(
            AccessibilityFallbackPolicy.isEditableCandidate(
                isVisibleToUser = true,
                isEnabled = false,
                isEditable = true,
                supportsSetText = true,
            )
        )
    }

    @Test
    fun rejectsVisibleEnabledNonEditableNode() {
        assertFalse(
            AccessibilityFallbackPolicy.isEditableCandidate(
                isVisibleToUser = true,
                isEnabled = true,
                isEditable = false,
                supportsSetText = false,
            )
        )
    }

    @Test
    fun rejectsChineseSearchField() {
        assertFalse(
            AccessibilityFallbackPolicy.isEditableCandidate(
                isVisibleToUser = true,
                isEnabled = true,
                isEditable = true,
                supportsSetText = true,
                semanticLabel = "搜索",
            )
        )
    }

    @Test
    fun rejectsEnglishSearchViewId() {
        assertFalse(
            AccessibilityFallbackPolicy.isEditableCandidate(
                isVisibleToUser = true,
                isEnabled = true,
                isEditable = true,
                supportsSetText = true,
                semanticLabel = "com.tencent.mobileqq:id/search_input",
            )
        )
    }

    @Test
    fun rejectsObviousLoginOrContactFormFields() {
        listOf("密码", "username", "email", "phone number", "address").forEach { label ->
            assertFalse(
                AccessibilityFallbackPolicy.isEditableCandidate(
                    isVisibleToUser = true,
                    isEnabled = true,
                    isEditable = true,
                    supportsSetText = true,
                    semanticLabel = label,
                )
            )
        }
    }

    @Test
    fun normalChatHintRemainsEligible() {
        assertTrue(
            AccessibilityFallbackPolicy.isEditableCandidate(
                isVisibleToUser = true,
                isEnabled = true,
                isEditable = true,
                supportsSetText = true,
                semanticLabel = "输入消息",
            )
        )
        assertTrue(AccessibilityFallbackPolicy.candidateScore("输入消息") > 0)
        assertTrue(AccessibilityFallbackPolicy.candidateScore("reply") > 0)
    }

    @Test
    fun neutralCustomChatFieldRemainsEligibleForVersionCompatibility() {
        assertEquals(0, AccessibilityFallbackPolicy.candidateScore("com.tencent.mm:id/b4a"))
        assertTrue(
            AccessibilityFallbackPolicy.isEditableCandidate(
                isVisibleToUser = true,
                isEnabled = true,
                isEditable = false,
                supportsSetText = true,
                semanticLabel = "com.tencent.mm:id/b4a",
            )
        )
    }
}
