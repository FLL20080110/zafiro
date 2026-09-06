package com.niki914.zafiro.app.ui.content

import android.content.Intent
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.app.NotificationManagerCompat
import com.niki914.logging.Logger
import com.niki914.uikit.infra.component.settings.SettingsPageSpec
import com.niki914.uikit.infra.component.settings.SettingsRowAction
import com.niki914.uikit.infra.component.settings.SettingsRowSpec
import com.niki914.uikit.infra.component.settings.SettingsSectionLayout
import com.niki914.uikit.infra.component.settings.SettingsSectionSpec
import com.niki914.uikit.infra.component.settings.SettingsSpecPageContent
import com.niki914.zafiro.app.R
import com.niki914.zafiro.message.MessageAssistantCoordinator
import com.niki914.zafiro.message.RecentConversationRegistry
import com.niki914.zafiro.repo.MessageAssistantSettings
import kotlinx.coroutines.launch

private const val MODE_ROW_ID = "message-assistant.mode"
private const val NOTIFICATION_ACCESS_ROW_ID = "message-assistant.notification-access"
private const val ACCESSIBILITY_FALLBACK_ROW_ID = "message-assistant.accessibility-fallback"
private const val PACKAGE_ROW_PREFIX = "message-assistant.package:"
private const val TRUSTED_ROW_PREFIX = "message-assistant.trusted:"
private const val LOG_TAG = "niki914_nexus_MessageAssistant"

private val CHAT_PACKAGES = listOf(
    "com.tencent.mm" to "WeChat",
    "com.tencent.mobileqq" to "QQ",
    "com.tencent.tim" to "TIM",
)

@Composable
fun MessageAssistantSettingsContent() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var snapshot by remember { mutableStateOf<MessageAssistantSettings.Snapshot?>(null) }
    var showModeDialog by remember { mutableStateOf(false) }
    var notificationAccess by remember { mutableStateOf(false) }
    val recentConversations by RecentConversationRegistry.entries.collectAsState()
    val latestSuggestion by MessageAssistantCoordinator.latestSuggestion.collectAsState()

    fun refreshNotificationAccess() {
        notificationAccess = NotificationManagerCompat
            .getEnabledListenerPackages(context)
            .contains(context.packageName)
    }

    LaunchedEffect(Unit) {
        refreshNotificationAccess()
        snapshot = runCatching { MessageAssistantSettings.snapshot() }
            .onFailure { Logger.w(LOG_TAG, "load failed ${it.message}") }
            .getOrNull()
    }

    val current = snapshot
    val modeLabel = when (current?.mode ?: MessageAssistantSettings.Mode.OFF) {
        MessageAssistantSettings.Mode.OFF -> stringResource(R.string.message_assistant_mode_off)
        MessageAssistantSettings.Mode.SUGGEST -> stringResource(R.string.message_assistant_mode_suggest)
        MessageAssistantSettings.Mode.AUTO_REPLY -> stringResource(R.string.message_assistant_mode_auto)
    }

    val trustedRows = if (recentConversations.isEmpty()) {
        listOf(SettingsRowSpec.Message(title = stringResource(R.string.message_assistant_recent_empty)))
    } else {
        recentConversations.map { entry ->
            val appLabel = CHAT_PACKAGES.firstOrNull { it.first == entry.packageName }?.second ?: entry.packageName
            SettingsRowSpec.Toggle(
                id = TRUSTED_ROW_PREFIX + entry.conversationKey,
                title = "$appLabel · ${entry.conversation}",
                summary = stringResource(R.string.message_assistant_recent_summary),
                checked = entry.conversationKey in (current?.trustedConversations ?: emptySet()),
            )
        }
    }

    val suggestionSections = latestSuggestion?.let { suggestion ->
        listOf(
            SettingsSectionSpec(
                title = stringResource(R.string.message_assistant_latest_suggestion_title),
                layout = SettingsSectionLayout.GroupedCard,
                rows = listOf(
                    SettingsRowSpec.Message(title = suggestion.conversation),
                    SettingsRowSpec.Message(title = suggestion.text),
                    SettingsRowSpec.Message(
                        title = if (suggestion.autoSent) {
                            stringResource(R.string.message_assistant_latest_suggestion_auto_sent)
                        } else {
                            stringResource(R.string.message_assistant_latest_suggestion_not_sent)
                        },
                    ),
                ),
            )
        )
    }.orEmpty()

    SettingsSpecPageContent(
        spec = SettingsPageSpec(
            description = stringResource(R.string.message_assistant_description),
            sections = listOf(
                SettingsSectionSpec(
                    layout = SettingsSectionLayout.GroupedCard,
                    rows = listOf(
                        SettingsRowSpec.Navigation(
                            id = NOTIFICATION_ACCESS_ROW_ID,
                            title = stringResource(R.string.message_assistant_notification_access),
                            currentState = if (notificationAccess) {
                                stringResource(R.string.message_assistant_access_granted)
                            } else {
                                stringResource(R.string.message_assistant_access_required)
                            },
                        ),
                        SettingsRowSpec.Navigation(
                            id = MODE_ROW_ID,
                            title = stringResource(R.string.message_assistant_mode),
                            summary = stringResource(R.string.message_assistant_mode_summary),
                            currentState = modeLabel,
                        ),
                        SettingsRowSpec.Toggle(
                            id = ACCESSIBILITY_FALLBACK_ROW_ID,
                            title = stringResource(R.string.message_assistant_accessibility_fallback),
                            summary = stringResource(R.string.message_assistant_accessibility_fallback_summary),
                            checked = current?.accessibilityFallbackEnabled ?: false,
                        ),
                    ),
                ),
                SettingsSectionSpec(
                    layout = SettingsSectionLayout.GroupedCard,
                    rows = CHAT_PACKAGES.map { (packageName, label) ->
                        SettingsRowSpec.Toggle(
                            id = PACKAGE_ROW_PREFIX + packageName,
                            title = label,
                            summary = packageName,
                            checked = packageName in (current?.enabledPackages ?: emptySet()),
                        )
                    },
                ),
                SettingsSectionSpec(
                    layout = SettingsSectionLayout.GroupedCard,
                    rows = listOf(
                        SettingsRowSpec.Message(title = stringResource(R.string.message_assistant_recent_title)),
                    ) + trustedRows,
                ),
            ) + suggestionSections + listOf(
                SettingsSectionSpec(
                    layout = SettingsSectionLayout.GroupedCard,
                    rows = listOf(
                        SettingsRowSpec.Message(
                            title = stringResource(
                                R.string.message_assistant_trusted_count,
                                current?.trustedConversations?.size ?: 0,
                            ),
                        ),
                        SettingsRowSpec.Message(title = stringResource(R.string.message_assistant_safety_note)),
                    ),
                ),
            ),
        ),
        onAction = { action ->
            when (action) {
                is SettingsRowAction.Navigate -> when (action.id) {
                    NOTIFICATION_ACCESS_ROW_ID -> {
                        runCatching {
                            context.startActivity(
                                Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }.onFailure { Logger.w(LOG_TAG, "open notification access failed ${it.message}") }
                    }
                    MODE_ROW_ID -> showModeDialog = true
                }

                is SettingsRowAction.ToggleChanged -> when {
                    action.id == ACCESSIBILITY_FALLBACK_ROW_ID -> {
                        val before = snapshot
                        snapshot = before?.copy(accessibilityFallbackEnabled = action.checked)
                        scope.launch {
                            runCatching { MessageAssistantSettings.setAccessibilityFallbackEnabled(action.checked) }
                                .onSuccess { snapshot = it }
                                .onFailure {
                                    Logger.w(LOG_TAG, "accessibility fallback update failed ${it.message}")
                                    snapshot = runCatching { MessageAssistantSettings.snapshot() }
                                        .getOrNull() ?: before
                                }
                        }
                    }

                    action.id.startsWith(PACKAGE_ROW_PREFIX) -> {
                        val packageName = action.id.removePrefix(PACKAGE_ROW_PREFIX)
                        val before = snapshot
                        snapshot = before?.copy(
                            enabledPackages = if (action.checked) before.enabledPackages + packageName
                            else before.enabledPackages - packageName
                        )
                        scope.launch {
                            runCatching { MessageAssistantSettings.setPackageEnabled(packageName, action.checked) }
                                .onSuccess { snapshot = it }
                                .onFailure {
                                    Logger.w(LOG_TAG, "package policy update failed ${it.message}")
                                    snapshot = runCatching { MessageAssistantSettings.snapshot() }
                                        .getOrNull() ?: before
                                }
                        }
                    }

                    action.id.startsWith(TRUSTED_ROW_PREFIX) -> {
                        val conversationKey = action.id.removePrefix(TRUSTED_ROW_PREFIX)
                        val before = snapshot
                        snapshot = before?.copy(
                            trustedConversations = if (action.checked) before.trustedConversations + conversationKey
                            else before.trustedConversations - conversationKey
                        )
                        scope.launch {
                            runCatching {
                                MessageAssistantSettings.setTrustedConversation(conversationKey, action.checked)
                            }.onSuccess { snapshot = it }
                                .onFailure {
                                    Logger.w(LOG_TAG, "trusted chat update failed ${it.message}")
                                    snapshot = runCatching { MessageAssistantSettings.snapshot() }
                                        .getOrNull() ?: before
                                }
                        }
                    }
                }

                is SettingsRowAction.Click -> Unit
            }
        },
    )

    data class ModeOption(val mode: MessageAssistantSettings.Mode, val label: String)
    val modeOptions = listOf(
        ModeOption(MessageAssistantSettings.Mode.OFF, stringResource(R.string.message_assistant_mode_off)),
        ModeOption(MessageAssistantSettings.Mode.SUGGEST, stringResource(R.string.message_assistant_mode_suggest)),
        ModeOption(MessageAssistantSettings.Mode.AUTO_REPLY, stringResource(R.string.message_assistant_mode_auto)),
    )
    SingleChoiceLiquidDialog(
        visible = showModeDialog,
        onDismissRequest = { showModeDialog = false },
        title = stringResource(R.string.message_assistant_mode),
        hint = stringResource(R.string.message_assistant_auto_warning),
        options = modeOptions,
        selectedId = (current?.mode ?: MessageAssistantSettings.Mode.OFF).wireValue,
        optionId = { it.mode.wireValue },
        optionLabel = { it.label },
        onSelect = { option ->
            showModeDialog = false
            val before = snapshot
            snapshot = before?.copy(mode = option.mode)
            scope.launch {
                runCatching { MessageAssistantSettings.setMode(option.mode) }
                    .onSuccess { snapshot = it }
                    .onFailure {
                        Logger.w(LOG_TAG, "mode update failed ${it.message}")
                        snapshot = runCatching { MessageAssistantSettings.snapshot() }
                            .getOrNull() ?: before
                    }
            }
        },
    )
}
