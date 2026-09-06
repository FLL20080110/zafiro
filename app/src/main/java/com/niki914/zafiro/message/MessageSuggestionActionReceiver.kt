package com.niki914.zafiro.message

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.niki914.logging.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Handles explicit one-time send actions from Zafiro suggestion notifications. */
class MessageSuggestionActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_SEND_SUGGESTION) return
        val suggestionId = intent.getStringExtra(EXTRA_SUGGESTION_ID)
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?: return

        val pendingResult = goAsync()
        receiverScope.launch {
            try {
                MessageAssistantCoordinator.sendSuggestion(context.applicationContext, suggestionId)
                    .onFailure {
                        Logger.w(LOG_TAG, "manual suggestion send failed ${it.message}")
                    }
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val ACTION_SEND_SUGGESTION =
            "com.niki914.zafiro.message.action.SEND_SUGGESTION"
        const val EXTRA_SUGGESTION_ID = "suggestion_id"

        private const val LOG_TAG = "niki914_nexus_MessageSuggestionAction"
        private val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
