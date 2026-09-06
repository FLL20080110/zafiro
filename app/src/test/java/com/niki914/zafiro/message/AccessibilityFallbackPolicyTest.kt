package com.niki914.zafiro.message

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
}
