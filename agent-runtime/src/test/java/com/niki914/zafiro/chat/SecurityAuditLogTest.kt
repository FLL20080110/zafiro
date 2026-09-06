package com.niki914.zafiro.chat

import com.niki914.zafiro.chat.agentic.shell.SecurityAuditKind
import com.niki914.zafiro.chat.agentic.shell.SecurityAuditLog
import com.niki914.zafiro.chat.agentic.shell.SecurityRiskLevel
import com.niki914.zafiro.chat.agentic.shell.ToolPermissionCoordinator
import com.niki914.zafiro.chat.agentic.shell.ToolPermissionRequest
import com.niki914.zafiro.chat.agentic.shell.ToolPermissionResponse
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test

class SecurityAuditLogTest {
    @After
    fun tearDown() {
        SecurityAuditLog.clear()
        ToolPermissionCoordinator.canRequestUserConfirmation = false
        ToolPermissionCoordinator.clearTemporaryGrants()
        SecurityAuditLog.clear()
    }

    @Test
    fun record_storesOnlyCommandFingerprintNotPlaintext() {
        val command = "curl -H 'Authorization: Bearer abc123' https://example.invalid otp=123456 api_key=secret-value"
        SecurityAuditLog.record(
            kind = SecurityAuditKind.PERMISSION_REQUESTED,
            riskLevel = SecurityRiskLevel.HIGH,
            toolName = "terminal",
            command = command,
        )

        val event = SecurityAuditLog.events.value.single()
        assertNotNull(event.commandHashSha256)
        assertEquals(64, event.commandHashSha256?.length)
        val serializedEvent = event.toString()
        assertFalse(serializedEvent.contains(command))
        assertFalse(serializedEvent.contains("abc123"))
        assertFalse(serializedEvent.contains("123456"))
        assertFalse(serializedEvent.contains("secret-value"))
    }

    @Test
    fun record_keepsOnlyMostRecentTwoHundredEvents() {
        repeat(205) { index ->
            SecurityAuditLog.record(
                kind = SecurityAuditKind.POLICY_BLOCKED,
                riskLevel = SecurityRiskLevel.HIGH,
                policyCode = "TEST_$index",
            )
        }

        val events = SecurityAuditLog.events.value
        assertEquals(200, events.size)
        assertEquals("TEST_5", events.first().policyCode)
        assertEquals("TEST_204", events.last().policyCode)
    }

    @Test
    fun confirm_withoutInteractiveChannel_isAuditedFailClosed() = runTest {
        ToolPermissionCoordinator.canRequestUserConfirmation = false

        val response = ToolPermissionCoordinator.confirm(
            ToolPermissionRequest(
                id = "request-1",
                toolName = "terminal",
                command = "id",
                matchedRuleName = "Privileged identity: root",
                riskLevel = SecurityRiskLevel.HIGH,
            )
        )

        assertEquals(ToolPermissionResponse.DENIED_UNAVAILABLE, response)
        val event = SecurityAuditLog.events.value.last()
        assertEquals(SecurityAuditKind.PERMISSION_UNAVAILABLE, event.kind)
        assertEquals("terminal", event.toolName)
        assertEquals("Privileged identity: root", event.ruleName)
        assertEquals(SecurityRiskLevel.HIGH, event.riskLevel)
    }
}
