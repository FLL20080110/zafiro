package com.niki914.zafiro.app.ui.content

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.niki914.uikit.infra.component.settings.SettingsPageSpec
import com.niki914.uikit.infra.component.settings.SettingsRowAction
import com.niki914.uikit.infra.component.settings.SettingsRowSpec
import com.niki914.uikit.infra.component.settings.SettingsSectionLayout
import com.niki914.uikit.infra.component.settings.SettingsSectionSpec
import com.niki914.uikit.infra.component.settings.SettingsSpecPageContent
import com.niki914.zafiro.app.R
import com.niki914.zafiro.app.ui.PageChromeContribution
import com.niki914.zafiro.app.ui.RegisterPageChrome
import com.niki914.zafiro.app.ui.nav.TopBarActionSpec
import com.niki914.zafiro.chat.agentic.shell.SecurityAuditEvent
import com.niki914.zafiro.chat.agentic.shell.SecurityAuditKind
import com.niki914.zafiro.chat.agentic.shell.SecurityAuditLog
import com.niki914.zafiro.chat.agentic.shell.SecurityRiskLevel
import java.text.DateFormat
import java.util.Date

@Composable
fun SecurityAuditSettingsContent() {
    val events by SecurityAuditLog.events.collectAsState()
    val clearLabel = stringResource(R.string.security_audit_clear)
    val pageChromeContribution = remember(clearLabel) {
        PageChromeContribution(
            rightAction = TopBarActionSpec(
                icon = Icons.Default.Delete,
                onClick = SecurityAuditLog::clear,
                contentDescription = clearLabel,
            ),
        )
    }
    RegisterPageChrome(pageChromeContribution)

    SecurityAuditSettingsContentBody(events = events.asReversed())
}

@Composable
private fun SecurityAuditSettingsContentBody(
    events: List<SecurityAuditEvent>,
) {
    val description = stringResource(R.string.security_audit_description)
    val emptyText = stringResource(R.string.security_audit_empty)

    val sections = if (events.isEmpty()) {
        listOf(
            SettingsSectionSpec(
                layout = SettingsSectionLayout.GroupedCard,
                rows = listOf(
                    SettingsRowSpec.Message(
                        title = emptyText,
                        verticalPadding = 12.dp,
                    )
                ),
            )
        )
    } else {
        events.map { event ->
            SettingsSectionSpec(
                title = eventHeader(event),
                layout = SettingsSectionLayout.GroupedCard,
                rows = listOf(
                    SettingsRowSpec.Message(
                        title = eventDetails(event),
                        verticalPadding = 12.dp,
                    )
                ),
            )
        }
    }

    SettingsSpecPageContent(
        spec = SettingsPageSpec(
            description = description,
            sections = sections,
        ),
        onAction = { action ->
            when (action) {
                is SettingsRowAction.Navigate,
                is SettingsRowAction.Click,
                is SettingsRowAction.ToggleChanged -> Unit
            }
        },
    )
}

@Composable
private fun eventHeader(event: SecurityAuditEvent): String {
    val kind = when (event.kind) {
        SecurityAuditKind.PERMISSION_REQUESTED -> stringResource(R.string.security_audit_kind_permission_requested)
        SecurityAuditKind.PERMISSION_ALLOWED -> stringResource(R.string.security_audit_kind_permission_allowed)
        SecurityAuditKind.PERMISSION_DENIED -> stringResource(R.string.security_audit_kind_permission_denied)
        SecurityAuditKind.PERMISSION_UNAVAILABLE -> stringResource(R.string.security_audit_kind_permission_unavailable)
        SecurityAuditKind.TEMPORARY_GRANT_CREATED -> stringResource(R.string.security_audit_kind_temporary_grant_created)
        SecurityAuditKind.TEMPORARY_GRANT_USED -> stringResource(R.string.security_audit_kind_temporary_grant_used)
        SecurityAuditKind.POLICY_BLOCKED -> stringResource(R.string.security_audit_kind_policy_blocked)
        SecurityAuditKind.PRIVACY_BLOCKED -> stringResource(R.string.security_audit_kind_privacy_blocked)
        SecurityAuditKind.SENSITIVE_CONTEXT_BLOCKED -> stringResource(R.string.security_audit_kind_sensitive_context_blocked)
        SecurityAuditKind.TEMPORARY_GRANTS_CLEARED -> stringResource(R.string.security_audit_kind_temporary_grants_cleared)
    }
    val risk = riskLabel(event.riskLevel)
    val time = remember(event.timestampMs) {
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM)
            .format(Date(event.timestampMs))
    }
    return "$kind · $risk · $time"
}

@Composable
private fun eventDetails(event: SecurityAuditEvent): String {
    val details = buildList {
        event.toolName?.let { add(stringResource(R.string.security_audit_event_tool, it)) }
        event.ruleName?.let { add(stringResource(R.string.security_audit_event_rule, it)) }
        event.policyCode?.let { add(stringResource(R.string.security_audit_event_policy, it)) }
        event.reason?.let { add(stringResource(R.string.security_audit_event_reason, it)) }
        event.commandPreview?.let { add(stringResource(R.string.security_audit_event_command, it)) }
        add(stringResource(R.string.security_audit_risk, riskLabel(event.riskLevel)))
    }
    return details.joinToString(separator = "\n")
}

@Composable
private fun riskLabel(level: SecurityRiskLevel): String = when (level) {
    SecurityRiskLevel.INFO -> stringResource(R.string.security_audit_risk_info)
    SecurityRiskLevel.LOW -> stringResource(R.string.security_audit_risk_low)
    SecurityRiskLevel.MEDIUM -> stringResource(R.string.security_audit_risk_medium)
    SecurityRiskLevel.HIGH -> stringResource(R.string.security_audit_risk_high)
    SecurityRiskLevel.CRITICAL -> stringResource(R.string.security_audit_risk_critical)
}
