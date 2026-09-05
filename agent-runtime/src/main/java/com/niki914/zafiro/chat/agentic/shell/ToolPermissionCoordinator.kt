package com.niki914.zafiro.chat.agentic.shell

import com.niki914.logging.Logger
import com.niki914.zafiro.chat.agentic.shell.ToolPermissionCoordinator.canRequestUserConfirmation
import com.niki914.zafiro.chat.agentic.shell.ToolPermissionCoordinator.pendingConfirmation
import com.niki914.zafiro.chat.agentic.shell.ToolPermissionCoordinator.respond
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ToolPermissionRiskLevel { LOW, MEDIUM, HIGH, CRITICAL }

/** 一次工具执行确认请求（UI 展示字段）。 */
data class ToolPermissionRequest(
    val id: String,
    val toolName: String,
    val command: String,
    val matchedRuleName: String,
    val riskLevel: ToolPermissionRiskLevel = ToolPermissionRiskLevel.MEDIUM,
    val executionIdentity: String? = null,
    val reason: String? = null,
    val reversible: Boolean? = null,
    /** Optional exact approval scope for a short process-local grant. */
    val temporaryGrantScopeKey: String? = null,
    /** TTL for [temporaryGrantScopeKey]. Null/<=0 disables temporary approval. */
    val temporaryGrantDurationMs: Long? = null,
    /** Human-readable scope summary; never used as the security key. */
    val temporaryGrantLabel: String? = null,
)

/** 确认请求终态：允许一次 / 临时允许 / 用户拒绝 / 无确认渠道拒绝。 */
enum class ToolPermissionResponse {
    ALLOWED,
    ALLOWED_TEMPORARY,
    DENIED_BY_USER,
    DENIED_UNAVAILABLE,
}

/**
 * CONFIRM 型执行规则的用户确认协调器。
 * - LLMController.stream 按来源设置 [canRequestUserConfirmation]：
 *   UI 直连 = true；宿主 Binder = false（默认拒绝，Agent 收到英文错误）。
 * - UI collect [pendingConfirmation] 渲染对话框，[respond] 解除挂起；
 *   永不超时（用户明确决策，PRD §3）。
 * - LLMController 单活跃回合，confirm/respond 无并发竞争。
 * - 临时授权仅存在进程内，且由不可逆 scope key 精确匹配。
 */
object ToolPermissionCoordinator {
    private const val LOG_TAG = "niki914_nexus_ToolPermission"

    @Volatile
    var canRequestUserConfirmation: Boolean = false

    private val pendingFlow = MutableStateFlow<ToolPermissionRequest?>(null)

    /** 当前待确认请求；null = 无挂起确认。 */
    val pendingConfirmation: StateFlow<ToolPermissionRequest?> = pendingFlow.asStateFlow()

    private var deferred: CompletableDeferred<ToolPermissionResponse>? = null

    suspend fun confirm(request: ToolPermissionRequest): ToolPermissionResponse {
        request.temporaryGrantScopeKey?.let { scopeKey ->
            if (TemporaryGrantStore.isGranted(scopeKey)) {
                Logger.i(LOG_TAG, "confirm reused temporary grant id=${request.id} tool=${request.toolName}")
                return ToolPermissionResponse.ALLOWED_TEMPORARY
            }
        }

        if (!canRequestUserConfirmation) {
            Logger.i(LOG_TAG, "confirm denied source=host id=${request.id}")
            return ToolPermissionResponse.DENIED_UNAVAILABLE
        }
        Logger.i(LOG_TAG, "confirm requested id=${request.id} tool=${request.toolName}")
        pendingFlow.value = request
        val waiter = CompletableDeferred<ToolPermissionResponse>()
        deferred = waiter
        try {
            return waiter.await()
        } finally {
            if (deferred === waiter) {
                deferred = null
                pendingFlow.value = null
            }
        }
    }

    /** UI 决策入口；requestId 与当前挂起请求不一致时忽略。 */
    fun respond(requestId: String, response: ToolPermissionResponse) {
        val request = pendingFlow.value ?: return
        if (request.id != requestId) return

        val finalResponse = if (response == ToolPermissionResponse.ALLOWED_TEMPORARY) {
            val scopeKey = request.temporaryGrantScopeKey
            val durationMs = request.temporaryGrantDurationMs ?: 0L
            if (scopeKey != null && durationMs > 0L) {
                TemporaryGrantStore.grant(scopeKey, durationMs)
                Logger.i(
                    LOG_TAG,
                    "temporary grant created id=${request.id} tool=${request.toolName} durationMs=$durationMs"
                )
                ToolPermissionResponse.ALLOWED_TEMPORARY
            } else {
                Logger.i(LOG_TAG, "temporary grant unavailable; downgraded to allow-once id=${request.id}")
                ToolPermissionResponse.ALLOWED
            }
        } else {
            response
        }

        deferred?.complete(finalResponse)
    }

    /** 兼容现有二按钮 UI/调用方。 */
    fun respond(requestId: String, allowed: Boolean) {
        respond(
            requestId = requestId,
            response = if (allowed) {
                ToolPermissionResponse.ALLOWED
            } else {
                ToolPermissionResponse.DENIED_BY_USER
            },
        )
    }

    /** 急停时立即拒绝当前挂起请求，避免 Agent 永久卡在等待确认状态。 */
    fun denyPendingForEmergencyStop() {
        val request = pendingFlow.value ?: return
        Logger.i(LOG_TAG, "confirm denied emergency_stop id=${request.id}")
        deferred?.complete(ToolPermissionResponse.DENIED_BY_USER)
    }
}
