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

        IncomingMessageBus.publish(
            IncomingChatMessage(
                packageName = packageName,
                sender = sender,
                conversation = conversation,
                text = text,
                postedAtMs = sbn.postTime,
            )
        )
    }

    private companion object {
        val SUPPORTED_CHAT_PACKAGES = setOf(
            "com.tencent.mm",
            "com.tencent.mobileqq",
            "com.tencent.tim",
        )
    }
}
