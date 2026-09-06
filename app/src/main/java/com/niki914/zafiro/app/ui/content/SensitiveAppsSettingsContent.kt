package com.niki914.zafiro.app.ui.content

import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.niki914.logging.Logger
import com.niki914.uikit.infra.component.settings.SettingsPageSpec
import com.niki914.uikit.infra.component.settings.SettingsRowAction
import com.niki914.uikit.infra.component.settings.SettingsRowSpec
import com.niki914.uikit.infra.component.settings.SettingsSectionLayout
import com.niki914.uikit.infra.component.settings.SettingsSectionSpec
import com.niki914.uikit.infra.component.settings.SettingsSpecPageContent
import com.niki914.zafiro.app.R
import com.niki914.zafiro.repo.SensitiveAppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val ROW_PREFIX = "sensitive-app:"
private const val LOG_TAG = "niki914_nexus_SensitiveApps"

private data class LaunchableApp(
    val packageName: String,
    val label: String,
)

@Composable
fun SensitiveAppsSettingsContent() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var apps by remember { mutableStateOf<List<LaunchableApp>>(emptyList()) }
    var pausedPackages by remember { mutableStateOf<Set<String>>(emptySet()) }
    var loaded by remember { mutableStateOf(false) }
    // Only the newest optimistic mutation may update visible state when its async save finishes.
    // Durable/runtime policy mutations are separately serialized by SensitiveAppSettings.
    var policyMutationVersion by remember { mutableStateOf(0L) }

    LaunchedEffect(Unit) {
        val loadedPackages = runCatching { SensitiveAppSettings.packages() }
            .getOrElse {
                Logger.w(LOG_TAG, "load policy failed ${it.message}")
                emptySet()
            }
        val launchableApps = withContext(Dispatchers.IO) {
            runCatching {
                val pm = context.packageManager
                val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                val visibleApps = pm.queryIntentActivities(intent, PackageManager.MATCH_DISABLED_COMPONENTS)
                    .asSequence()
                    .mapNotNull { info ->
                        val packageName = info.activityInfo?.packageName.orEmpty()
                        if (packageName.isBlank() || packageName == context.packageName) {
                            null
                        } else {
                            LaunchableApp(
                                packageName = packageName,
                                label = info.loadLabel(pm)?.toString().orEmpty().ifBlank { packageName },
                            )
                        }
                    }
                    .distinctBy(LaunchableApp::packageName)
                    .toList()

                // Keep persisted policies manageable even when a protected package is
                // no longer launchable, temporarily disabled, or no longer visible to
                // PackageManager. Showing the saved package name lets the user remove
                // stale protection without requesting QUERY_ALL_PACKAGES.
                val visiblePackages = visibleApps.asSequence()
                    .map(LaunchableApp::packageName)
                    .toSet()
                val savedOnlyApps = loadedPackages
                    .asSequence()
                    .filter { it.isNotBlank() && it != context.packageName && it !in visiblePackages }
                    .map { packageName ->
                        LaunchableApp(
                            packageName = packageName,
                            label = packageName,
                        )
                    }
                    .toList()

                (visibleApps + savedOnlyApps)
                    .sortedWith(
                        compareBy<LaunchableApp> { it.label.lowercase() }
                            .thenBy(LaunchableApp::packageName)
                    )
            }.getOrElse {
                Logger.w(LOG_TAG, "load apps failed ${it.message}")
                emptyList()
            }
        }
        pausedPackages = loadedPackages
        apps = launchableApps
        loaded = true
    }

    val rows = when {
        !loaded -> listOf(
            SettingsRowSpec.Message(
                title = stringResource(R.string.sensitive_apps_loading),
                verticalPadding = 12.dp,
            )
        )
        apps.isEmpty() -> listOf(
            SettingsRowSpec.Message(
                title = stringResource(R.string.sensitive_apps_empty),
                verticalPadding = 12.dp,
            )
        )
        else -> apps.map { app ->
            SettingsRowSpec.Toggle(
                id = ROW_PREFIX + app.packageName,
                title = app.label,
                checked = app.packageName in pausedPackages,
            )
        }
    }

    SettingsSpecPageContent(
        spec = SettingsPageSpec(
            description = stringResource(R.string.sensitive_apps_description),
            sections = listOf(
                SettingsSectionSpec(
                    layout = SettingsSectionLayout.GroupedCard,
                    rows = rows,
                )
            ),
        ),
        onAction = { action ->
            when (action) {
                is SettingsRowAction.ToggleChanged -> {
                    val packageName = action.id.removePrefix(ROW_PREFIX)
                    if (action.id.startsWith(ROW_PREFIX) && packageName.isNotBlank()) {
                        val before = pausedPackages
                        pausedPackages = if (action.checked) {
                            before + packageName
                        } else {
                            before - packageName
                        }
                        policyMutationVersion += 1L
                        val mutationVersion = policyMutationVersion
                        scope.launch {
                            runCatching {
                                SensitiveAppSettings.setPaused(packageName, action.checked)
                            }.onSuccess { saved ->
                                if (mutationVersion == policyMutationVersion) {
                                    pausedPackages = saved
                                }
                            }.onFailure {
                                Logger.w(LOG_TAG, "save policy failed ${it.message}")
                                val restored = runCatching { SensitiveAppSettings.packages() }
                                    .getOrDefault(before)
                                if (mutationVersion == policyMutationVersion) {
                                    pausedPackages = restored
                                }
                            }
                        }
                    }
                }
                is SettingsRowAction.Navigate,
                is SettingsRowAction.Click -> Unit
            }
        },
    )
}
