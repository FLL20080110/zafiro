package com.niki914.zafiro.repo

import android.content.Context
import android.util.AtomicFile
import com.niki914.logging.Logger
import com.niki914.zafiro.chat.agentic.shell.SecurityAuditEvent
import com.niki914.zafiro.chat.agentic.shell.SecurityAuditKind
import com.niki914.zafiro.chat.agentic.shell.SecurityAuditLog
import com.niki914.zafiro.chat.agentic.shell.SecurityRiskLevel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * App-private durable storage for the minimized security audit snapshot.
 *
 * The file lives under noBackupFilesDir, is atomically replaced, is capped to the same
 * 200 events as the in-memory log, and stores only the already-minimized audit fields.
 * Raw passwords, OTPs, page text, screenshots, tokens and command output are never added here.
 */
object SecurityAuditPersistence {
    private const val LOG_TAG = "niki914_nexus_AuditPersistence"
    private const val FILE_NAME = "security_audit_v1.json"
    private const val MAX_TEXT_CHARS = 160

    fun start(scope: CoroutineScope, context: Context) {
        val appContext = context.applicationContext
        scope.launch {
            val atomicFile = AtomicFile(File(appContext.noBackupFilesDir, FILE_NAME))
            val restored = runCatching { readSnapshot(atomicFile) }
                .onFailure { Logger.w(LOG_TAG, "restore failed ${it.message}") }
                .getOrDefault(emptyList())
            if (restored.isNotEmpty()) {
                SecurityAuditLog.restorePersisted(restored)
            }

            SecurityAuditLog.events.collectLatest { events ->
                runCatching {
                    withContext(Dispatchers.IO) {
                        writeSnapshot(atomicFile, events.takeLast(SecurityAuditLog.MAX_EVENTS))
                    }
                }.onFailure {
                    Logger.w(LOG_TAG, "persist failed ${it.message}")
                }
            }
        }
    }

    private fun readSnapshot(file: AtomicFile): List<SecurityAuditEvent> {
        if (!file.baseFile.exists()) return emptyList()
        val text = file.openRead().bufferedReader(Charsets.UTF_8).use { it.readText() }
        if (text.isBlank()) return emptyList()
        val array = JSONArray(text)
        val start = (array.length() - SecurityAuditLog.MAX_EVENTS).coerceAtLeast(0)
        return buildList {
            for (index in start until array.length()) {
                decodeEvent(array.optJSONObject(index))?.let(::add)
            }
        }
    }

    private fun writeSnapshot(file: AtomicFile, events: List<SecurityAuditEvent>) {
        val array = JSONArray()
        events.forEach { event -> array.put(encodeEvent(event)) }
        val bytes = array.toString().toByteArray(Charsets.UTF_8)
        val stream = file.startWrite()
        try {
            stream.write(bytes)
            stream.flush()
            file.finishWrite(stream)
        } catch (throwable: Throwable) {
            file.failWrite(stream)
            throw throwable
        }
    }

    private fun encodeEvent(event: SecurityAuditEvent): JSONObject = JSONObject().apply {
        put("id", event.id)
        put("timestamp_ms", event.timestampMs)
        put("kind", event.kind.name)
        put("risk", event.riskLevel.name)
        putNullable("tool", event.toolName)
        putNullable("rule", event.ruleName)
        putNullable("policy", event.policyCode)
        putNullable("reason", event.reason)
        putNullable("command_hash_sha256", event.commandHashSha256)
        putNullable("command_preview", event.commandPreview)
    }

    private fun decodeEvent(value: JSONObject?): SecurityAuditEvent? {
        value ?: return null
        val kind = runCatching { SecurityAuditKind.valueOf(value.optString("kind")) }.getOrNull()
            ?: return null
        val risk = runCatching { SecurityRiskLevel.valueOf(value.optString("risk")) }.getOrNull()
            ?: return null
        val timestampMs = value.optLong("timestamp_ms", 0L).takeIf { it > 0L } ?: return null
        val id = value.optLong("id", 0L).coerceAtLeast(0L)
        return SecurityAuditEvent(
            id = id,
            timestampMs = timestampMs,
            kind = kind,
            riskLevel = risk,
            toolName = value.safeText("tool"),
            ruleName = value.safeText("rule"),
            policyCode = value.safeText("policy"),
            reason = value.safeText("reason"),
            commandHashSha256 = value.safeHash(),
            commandPreview = value.safeText("command_preview"),
        )
    }

    private fun JSONObject.safeText(key: String): String? =
        optString(key).trim().takeIf(String::isNotEmpty)?.take(MAX_TEXT_CHARS)

    private fun JSONObject.safeHash(): String? =
        optString("command_hash_sha256")
            .lowercase()
            .takeIf { it.matches(Regex("[0-9a-f]{64}")) }

    private fun JSONObject.putNullable(key: String, value: String?) {
        if (!value.isNullOrBlank()) put(key, value.take(MAX_TEXT_CHARS))
    }
}
