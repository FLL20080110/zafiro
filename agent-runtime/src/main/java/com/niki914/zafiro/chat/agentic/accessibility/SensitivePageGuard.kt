package com.niki914.zafiro.chat.agentic.accessibility

import android.os.Build
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/**
 * Local-only sensitive page classifier used to pause AI screen interaction before
 * password, OTP, payment, notification OTP, overlay-window, or user-designated
 * sensitive-app UI can be read or acted on.
 *
 * The classifier intentionally keeps no page/notification text and performs no
 * network I/O. Window roots and notification text are evaluated transiently.
 */
object SensitivePageGuard {
    enum class Kind {
        PASSWORD,
        OTP,
        PAYMENT,
        OVERLAY,
        SENSITIVE_APP,
    }

    data class Decision(
        val blocked: Boolean,
        val kind: Kind? = null,
        val reasonCode: String? = null,
    )

    @Volatile
    private var rootProvider: (() -> AccessibilityNodeInfo?)? = null

    @Volatile
    private var windowRootsProvider: (() -> List<AccessibilityNodeInfo>)? = null

    @Volatile
    private var transientOtpBlockedUntilElapsedMs: Long = 0L

    fun installRootProvider(provider: () -> AccessibilityNodeInfo?) {
        rootProvider = provider
    }

    fun installWindowRootsProvider(provider: () -> List<AccessibilityNodeInfo>) {
        windowRootsProvider = provider
    }

    fun clearRootProvider() {
        rootProvider = null
        windowRootsProvider = null
        transientOtpBlockedUntilElapsedMs = 0L
    }

    /**
     * Inspect notification text only long enough to classify it. The text and OTP
     * digits are never retained; only a short-lived local blocked-until timestamp is kept.
     */
    fun recordNotificationText(parts: Iterable<CharSequence?>) {
        val semanticText = parts
            .asSequence()
            .mapNotNull { it?.toString()?.trim()?.takeIf(String::isNotEmpty) }
            .joinToString(" ")
            .lowercase()
        if (semanticText.isEmpty()) return

        val hasStrongOtpSignal = OTP_KEYWORDS.any { it in semanticText } ||
            NOTIFICATION_OTP_PHRASES.any { it in semanticText }
        val hasOtpDigits = OTP_CODE_PATTERN.containsMatchIn(semanticText)
        val hasAuthContext = NOTIFICATION_AUTH_CONTEXT.any { it in semanticText }

        if (hasStrongOtpSignal || (hasOtpDigits && hasAuthContext)) {
            transientOtpBlockedUntilElapsedMs =
                SystemClock.elapsedRealtime() + NOTIFICATION_OTP_BLOCK_MS
        }
    }

    /** Evaluate all currently interactive accessibility windows without retaining them. */
    fun evaluateCurrent(): Decision {
        if (SystemClock.elapsedRealtime() < transientOtpBlockedUntilElapsedMs) {
            return Decision(
                blocked = true,
                kind = Kind.OTP,
                reasonCode = "OTP_NOTIFICATION_TRANSIENT",
            )
        }

        val roots = try {
            windowRootsProvider?.invoke().orEmpty()
        } catch (_: Throwable) {
            emptyList()
        }

        if (roots.isNotEmpty()) {
            for (root in roots.take(MAX_WINDOWS_TO_SCAN)) {
                if (isThirdPartyOverlayWindow(root)) {
                    return Decision(
                        blocked = true,
                        kind = Kind.OVERLAY,
                        reasonCode = "THIRD_PARTY_OVERLAY_WINDOW",
                    )
                }
                val decision = evaluate(root)
                if (decision.blocked) return decision
            }
            return Decision(blocked = false)
        }

        val root = try {
            rootProvider?.invoke()
        } catch (_: Throwable) {
            null
        } ?: return Decision(blocked = false)

        if (isThirdPartyOverlayWindow(root)) {
            return Decision(
                blocked = true,
                kind = Kind.OVERLAY,
                reasonCode = "THIRD_PARTY_OVERLAY_WINDOW",
            )
        }

        return evaluate(root)
    }

    /**
     * AccessibilityWindowInfo does not expose WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY.
     * Third-party application overlays are surfaced through accessibility as system windows on
     * supported Android versions, so classify non-platform TYPE_SYSTEM windows conservatively.
     * Known platform-owned windows are excluded to avoid pausing on SystemUI/permission surfaces.
     */
    private fun isThirdPartyOverlayWindow(root: AccessibilityNodeInfo): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        return try {
            val window = root.window ?: return false
            if (window.type != AccessibilityWindowInfo.TYPE_SYSTEM) return false
            val packageName = root.packageName?.toString()?.lowercase().orEmpty()
            if (packageName.isEmpty()) return false
            !isPlatformWindowPackage(packageName)
        } catch (_: Throwable) {
            false
        }
    }

    private fun isPlatformWindowPackage(packageName: String): Boolean =
        packageName == "android" ||
            packageName.endsWith(".systemui") ||
            packageName.contains("permissioncontroller")

    internal fun evaluate(root: AccessibilityNodeInfo): Decision {
        val foregroundPackage = root.packageName?.toString()
        if (
            SensitiveAppPolicyRegistry.policyFor(foregroundPackage) ==
            SensitiveAppPolicyRegistry.Policy.PAUSE_AI
        ) {
            return Decision(
                blocked = true,
                kind = Kind.SENSITIVE_APP,
                reasonCode = "SENSITIVE_APP_POLICY",
            )
        }

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
            Kind.OVERLAY -> "screen-overlay"
            Kind.SENSITIVE_APP -> "user-protected app"
            null -> "sensitive"
        }
        return "AI screen interaction is paused because the current page appears to be a $category page. " +
            "Leave the sensitive page before continuing automation."
    }

    private const val MAX_NODES_TO_SCAN = 512
    private const val MAX_WINDOWS_TO_SCAN = 12
    private const val NOTIFICATION_OTP_BLOCK_MS = 30_000L

    private val OTP_CODE_PATTERN = Regex("(?<!\\d)\\d{4,8}(?!\\d)")

    private val NOTIFICATION_OTP_PHRASES = setOf(
        "your code",
        "code is",
        "login code",
        "sign-in code",
        "sign in code",
        "passcode",
    )

    private val NOTIFICATION_AUTH_CONTEXT = setOf(
        "login",
        "sign-in",
        "sign in",
        "verify",
        "verification",
        "authenticate",
        "authentication",
        "otp",
        "2fa",
        "验证码",
        "验证",
        "登录",
        "动态码",
        "认证码",
        "校验码",
    )

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
