package com.fll.zafiro.wexposednext.compat

/**
 * Version-neutral description of a WeChat hook target.
 *
 * Feature code should depend on this model instead of hard-coding obfuscated
 * class/method names. A resolver can later bind these fingerprints to the
 * concrete symbols present in a specific WeChat APK.
 */
data class HookTarget(
    val id: String,
    val feature: String,
    val candidateClassNames: List<String> = emptyList(),
    val methodNameHints: List<String> = emptyList(),
    val parameterCount: Int? = null,
    val returnTypeHint: String? = null,
    val requiredStringConstants: List<String> = emptyList(),
    val requiredFieldTypeHints: List<String> = emptyList(),
)

/** Result of resolving one logical hook target against one WeChat build. */
data class ResolvedHookTarget(
    val target: HookTarget,
    val className: String,
    val methodName: String,
    val descriptor: String? = null,
    val confidence: Double,
)
