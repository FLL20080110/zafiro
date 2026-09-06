package com.niki914.zafiro.message

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.niki914.logging.Logger
import com.niki914.zafiro.app.MainActivity

/**
 * User-visible surface for generated chat suggestions.
 *
 * The original incoming message body is intentionally never placed in the notification. Only the
 * generated suggestion and the conversation label are shown. The one-time send action carries only
 * an opaque suggestion id; reply text, sender and source message are never copied into Intent extras.
 */
object MessageAssistantSuggestionNotifier {
    private const val LOG_TAG = "niki914_nexus_MessageSuggestion"
    private const val CHANNEL_ID = "message_assistant_suggestions"
    private const val CHANNEL_NAME = "消息助手建议"

    fun show(context: Context, suggestion: MessageAssistantCoordinator.Suggestion) {
        val appContext = context.applicationContext
        if (!canPostNotifications(appContext)) {
            Logger.i(LOG_TAG, "suggestion notification skipped permission=false")
            return
        }

        ensureChannel(appContext)
        val launchIntent = Intent(appContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val contentIntent = PendingIntent.getActivity(
            appContext,
            suggestion.conversation.hashCode(),
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val title = if (suggestion.autoSent) {
            "已自动回复 · ${suggestion.conversation}"
        } else {
            "建议回复 · ${suggestion.conversation}"
        }
        val builder = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(resolveSmallIcon(appContext))
            .setContentTitle(title)
            .setContentText(suggestion.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(suggestion.text))
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)

        if (suggestion.manualSendAvailable) {
            val sendIntent = Intent(appContext, MessageSuggestionActionReceiver::class.java)
                .setAction(MessageSuggestionActionReceiver.ACTION_SEND_SUGGESTION)
                .putExtra(MessageSuggestionActionReceiver.EXTRA_SUGGESTION_ID, suggestion.id)
            val sendPendingIntent = PendingIntent.getBroadcast(
                appContext,
                suggestion.id.hashCode(),
                sendIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.addAction(
                android.R.drawable.ic_menu_send,
                "发送建议",
                sendPendingIntent,
            )
        }

        runCatching {
            NotificationManagerCompat.from(appContext).notify(
                notificationId(suggestion.packageName, suggestion.conversation),
                builder.build(),
            )
        }.onFailure {
            Logger.w(LOG_TAG, "show suggestion failed ${it.message}")
        }
    }

    fun dismiss(context: Context, packageName: String, conversation: String) {
        NotificationManagerCompat.from(context.applicationContext).cancel(
            notificationId(packageName, conversation),
        )
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_DEFAULT,
            )
        )
    }

    private fun canPostNotifications(context: Context): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
    }

    private fun resolveSmallIcon(context: Context): Int {
        return context.applicationInfo.icon.takeIf { it != 0 }
            ?: android.R.drawable.ic_dialog_info
    }

    internal fun notificationId(packageName: String, conversation: String): Int {
        var result = packageName.hashCode()
        result = 31 * result + conversation.hashCode()
        return result
    }
}
