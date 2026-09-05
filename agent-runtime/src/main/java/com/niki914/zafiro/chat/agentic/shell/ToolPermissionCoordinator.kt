package com.niki914.zafiro.chat.agentic.shell

import com.niki914.logging.Logger
import com.niki914.zafiro.chat.agentic.shell.ToolPermissionCoordinator.canRequestUserConfirmation
import com.niki914.zafiro.chat.agentic.shell.ToolPermissionCoordinator.pendingConfirmation
import com.niki914.zafiro.chat.agentic.shell.ToolPermissionCoordinator.respond
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** 一次工具执行确认请求（UI 展示字段）。 */
data class ToolPermissionRequest(
    val id: String,
    val toolName: String,
    val command: String,
    val matchedRuleName: String,
    /**
     * 大于 0 时 UI 可以提供“临时允许”。授权范围严格绑定当前
     * toolName + matchedRuleName + command，且只保存在当前进程内存中。
     */
    val temporaryGrantMillis: Long? = null,
)

/** 确认请求终态：允许 / 用户拒绝 / 无确认渠道（宿主路径）拒绝。 */
enum class ToolPermissionResponse { ALLOWED, DENIED_BY_USER, DENIED_UNAVAILABLE }

/**
 * CONFIRM 型执行规则的用户确认协调器。
 * - LLMController.stream 按来源设置 [canRequestUserConfirmation]：
 *   UI 直连 = true；宿主 Binder = false（默认拒绝，Agent 收到英文错误）。
 * - UI collect [pendingConfirmation] 渲染对话框，[respond] 解除挂起；
 * - 临时授权仅存在内存，按精确请求作用域缓存，到期或进程退出即失效；
 * - LLMController 单活跃回合，confirm/respond 无并发竞争。
 */
object ToolPermissionCoordinator {
    private const val LOG_TAG = "niki914_nexus_ToolPermission"

    @Volatile
    var canRequestUserConfirmation: Boolean = false

    private val pendingFlow = MutableStateFlow<ToolPermissionRequest?>(null)

    /** 当前待确认请求；null = 无挂起确认。 */
    val pendingConfirmation: StateFlow<ToolPermissionRequest?> = pendingFlow.asStateFlow()

    private var deferred: CompletableDeferred<ToolPermissionResponse>? = null
    private val temporaryGrantLock = Any()
    private val temporaryGrantExpiryNanos = mutableMapOf<String, Long>()

    suspend fun confirm(request: ToolPermissionRequest): ToolPermissionResponse {
        // 宿主/Binder 路径继续 fail-closed，不能借用 UI 会话里曾经批准的临时授权。
        if (!canRequestUserConfirmation) {
            Logger.i(LOG_TAG, "confirm denied source=host id=${request.id}")
            return ToolPermissionResponse.DENIED_UNAVAILABLE
        }

        if (hasActiveTemporaryGrant(request)) {
            Logger.i(LOG_TAG, "confirm temporary grant hit id=${request.id} tool=${request.toolName}")
            return ToolPermissionResponse.ALLOWED
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
    fun respond(requestId: String, allowed: Boolean) {
        if (pendingFlow.value?.id != requestId) return
        deferred?.complete(
            if (allowed) ToolPermissionResponse.ALLOWED else ToolPermissionResponse.DENIED_BY_USER
        )
    }

    /**
     * 临时允许当前精确请求。只有请求方显式声明 [ToolPermissionRequest.temporaryGrantMillis]
     * 时才生效；缓存键为 SHA-256，不保存第二份原始命令文本。
     */
    fun respondTemporary(requestId: String) {
        val request = pendingFlow.value ?: return
        if (request.id != requestId) return
        val ttlMillis = request.temporaryGrantMillis?.takeIf { it > 0L } ?: return
        val key = temporaryGrantScopeKey(request)
        val expiresAt = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(ttlMillis)
        synchronized(temporaryGrantLock) {
            temporaryGrantExpiryNanos[key] = expiresAt
        }
        Logger.i(LOG_TAG, "temporary grant stored id=${request.id} tool=${request.toolName} ttlMs=$ttlMillis")
        deferred?.complete(ToolPermissionResponse.ALLOWED)
    }

    /** 供急停、隐私模式或未来账号切换时主动清空临时授权。 */
    fun clearTemporaryGrants() {
        synchronized(temporaryGrantLock) {
            temporaryGrantExpiryNanos.clear()
        }
        Logger.i(LOG_TAG, "temporary grants cleared")
    }

    private fun hasActiveTemporaryGrant(request: ToolPermissionRequest): Boolean {
        if ((request.temporaryGrantMillis ?: 0L) <= 0L) return false
        val key = temporaryGrantScopeKey(request)
        val now = System.nanoTime()
        return synchronized(temporaryGrantLock) {
            val expiresAt = temporaryGrantExpiryNanos[key] ?: return@synchronized false
            if (expiresAt <= now) {
                temporaryGrantExpiryNanos.remove(key)
                false
            } else {
                true
            }
        }
    }

    private fun temporaryGrantScopeKey(request: ToolPermissionRequest): String {
        val rawScope = buildString {
            append(request.toolName)
            append('\u0000')
            append(request.matchedRuleName)
            append('\u0000')
            append(request.command)
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(rawScope.toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
    }
}
