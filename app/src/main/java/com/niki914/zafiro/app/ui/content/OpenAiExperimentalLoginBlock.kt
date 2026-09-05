package com.niki914.zafiro.app.ui.content

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.niki914.uikit.infra.component.SettingsGroupCard
import com.niki914.uikit.infra.component.SettingsListItem
import com.niki914.zafiro.openai.auth.OpenAiAuthHolder
import com.niki914.zafiro.openai.auth.OpenAiDeviceCodeSession
import com.niki914.zafiro.openai.auth.OpenAiLoginResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Experimental ChatGPT/Codex account login entry shown only for the OpenAI
 * provider. API-key configuration remains available as a stable fallback.
 */
@Composable
internal fun OpenAiExperimentalLoginBlock(
    isManagedOAuth: Boolean,
    onManagedOAuthChanged: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = remember { OpenAiAuthHolder.requireRepository() }

    var account by remember { mutableStateOf(repository.currentAccount()) }
    var session by remember { mutableStateOf<OpenAiDeviceCodeSession?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var starting by remember { mutableStateOf(false) }
    var showAccountDialog by remember { mutableStateOf(false) }

    fun startLogin() {
        if (starting) return
        starting = true
        errorMessage = null
        scope.launch {
            runCatching { repository.startLogin() }
                .onSuccess { session = it }
                .onFailure { errorMessage = it.message ?: "无法启动 OpenAI 登录" }
            starting = false
        }
    }

    SettingsGroupCard {
        SettingsListItem(
            title = "ChatGPT / Codex 登录（实验性）",
            currentState = when {
                starting -> "正在获取验证码…"
                account != null && isManagedOAuth -> "已启用 · ${account?.email ?: "已登录"}"
                account != null -> "已登录 · ${account?.email ?: "点击启用"}"
                else -> "未登录"
            },
            showChevron = true,
            onClick = {
                if (account == null) startLogin() else showAccountDialog = true
            },
        )
    }

    LaunchedEffect(session?.deviceAuthId) {
        val active = session ?: return@LaunchedEffect
        while (session?.deviceAuthId == active.deviceAuthId) {
            if (System.currentTimeMillis() >= active.expiresAtEpochMs) {
                errorMessage = "验证码已过期，请重新登录"
                session = null
                break
            }
            delay((active.intervalSeconds + POLLING_SAFETY_MARGIN_SECONDS) * 1_000L)
            when (val result = repository.pollLogin(active)) {
                OpenAiLoginResult.Pending -> Unit
                OpenAiLoginResult.AccessDenied -> {
                    errorMessage = "你已拒绝授权"
                    session = null
                }
                OpenAiLoginResult.Expired -> {
                    errorMessage = "验证码已过期，请重新登录"
                    session = null
                }
                is OpenAiLoginResult.Failed -> {
                    errorMessage = result.message
                    session = null
                }
                is OpenAiLoginResult.Success -> {
                    account = result.account
                    onManagedOAuthChanged(true)
                    errorMessage = null
                    session = null
                }
            }
        }
    }

    session?.let { active ->
        AlertDialog(
            onDismissRequest = { session = null },
            title = { Text("登录 ChatGPT") },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("在 OpenAI 授权页面输入下面的设备代码：")
                    Text(
                        text = active.userCode,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                    )
                    Text(
                        text = active.verificationUri,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        text = "授权后会自动检测登录并将当前 OpenAI 配置切换到 ChatGPT/Codex 模式。此方式依赖实验性的 Codex 设备认证接口，可能随上游变化失效。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    errorMessage?.let {
                        Text(
                            text = it,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(active.verificationUri))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    },
                ) {
                    Text("打开 OpenAI 授权页面")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
                                as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("OpenAI device code", active.userCode))
                    },
                ) {
                    Text("复制代码")
                }
            },
        )
    }

    if (errorMessage != null && session == null && account == null) {
        AlertDialog(
            onDismissRequest = { errorMessage = null },
            title = { Text("OpenAI 登录失败") },
            text = { Text(errorMessage.orEmpty()) },
            confirmButton = {
                TextButton(onClick = { errorMessage = null; startLogin() }) {
                    Text("重试")
                }
            },
            dismissButton = {
                TextButton(onClick = { errorMessage = null }) { Text("关闭") }
            },
        )
    }

    if (showAccountDialog) {
        AlertDialog(
            onDismissRequest = { showAccountDialog = false },
            title = { Text("OpenAI 已连接") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(account?.email ?: "ChatGPT 账号")
                    account?.chatgptAccountId?.let { Text("Account: $it") }
                    Text(
                        "Refresh Token 已使用 Android Keystore 加密保存；短期 Access Token 仅保留在内存中。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = {
                if (isManagedOAuth) {
                    TextButton(
                        onClick = {
                            onManagedOAuthChanged(false)
                            repository.logout()
                            account = null
                            showAccountDialog = false
                        },
                    ) { Text("退出登录") }
                } else {
                    TextButton(
                        onClick = {
                            onManagedOAuthChanged(true)
                            showAccountDialog = false
                        },
                    ) { Text("使用此账号") }
                }
            },
            dismissButton = {
                TextButton(onClick = { showAccountDialog = false }) { Text("关闭") }
            },
        )
    }
}

private const val POLLING_SAFETY_MARGIN_SECONDS = 3L
