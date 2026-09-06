package com.niki914.zafiro.chat.agentic.accessibility

import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Local-only sensitive page classifier used to pause AI screen interaction before
 * password, OTP, or payment UI can be read or acted on.
 *
 * The classifier intentionally keeps no page text and performs no network I/O.
 * A root provider is installed by the host AccessibilityService so every decision
 * is evaluated against the current active window rather than a cached snapshot.
 */
object SensitivePageGuard {
    enum class Kind {
        PASSWORD,
        OTP,
        PAYMENT,
    }

    data class Decision(
        val blocked: Boolean,
        val kind: Kind? = null,
        val reasonCode: String? = null,
    )

    @Volatile
    private var rootProvider: (() -> AccessibilityNodeInfo?)? = null

    fun installRootProvider(provider: () -> AccessibilityNodeInfo?) {
        rootProvider = provider
    }

    fun clearRootProvider() {
        rootProvider = null
    }

    /** Evaluate the current foreground accessibility tree without retaining it. */
    fun evaluateCurrent(): Decision {
        val root = try {
            rootProvider?.invoke()
        } catch (_: Throwable) {
            null
        } ?: return Decision(blocked = false)

        return evaluate(root)
    }

    internal fun evaluate(root: AccessibilityNodeInfo): Decision {
        var hasEditableField = false
        var hasOtpSignal = false
        var hasStrongPaymentSignal = false
        var hasGeneralPaymentSignal = false
        var visited = 0

        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.add(root)

        while (stack.isNotEmpty() && visited < MAX_NODES_TO_SCAN) {
            val node = stack.removeLast()
            visited += 1

            // Android exposes password semantics directly. This is the strongest,
            // locale-independent signal and therefore wins immediately.
            if (node.isPassword) {
                return Decision(
                    blocked = true,
                    kind = Kind.PASSWORD,
                    reasonCode = "PASSWORD_FIELD",
                )
            }

            if (node.isEditable) hasEditableField = true

            val semanticText = buildString {
                append(node.text?.toString().orEmpty())
                append(' ')
                append(node.contentDescription?.toString().orEmpty())
                append(' ')
                append(node.viewIdResourceName.orEmpty())
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    append(' ')
                    append(node.hintText?.toString().orEmpty())
                }
            }.lowercase()

            if (OTP_KEYWORDS.any { it in semanticText }) {
                hasOtpSignal = true
            }
            if (STRONG_PAYMENT_KEYWORDS.any { it in semanticText }) {
                hasStrongPaymentSignal = true
            }
            if (GENERAL_PAYMENT_KEYWORDS.any { it in semanticText }) {
                hasGeneralPaymentSignal = true
            }

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let(stack::add)
            }
        }

        if (hasOtpSignal && hasEditableField) {
            return Decision(
                blocked = true,
                kind = Kind.OTP,
                reasonCode = "OTP_ENTRY_PAGE",
            )
        }

        if (hasStrongPaymentSignal || (hasGeneralPaymentSignal && hasEditableField)) {
            return Decision(
                blocked = true,
                kind = Kind.PAYMENT,
                reasonCode = "PAYMENT_PAGE",
            )
        }

        return Decision(blocked = false)
    }

    fun blockedMessage(decision: Decision): String {
        val category = when (decision.kind) {
            Kind.PASSWORD -> "password"
            Kind.OTP -> "one-time-code"
            Kind.PAYMENT -> "payment"
            null -> "sensitive"
        }
        return "AI screen interaction is paused because the current page appears to be a $category page. " +
            "Leave the sensitive page before continuing automation."
    }

    private const val MAX_NODES_TO_SCAN = 512

    private val OTP_KEYWORDS = setOf(
        "one-time password",
        "one time password",
        "one-time code",
        "verification code",
        "security code",
        "auth code",
        "otp",
        "2fa",
        "two-factor",
        "two factor",
        "验证码",
        "短信码",
        "短信验证码",
        "动态码",
        "动态口令",
        "一次性密码",
        "认证码",
        "校验码",
    )

    private val STRONG_PAYMENT_KEYWORDS = setOf(
        "card number",
        "credit card",
        "debit card",
        "cvv",
        "cvc",
        "card security code",
        "expiry date",
        "expiration date",
        "银行卡号",
        "信用卡号",
        "借记卡号",
        "信用卡",
        "安全码",
        "有效期",
        "支付密码",
    )

    private val GENERAL_PAYMENT_KEYWORDS = setOf(
        "checkout",
        "pay now",
        "confirm payment",
        "place order",
        "payment method",
        "支付",
        "付款",
        "确认支付",
        "收银台",
        "支付方式",
        "提交订单",
    )
}
