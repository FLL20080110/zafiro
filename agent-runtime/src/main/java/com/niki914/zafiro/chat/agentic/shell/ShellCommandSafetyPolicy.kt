package com.niki914.zafiro.chat.agentic.shell

import com.niki914.xposed.api.util.LockState
import com.niki914.zafiro.settings.RuntimeEnvironment
import com.niki914.zafiro.util.TextPatternMatcher
import java.util.UUID
import com.niki914.zafiro.settings.model.RuntimeExecutionRule as ExecutionRule
import com.niki914.zafiro.settings.model.RuntimeExecutionRuleEnabledMode as ExecutionRuleEnabledMode

data class ShellCommandPolicyDecision(
    val allowed: Boolean,
    val code: String = "OK",
    val reason: String = "",
    val matchedRuleId: String? = null,
    val matchedRuleName: String? = null,
    val matchedPattern: String? = null,
)

/**
 * Local, model-independent safety gate for shell execution.
 *
 * User-defined execution rules are still evaluated, but a minimum built-in
 * policy is always applied first. This prevents a fresh installation with no
 * rules from silently executing destructive commands suggested by an LLM,
 * imported skill, MCP server, or prompt-injected page.
 */
class ShellCommandSafetyPolicy(
    private val listExecutionRules: suspend () -> List<ExecutionRule> = {
        RuntimeEnvironment.awaitSettingsGateway().listExecutionRules()
    },
    private val isUnlocked: suspend () -> Boolean = { LockState.isUnlocked() },
) {
    suspend fun evaluate(command: String, toolName: String): ShellCommandPolicyDecision {
        val candidates = command.matchCandidates()

        // Hard safety floor: these checks cannot be disabled by deleting all
        // user-defined execution rules.
        when (val builtIn = candidates.evaluateBuiltInProtection()) {
            is BuiltInProtection.Deny -> {
                return ShellCommandPolicyDecision(
                    allowed = false,
                    code = "BUILTIN_CRITICAL_BLOCKED",
                    reason = builtIn.reason,
                    matchedRuleName = builtIn.name,
                    matchedPattern = builtIn.pattern,
                )
            }

            is BuiltInProtection.Confirm -> {
                when (
                    ToolPermissionCoordinator.confirm(
                        ToolPermissionRequest(
                            id = UUID.randomUUID().toString(),
                            toolName = toolName,
                            command = command,
                            matchedRuleName = builtIn.name,
                        )
                    )
                ) {
                    ToolPermissionResponse.ALLOWED,
                    ToolPermissionResponse.ALLOWED_TEMPORARY -> Unit
                    ToolPermissionResponse.DENIED_BY_USER -> {
                        return ShellCommandPolicyDecision(
                            allowed = false,
                            code = "BUILTIN_CONFIRM_DENIED",
                            reason = "The user denied a sensitive shell operation.",
                            matchedRuleName = builtIn.name,
                            matchedPattern = builtIn.pattern,
                        )
                    }

                    ToolPermissionResponse.DENIED_UNAVAILABLE -> {
                        return ShellCommandPolicyDecision(
                            allowed = false,
                            code = "BUILTIN_CONFIRM_UNAVAILABLE",
                            reason = "This sensitive shell operation requires explicit user confirmation, " +
                                    "but the current session cannot display a confirmation prompt.",
                            matchedRuleName = builtIn.name,
                            matchedPattern = builtIn.pattern,
                        )
                    }
                }
            }

            BuiltInProtection.Allow -> Unit
        }

        val rules = listExecutionRules()
        if (rules.isEmpty()) {
            return ShellCommandPolicyDecision(allowed = true)
        }
        val unlocked = if (rules.any { it.enabledMode == ExecutionRuleEnabledMode.LOCKED_ONLY }) {
            isUnlocked()
        } else {
            true
        }
        for (rule in rules.filter { it.isActive(unlocked) }) {
            val pattern = rule.patterns.asSequence()
                .map(String::trim)
                .filter(String::isNotBlank)
                .firstOrNull { pattern ->
                    candidates.any { candidate ->
                        TextPatternMatcher.matches(
                            candidate,
                            pattern
                        )
                    }
                }
                ?: continue
            val blocked = ShellCommandPolicyDecision(
                allowed = false,
                code = "RULE_BLOCKED",
                reason = "Command blocked by execution rule '${rule.name}' with pattern '$pattern'.",
                matchedRuleId = rule.id,
                matchedRuleName = rule.name,
                matchedPattern = pattern,
            )
            when (rule.enabledMode) {
                ExecutionRuleEnabledMode.ALWAYS, ExecutionRuleEnabledMode.LOCKED_ONLY -> return blocked
                ExecutionRuleEnabledMode.DISABLED -> {}
                ExecutionRuleEnabledMode.CONFIRM -> when (
                    ToolPermissionCoordinator.confirm(
                        blocked.toConfirmationRequest(
                            command,
                            toolName
                        )
                    )
                ) {
                    ToolPermissionResponse.ALLOWED,
                    ToolPermissionResponse.ALLOWED_TEMPORARY -> continue
                    ToolPermissionResponse.DENIED_BY_USER -> return blocked.copy(
                        code = "CONFIRM_DENIED",
                        reason = "The user denied this operation.",
                    )

                    ToolPermissionResponse.DENIED_UNAVAILABLE -> return blocked.copy(
                        code = "CONFIRM_UNAVAILABLE",
                        reason = "Tool execution requires user confirmation, but this session " +
                                "cannot request permission from the user. The operation was denied.",
                    )
                }
            }
        }
        return ShellCommandPolicyDecision(allowed = true)
    }

    private fun ShellCommandPolicyDecision.toConfirmationRequest(
        command: String,
        toolName: String,
    ): ToolPermissionRequest {
        return ToolPermissionRequest(
            id = UUID.randomUUID().toString(),
            toolName = toolName,
            command = command,
            matchedRuleName = matchedRuleName.orEmpty(),
        )
    }

    private fun List<String>.evaluateBuiltInProtection(): BuiltInProtection {
        for (candidate in this) {
            val normalized = candidate.lowercase().replace(WHITESPACE_REGEX, " ").trim()

            CRITICAL_PATTERNS.firstOrNull { it.regex.containsMatchIn(normalized) }?.let { rule ->
                return BuiltInProtection.Deny(
                    name = rule.name,
                    pattern = rule.label,
                    reason = rule.reason,
                )
            }

            SENSITIVE_PATTERNS.firstOrNull { it.regex.containsMatchIn(normalized) }?.let { rule ->
                return BuiltInProtection.Confirm(
                    name = rule.name,
                    pattern = rule.label,
                )
            }
        }
        return BuiltInProtection.Allow
    }

    private fun String.shellLikeTokens(): List<String> {
        val tokens = mutableListOf<String>()
        val current = StringBuilder()
        var quote: Char? = null
        var escaped = false

        fun flush() {
            if (current.isNotEmpty()) {
                tokens += current.toString()
                current.clear()
            }
        }

        for (char in this) {
            when {
                escaped -> {
                    current.append(char)
                    escaped = false
                }

                char == '\\' -> escaped = true
                quote != null -> {
                    if (char == quote) {
                        quote = null
                    } else {
                        current.append(char)
                    }
                }

                char == '\'' || char == '"' -> quote = char
                char.isWhitespace() || char in SHELL_TOKEN_SEPARATORS -> flush()
                else -> current.append(char)
            }
        }
        if (escaped) {
            current.append('\\')
        }
        flush()
        return tokens
    }

    private fun String.matchCandidates(): List<String> {
        val candidates = linkedSetOf<String>()
        collectMatchCandidates(depth = 0, candidates = candidates)
        return candidates.toList()
    }

    private fun String.collectMatchCandidates(depth: Int, candidates: MutableSet<String>) {
        if (depth > MAX_SHELL_PAYLOAD_DEPTH) {
            return
        }
        val tokens = shellLikeTokens()
        val normalizedTokens = tokens
            .map { it.normalizedShellToken() }
            .filter { it.isNotBlank() }
        candidates += this
        candidates += normalizedTokens.joinToString(separator = " ")
        normalizedTokens.nestedShellPayloads().forEach { payload ->
            candidates += payload
            payload.collectMatchCandidates(depth = depth + 1, candidates = candidates)
        }
    }

    private fun String.normalizedShellToken(): String {
        return lowercase()
            .trim()
            .trim('"', '\'')
    }

    private fun List<String>.nestedShellPayloads(): List<String> {
        val payloads = mutableListOf<String>()
        for (index in indices) {
            val executable = this[index].executableName()
            val payload = when {
                executable in SHELL_COMMANDS -> shellCommandPayloadAfterC(startIndex = index + 1)
                executable == "eval" -> drop(index + 1).joinToString(" ").takeIf { it.isNotBlank() }
                else -> null
            }
            if (payload != null) {
                payloads += payload
            }
        }
        return payloads
    }

    private fun List<String>.shellCommandPayloadAfterC(startIndex: Int): String? {
        for (index in startIndex until size) {
            val token = this[index]
            if (token == "-c") {
                return getOrNull(index + 1)
            }
            if (!token.startsWith("-")) {
                return null
            }
        }
        return null
    }

    private fun String.executableName(): String {
        return substringAfterLast('/')
    }

    private fun ExecutionRule.isActive(unlocked: Boolean): Boolean {
        return when (enabledMode) {
            ExecutionRuleEnabledMode.ALWAYS, ExecutionRuleEnabledMode.CONFIRM -> true
            ExecutionRuleEnabledMode.LOCKED_ONLY -> !unlocked
            ExecutionRuleEnabledMode.DISABLED -> false
        }
    }

    private sealed interface BuiltInProtection {
        data object Allow : BuiltInProtection

        data class Confirm(
            val name: String,
            val pattern: String,
        ) : BuiltInProtection

        data class Deny(
            val name: String,
            val pattern: String,
            val reason: String,
        ) : BuiltInProtection
    }

    private data class BuiltInPattern(
        val name: String,
        val label: String,
        val reason: String,
        val regex: Regex,
    )

    companion object {
        private const val MAX_SHELL_PAYLOAD_DEPTH = 8
        private val SHELL_TOKEN_SEPARATORS = setOf(';', '&', '|', '`', '$', '(', ')', '<', '>')
        private val SHELL_COMMANDS = setOf("sh", "bash", "mksh")
        private val WHITESPACE_REGEX = Regex("\\s+")

        /**
         * Catastrophic operations are denied rather than confirmed. The agent
         * should use a dedicated, structured action in a future advanced mode
         * if the user explicitly needs one of these operations.
         */
        private val CRITICAL_PATTERNS = listOf(
            BuiltInPattern(
                name = "Critical: filesystem format",
                label = "mkfs/wipefs",
                reason = "Formatting or wiping a filesystem is blocked for AI shell execution.",
                regex = Regex("\\b(?:mkfs(?:\\.[a-z0-9_+.-]+)?|wipefs)\\b", RegexOption.IGNORE_CASE),
            ),
            BuiltInPattern(
                name = "Critical: raw block write",
                label = "dd -> /dev/block",
                reason = "Raw writes to block devices are blocked for AI shell execution.",
                regex = Regex("\\bdd\\b[^\\n;&|]*\\bof\\s*=\\s*/dev/(?:block/)?", RegexOption.IGNORE_CASE),
            ),
            BuiltInPattern(
                name = "Critical: boot/system flashing",
                label = "fastboot/flash_image erase or flash",
                reason = "Flashing or erasing boot/system partitions is blocked for AI shell execution.",
                regex = Regex("\\b(?:fastboot\\s+(?:flash|erase|format)|flash_image)\\b", RegexOption.IGNORE_CASE),
            ),
            BuiltInPattern(
                name = "Critical: disable SELinux",
                label = "setenforce 0",
                reason = "Disabling SELinux is blocked for AI shell execution.",
                regex = Regex("\\bsetenforce\\s+0\\b", RegexOption.IGNORE_CASE),
            ),
            BuiltInPattern(
                name = "Critical: protected partition delete",
                label = "rm on protected Android partitions",
                reason = "Deleting files from boot/system/vendor/product/odm/recovery is blocked for AI shell execution.",
                regex = Regex("\\brm\\b[^\\n;&|]*(?:/system|/vendor|/product|/odm|/boot|/recovery)(?:/|\\s|$)", RegexOption.IGNORE_CASE),
            ),
            BuiltInPattern(
                name = "Critical: protected partition remount",
                label = "mount protected Android partitions",
                reason = "Mounting or remounting protected Android partitions is blocked for AI shell execution.",
                regex = Regex("\\bmount\\b[^\\n;&|]*(?:/system|/vendor|/product|/odm|/boot)(?:/|\\s|$)", RegexOption.IGNORE_CASE),
            ),
        )

        /**
         * Reversible but privacy/security-impacting commands require an explicit
         * user decision even when the execution-rule list is empty.
         */
        private val SENSITIVE_PATTERNS = listOf(
            BuiltInPattern(
                name = "Sensitive: file mutation",
                label = "rm/mv/chmod/chown/chgrp",
                reason = "",
                regex = Regex("\\b(?:rm|mv|chmod|chown|chgrp)\\b", RegexOption.IGNORE_CASE),
            ),
            BuiltInPattern(
                name = "Sensitive: app/package management",
                label = "pm/cmd package/appops/dpm",
                reason = "",
                regex = Regex("\\b(?:pm|appops|dpm)\\b|\\bcmd\\s+package\\b", RegexOption.IGNORE_CASE),
            ),
            BuiltInPattern(
                name = "Sensitive: system settings",
                label = "settings/setprop",
                reason = "",
                regex = Regex("\\b(?:settings|setprop)\\b", RegexOption.IGNORE_CASE),
            ),
            BuiltInPattern(
                name = "Sensitive: process/device control",
                label = "kill/reboot/shutdown/force-stop",
                reason = "",
                regex = Regex("\\b(?:kill|pkill|killall|reboot|shutdown|poweroff)\\b|\\bam\\s+force-stop\\b", RegexOption.IGNORE_CASE),
            ),
            BuiltInPattern(
                name = "Sensitive: privilege escalation",
                label = "su/magisk",
                reason = "",
                regex = Regex("(?:^|\\s)(?:su|magisk)(?:\\s|$)", RegexOption.IGNORE_CASE),
            ),
            BuiltInPattern(
                name = "Sensitive: network transfer",
                label = "curl/wget",
                reason = "",
                regex = Regex("\\b(?:curl|wget)\\b", RegexOption.IGNORE_CASE),
            ),
            BuiltInPattern(
                name = "Sensitive: network policy",
                label = "iptables/nft/ip route/ip rule",
                reason = "",
                regex = Regex("\\b(?:iptables|ip6tables|nft)\\b|\\bip\\s+(?:route|rule)\\b", RegexOption.IGNORE_CASE),
            ),
        )
    }
}
