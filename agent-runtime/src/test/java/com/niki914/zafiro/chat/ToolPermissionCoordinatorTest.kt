package com.niki914.zafiro.chat

import com.niki914.zafiro.chat.agentic.shell.SecurityAuditKind
import com.niki914.zafiro.chat.agentic.shell.SecurityAuditLog
import com.niki914.zafiro.chat.agentic.shell.ToolPermissionCoordinator
import com.niki914.zafiro.chat.agentic.shell.ToolPermissionRequest
import com.niki914.zafiro.chat.agentic.shell.ToolPermissionResponse
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolPermissionCoordinatorTest {

    @After
    fun tearDown() {
        ToolPermissionCoordinator.canRequestUserConfirmation = false
        ToolPermissionCoordinator.clearTemporaryGrants()
        SecurityAuditLog.clear()
    }

    @Test
    fun allowOnceDoesNotCreateReusableGrant() = runTest {
        ToolPermissionCoordinator.canRequestUserConfirmation = true
        val request = request(command = "settings put secure test_key 1")

        val first = async { ToolPermissionCoordinator.confirm(request) }
        runCurrent()
        assertEquals(request.id, ToolPermissionCoordinator.pendingConfirmation.value?.id)
        ToolPermissionCoordinator.respond(request.id, allowed = true)
        assertEquals(ToolPermissionResponse.ALLOWED, first.await())

        val second = async { ToolPermissionCoordinator.confirm(request.copy(id = "request-2")) }
        runCurrent()
        assertFalse(second.isCompleted)
        assertEquals("request-2", ToolPermissionCoordinator.pendingConfirmation.value?.id)

        ToolPermissionCoordinator.respond("request-2", allowed = false)
        assertEquals(ToolPermissionResponse.DENIED_BY_USER, second.await())
        assertNull(ToolPermissionCoordinator.pendingConfirmation.value)
    }

    @Test
    fun temporaryGrantIsReusedOnlyForExactScope() = runTest {
        ToolPermissionCoordinator.canRequestUserConfirmation = true
        val request = request(command = "settings put secure test_key 1")

        val first = async { ToolPermissionCoordinator.confirm(request) }
        runCurrent()
        ToolPermissionCoordinator.respondTemporary(request.id)
        assertEquals(ToolPermissionResponse.ALLOWED, first.await())

        val sameScope = ToolPermissionCoordinator.confirm(request.copy(id = "request-2"))
        assertEquals(ToolPermissionResponse.ALLOWED, sameScope)
        assertNull(ToolPermissionCoordinator.pendingConfirmation.value)

        val changedCommand = async {
            ToolPermissionCoordinator.confirm(
                request.copy(id = "request-3", command = "settings put secure test_key 2")
            )
        }
        runCurrent()
        assertFalse(changedCommand.isCompleted)
        assertEquals("request-3", ToolPermissionCoordinator.pendingConfirmation.value?.id)

        ToolPermissionCoordinator.respond("request-3", allowed = false)
        assertEquals(ToolPermissionResponse.DENIED_BY_USER, changedCommand.await())
    }

    @Test
    fun hostPathCannotBorrowUiTemporaryGrant() = runTest {
        ToolPermissionCoordinator.canRequestUserConfirmation = true
        val request = request(command = "settings put secure test_key 1")

        val first = async { ToolPermissionCoordinator.confirm(request) }
        runCurrent()
        ToolPermissionCoordinator.respondTemporary(request.id)
        assertEquals(ToolPermissionResponse.ALLOWED, first.await())

        ToolPermissionCoordinator.canRequestUserConfirmation = false
        val hostResult = ToolPermissionCoordinator.confirm(request.copy(id = "host-request"))
        assertEquals(ToolPermissionResponse.DENIED_UNAVAILABLE, hostResult)
        assertNull(ToolPermissionCoordinator.pendingConfirmation.value)
        assertTrue(SecurityAuditLog.events.value.isNotEmpty())
    }

    @Test
    fun freeformRiskReasonIsNotCopiedIntoAuditRecord() = runTest {
        ToolPermissionCoordinator.canRequestUserConfirmation = true
        val secret = "Bearer super-secret-token otp=123456"
        val request = request(command = "settings put secure test_key 1").copy(riskReason = secret)

        val pending = async { ToolPermissionCoordinator.confirm(request) }
        runCurrent()

        val requested = SecurityAuditLog.events.value.single {
            it.kind == SecurityAuditKind.PERMISSION_REQUESTED
        }
        assertFalse(requested.toString().contains(secret))
        assertFalse(requested.toString().contains("super-secret-token"))
        assertFalse(requested.toString().contains("123456"))
        assertEquals("CONFIRM_REQUESTED", requested.policyCode)
        assertEquals("Explicit user confirmation required.", requested.reason)

        ToolPermissionCoordinator.respond(request.id, allowed = false)
        assertEquals(ToolPermissionResponse.DENIED_BY_USER, pending.await())
    }

    private fun request(command: String) = ToolPermissionRequest(
        id = "request-1",
        toolName = "terminal",
        command = command,
        matchedRuleName = "Privileged identity: root",
        temporaryGrantMillis = 5 * 60 * 1000L,
    )
}
