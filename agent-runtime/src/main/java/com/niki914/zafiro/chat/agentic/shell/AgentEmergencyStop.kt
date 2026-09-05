package com.niki914.zafiro.chat.agentic.shell

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide hard stop for agent tool execution.
 *
 * This gate is intentionally local and model-independent. When active, new
 * builtin tool calls are rejected before they reach Root/Shizuku/Accessibility
 * or other executors. Clearing the stop requires an explicit local UI action.
 */
object AgentEmergencyStop {
    data class State(
        val active: Boolean = false,
        val reason: String? = null,
        val activatedAtEpochMs: Long? = null,
    )

    private val stateFlow = MutableStateFlow(State())
    val state: StateFlow<State> = stateFlow.asStateFlow()

    @Volatile
    private var activeFlag: Boolean = false

    fun isActive(): Boolean = activeFlag

    fun activate(reason: String = "Stopped by user") {
        activeFlag = true
        stateFlow.value = State(
            active = true,
            reason = reason,
            activatedAtEpochMs = System.currentTimeMillis(),
        )
        // Never leave a permission request suspended after the user hits stop.
        ToolPermissionCoordinator.denyPendingForEmergencyStop()
    }

    fun clear() {
        activeFlag = false
        stateFlow.value = State()
    }
}
