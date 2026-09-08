package com.niki914.zafiro.message

import android.app.Notification
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Notification-based incoming message recognizer for supported chat apps.
 *
 * This service only normalizes inbound notification content and publishes it to an in-process
 * stream. It does not generate replies, send messages, persist message bodies, or contact a model.
 */
class IncomingMessageNotificationService : NotificationListenerService() {

    override fun onListenerConnected() {
        // Capabilities belong to the currently connected listener instance only. Do not let a
        // reconnect resurrect handles captured before Android re-bound the notification service.
        IncomingMessageReplyRegistry.clear()
        super.onListenerConnected()
    }

    override fun onListenerDisconnected() {
        // Pending notification reply handles are ephemeral capabilities. Once listener state is no
        // longer authoritative, fail closed instead of retaining handles until their normal TTL.
        IncomingMessageReplyRegistry.clear()
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        IncomingMessageReplyRegistry.clear()
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val statusBarNotification = sbn ?: return
        val packageName = statusBarNotification.packageName.orEmpty()
        if (packageName !in SUPPORTED_CHAT_PACKAGES) return

        // A replacement notification with the same system key invalidates any older direct-reply
        // capability before we inspect the new payload. If the replacement no longer carries a
        // usable reply action, stale handles remain revoked rather than silently surviving.
        IncomingMessageReplyRegistry.revokeForNotification(statusBarNotification.key)

        val notification = statusBarNotification.notification
        if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return

        val extras = notification.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty().trim()
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString().orEmpty().trim()
        val conversationTitle = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString().orEmpty().trim()
        } else {
            ""
        }

        val messagingMessage = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            Notification.MessagingStyle.Message
                .getMessagesFromBundleArray(extras.getParcelableArray(Notification.EXTRA_MESSAGES))
                .lastOrNull()
        } else {
            null
        }

        val text = messagingMessage?.text?.toString()?.trim()
            ?.takeIf(String::isNotBlank)
            ?: extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        if (text.isBlank()) return

        val sender = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            messagingMessage?.senderPerson?.name?.toString()?.trim().orEmpty()
        } else {
            @Suppress("DEPRECATION")
            messagingMessage?.sender?.toString()?.trim().orEmpty()
        }.ifBlank { title }

        val conversation = conversationTitle
            .ifBlank { subText }
            .ifBlank { title }
            .ifBlank { sender }

        val replyAction = notification.actions.orEmpty().firstOrNull(::hasFreeFormReply)
        val replyHandleId = replyAction?.let { action ->
            IncomingMessageReplyRegistry.register(action, statusBarNotification.key)
        }

        val message = IncomingChatMessage(
            packageName = packageName,
            sender = sender,
            conversation = conversation,
            text = text,
            postedAtMs = statusBarNotification.postTime,
            systemReplyAvailable = replyHandleId != null,
            sensitive = isSensitiveMessageContent(text),
            replyHandleId = replyHandleId,
        )

        // Keep only non-body conversation metadata in memory so the settings UI can offer an
        // explicit trust toggle without requiring users to type fragile internal keys.
        RecentConversationRegistry.observe(message)
        IncomingMessageBus.publish(message)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        val statusBarNotification = sbn ?: return
        if (statusBarNotification.packageName.orEmpty() !in SUPPORTED_CHAT_PACKAGES) return
        IncomingMessageReplyRegistry.revokeForNotification(statusBarNotification.key)
    }

    private fun hasFreeFormReply(action: Notification.Action): Boolean {
        return action.remoteInputs.orEmpty().any { input ->
            input.allowFreeFormInput && input.resultKey.isNotBlank()
        }
    }

    private companion object {
        val SUPPORTED_CHAT_PACKAGES = setOf(
            "com.tencent.mm",
            "com.tencent.mobileqq",
            "com.tencent.tim",
        )
    }
}

/**
 * Conservative local-only classifier used before any message body is handed to a model.
 * False positives are preferable to allowing automated replies around credentials or money.
 */
internal fun isSensitiveMessageContent(text: String): Boolean {
    val normalized = text.lowercase()
    if (SENSITIVE_MESSAGE_KEYWORDS.any(normalized::contains)) return true

    // Common verification/OTP formats.
    if (MESSAGE_OTP_PATTERN.containsMatchIn(normalized)) return true

    // Long numeric identifiers are commonly bank cards/account numbers. This intentionally starts
    // at 12 digits so ordinary phone numbers are not blocked solely for being numeric.
    return MESSAGE_LONG_NUMBER_PATTERN.containsMatchIn(normalized)
}

private val SENSITIVE_MESSAGE_KEYWORDS = listOf(
    "验证码",
    "校验码",
    "动态码",
    "支付密码",
    "登录密码",
    "交易密码",
    "银行卡",
    "卡号",
    "账户",
    "账号",
    "转账",
    "汇款",
    "付款",
    "收款",
    "收款码",
    "付款码",
    "支付",
    "身份证",
    "密钥",
    "口令",
    "otp",
    "verification code",
    "one-time password",
    "password",
    "passcode",
    "pin code",
    "api key",
    "access token",
    "refresh token",
    "secret key",
)

private val MESSAGE_OTP_PATTERN = Regex("(?:^|\\D)\\d{4,8}(?:\\D|$)")
private val MESSAGE_LONG_NUMBER_PATTERN = Regex("(?:^|\\D)\\d{12,19}(?:\\D|$)")
