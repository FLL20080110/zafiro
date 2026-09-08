package com.niki914.zafiro.settings

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeCapabilityRegistryTest {
    @After
    fun tearDown() {
        RuntimeCapabilityRegistry.clearForTest()
    }

    @Test
    fun notObservedXposedIsReportedAsUnknownNotAbsent() {
        RuntimeCapabilityRegistry.setXposedActivationConfirmed(false)
        val prompt = RuntimeCapabilityRegistry.promptFragment()

        assertTrue(prompt.contains("NOT OBSERVED"))
        assertTrue(prompt.contains("does NOT mean LSPosed/Xposed is absent or uninstalled"))
        assertTrue(prompt.contains("status unknown"))
        assertFalse(prompt.contains("Xposed/LSPosed module activation: not installed"))
    }

    @Test
    fun liveHeartbeatIsAuthoritativeAndAssistIsNotRequiredForAccessibility() {
        RuntimeCapabilityRegistry.setXposedActivationConfirmed(true)
        val prompt = RuntimeCapabilityRegistry.promptFragment()

        assertTrue(prompt.contains("CONFIRMED by a live injected host process"))
        assertTrue(prompt.contains("ASSIST/VoiceInteractionService and RECORD_AUDIO are NOT prerequisites"))
        assertTrue(prompt.contains("screen_operation_accessibility read -> editable field set_text -> send-button tap"))
        assertTrue(prompt.contains("Never infer that Xposed/LSPosed, Root, or Shizuku"))
    }
}
