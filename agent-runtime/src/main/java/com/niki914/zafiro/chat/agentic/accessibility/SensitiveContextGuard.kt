package com.niki914.zafiro.chat.agentic.accessibility

import android.text.InputType
import android.view.accessibility.AccessibilityNodeInfo
import com.niki914.zafiro.chat.agentic.shell.SecurityAuditEvent
import com.niki914.zafiro.chat.agentic.shell.SecurityAuditEventType
import com.niki914.zafiro.chat.agentic.shell.SecurityAuditLog
import com.niki914.zafiro.chat.agentic.shell.ToolPermissionRiskLevel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

enum class SensitiveContextKind {
    PASSWORD,
    OTP,
    PAYMENT,
}

data class SensitiveContextState(
    val active: Boolean = false,
    val kind: SensitiveContextKind? = null,
    val appPackage: String? = null,
    val reason: String? = null,
    val detectedAtEpochMs: Long? = null,
)

class SensitiveContextBlockedException(
    val kind: SensitiveContextKind,
    val appPackage: String,
    reason: String,
) : RuntimeException(
    "Sensitive context detected ($kind). User takeover is required before automation can continue. " +
        "Package: $appPackage. Reason: $reason"
)

/**
 * Local, model-independent sensitive-screen detector.
 *
 * The detector intentionally runs on AccessibilityNodeInfo before a UI tree is
 * serialized for the model. It uses multiple local signals inspired by mature
 * Android accessibility projects: password inputType/isPassword flags, OTP labels,
 * and transaction/payment context. It never logs or exports matched field text.
 *
 * A package name alone never blocks an app. Payment-package hints only strengthen
 * page-level signals so apps such as messaging/wallet clients are not blanket-blocked.
 */
object SensitiveContextGuard {
    private const val MAX_NODES_TO_SCAN = 500

    private val stateFlow = MutableStateFlow(SensitiveContextState())
    val state: StateFlow<SensitiveContextState> = stateFlow.asStateFlow()

    private var lastAuditSignature: String? = null

    fun requireSafe(root: AccessibilityNodeInfo, appPackage: String): Result<Unit> {
        val detection = inspect(root, appPackage)
        if (detection == null) {
            clearIfPreviouslyBlocked()
            return Result.success(Unit)
        }

        setBlocked(detection, appPackage)
        return Result.failure(
            SensitiveContextBlockedException(
                kind = detection.kind,
                appPackage = appPackage,
                reason = detection.reason,
            )
        )
    }

    private data class Detection(
        val kind: SensitiveContextKind,
        val reason: String,
    )

    private data class ScanStats(
        var nodeCount: Int = 0,
        var editableCount: Int = 0,
        var passwordFieldFound: Boolean = false,
        var otpKeywordFound: Boolean = false,
        var criticalPaymentKeywordFound: Boolean = false,
        val broadPaymentSignals: MutableSet<String> = linkedSetOf(),
    )

    private fun inspect(root: AccessibilityNodeInfo, appPackage: String): Detection? {
        val stats = ScanStats()
        scanNode(root, stats)

        if (stats.passwordFieldFound) {
            return Detection(
                kind = SensitiveContextKind.PASSWORD,
                reason = "A visible accessibility field is marked as a password input.",
            )
        }

        if (stats.otpKeywordFound && stats.editableCount > 0) {
            return Detection(
                kind = SensitiveContextKind.OTP,
                reason = "Verification/OTP context with an editable field is present.",
            )
        }

        if (stats.criticalPaymentKeywordFound) {
            return Detection(
                kind = SensitiveContextKind.PAYMENT,
                reason = "A high-confidence payment/card/transfer confirmation signal is present.",
            )
        }

        val packageLooksFinancial = looksFinancialPackage(appPackage)
        val paymentSignalCount = stats.broadPaymentSignals.size
        if (paymentSignalCount >= 2 && (stats.editableCount > 0 || packageLooksFinancial)) {
            return Detection(
                kind = SensitiveContextKind.PAYMENT,
                reason = "Multiple payment/transfer signals indicate a transaction-sensitive page.",
            )
        }

        if (packageLooksFinancial && paymentSignalCount >= 1 && stats.editableCount > 0) {
            return Detection(
                kind = SensitiveContextKind.PAYMENT,
                reason = "A financial-app page contains payment context and editable input.",
            )
        }

        return null
    }

    private fun scanNode(node: AccessibilityNodeInfo, stats: ScanStats) {
        if (stats.nodeCount >= MAX_NODES_TO_SCAN) return
        stats.nodeCount++

        if (node.isEditable) stats.editableCount++
        if (isPasswordNode(node)) stats.passwordFieldFound = true

        val signals = buildString {
            append(node.text?.toString().orEmpty())
            append(' ')
            append(node.contentDescription?.toString().orEmpty())
            append(' ')
            append(node.hintText?.toString().orEmpty())
            append(' ')
            append(node.viewIdResourceName.orEmpty())
        }.lowercase(Locale.ROOT)

        if (OTP_KEYWORDS.any { it in signals }) {
            stats.otpKeywordFound = true
        }
        if (CRITICAL_PAYMENT_KEYWORDS.any { it in signals }) {
            stats.criticalPaymentKeywordFound = true
        }
        BROAD_PAYMENT_KEYWORDS.forEach { keyword ->
            if (keyword in signals) stats.broadPaymentSignals += keyword
        }

        if (stats.nodeCount >= MAX_NODES_TO_SCAN) return
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            scanNode(child, stats)
            if (stats.nodeCount >= MAX_NODES_TO_SCAN) return
        }
    }

    private fun isPasswordNode(node: AccessibilityNodeInfo): Boolean {
        if (node.isPassword) return true

        val inputType = node.inputType
        val inputClass = inputType and InputType.TYPE_MASK_CLASS
        val variation = inputType and InputType.TYPE_MASK_VARIATION

        val textPassword = inputClass == InputType.TYPE_CLASS_TEXT && variation in setOf(
            InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
        )
        val numberPassword = inputClass == InputType.TYPE_CLASS_NUMBER &&
            variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
        return textPassword || numberPassword
    }

    private fun looksFinancialPackage(appPackage: String): Boolean {
        val pkg = appPackage.lowercase(Locale.ROOT)
        return FINANCIAL_PACKAGE_MARKERS.any { marker -> marker in pkg }
    }

    private fun setBlocked(detection: Detection, appPackage: String) {
        val signature = "${detection.kind}|$appPackage|${detection.reason}"
        val previous = stateFlow.value
        if (!previous.active || lastAuditSignature != signature) {
            SecurityAuditLog.record(
                SecurityAuditEvent(
                    type = SecurityAuditEventType.SENSITIVE_CONTEXT_BLOCKED,
                    riskLevel = ToolPermissionRiskLevel.CRITICAL,
                    detail = "kind=${detection.kind}; package=$appPackage; reason=${detection.reason}",
                )
            )
            lastAuditSignature = signature
        }
        stateFlow.value = SensitiveContextState(
            active = true,
            kind = detection.kind,
            appPackage = appPackage,
            reason = detection.reason,
            detectedAtEpochMs = previous.detectedAtEpochMs ?: System.currentTimeMillis(),
        )
    }

    private fun clearIfPreviouslyBlocked() {
        val previous = stateFlow.value
        if (!previous.active) return

        SecurityAuditLog.record(
            SecurityAuditEvent(
                type = SecurityAuditEventType.SENSITIVE_CONTEXT_CLEARED,
                riskLevel = ToolPermissionRiskLevel.LOW,
                detail = "Sensitive context cleared locally; automation may resume.",
            )
        )
        stateFlow.value = SensitiveContextState()
        lastAuditSignature = null
    }

    private val OTP_KEYWORDS = listOf(
        "otp",
        "验证码",
        "认证码",
        "动态码",
        "短信码",
        "校验码",
        "一次性密码",
        "verification code",
        "one-time code",
        "one time code",
        "authentication code",
    )

    private val CRITICAL_PAYMENT_KEYWORDS = listOf(
        "支付密码",
        "付款密码",
        "交易密码",
        "确认支付",
        "确认付款",
        "确认转账",
        "转账金额",
        "付款码",
        "银行卡号",
        "信用卡号",
        "cvv",
        "cvc",
        "card number",
        "confirm payment",
        "pay now",
        "transfer money",
    )

    private val BROAD_PAYMENT_KEYWORDS = listOf(
        "支付",
        "付款",
        "转账",
        "收款",
        "银行卡",
        "信用卡",
        "金额",
        "payment",
        "checkout",
        "transfer",
        "bank card",
        "credit card",
        "debit card",
    )

    private val FINANCIAL_PACKAGE_MARKERS = listOf(
        "bank",
        "wallet",
        "finance",
        "alipay",
        "paypal",
        ".pay",
        "payment",
    )
}
