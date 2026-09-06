package com.niki914.zafiro.message

import android.app.Notification
import android.app.RemoteInput
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

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val notification = sbn?.notification ?: return
        val packageName = sbn.packageName.orEmpty()
        if (packageName !in SUPPORTED_CHAT_PACKAGES) return
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
        val replyHandleId = replyAction?.let(IncomingMessageReplyRegistry::register)

        val message = IncomingChatMessage(
            packageName = packageName,
            sender = sender,
            conversation = conversation,
            text = text,
            postedAtMs = sbn.postTime,
            systemReplyAvailable = replyHandleId != null,
            sensitive = isSensitiveMessage(text),
            replyHandleId = replyHandleId,
        )

        // Keep only non-body conversation metadata in memory so the settings UI can offer an
        // explicit trust toggle without requiring users to type fragile internal keys.
        RecentConversationRegistry.observe(message)
        IncomingMessageBus.publish(message)
    }

    private fun hasFreeFormReply(action: Notification.Action): Boolean {
        return action.remoteInputs.orEmpty().any { input ->
            input.allowFreeFormInput && input.resultKey.isNotBlank()
        }
    }

    /**
     * Conservative local-only sensitive classifier for chat auto-reply safety.
     * False positives are acceptable here because this flag only blocks automation.
     */
    private fun isSensitiveMessage(text: String): Boolean {
        val normalized = text.lowercase()
        if (SENSITIVE_KEYWORDS.any(normalized::contains)) return true

        // Common 4-8 digit verification/OTP formats. Keep this deterministic and local.
        return OTP_PATTERN.containsMatchIn(normalized)
    }

    private companion object {
        val SUPPORTED_CHAT_PACKAGES = setOf(
            "com.tencent.mm",
            "com.tencent.mobileqq",
            "com.tencent.tim",
        )

        val SENSITIVE_KEYWORDS = listOf(
            "验证码",
            "校验码",
            "动态码",
            "支付密码",
            "登录密码",
            "交易密码",
            "银行卡",
            "收款码",
            "付款码",
            "otp",
            "verification code",
            "one-time password",
            "password",
        )

        val OTP_PATTERN = Regex("(?:^|\\D)\\d{4,8}(?:\\D|$)")
    }
}
