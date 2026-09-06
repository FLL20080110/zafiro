package com.niki914.zafiro.settings

/**
 * Process-local capability facts that are proven by runtime execution rather than inferred from
 * installed packages. The app-side IPC service owns updates; the agent runtime only consumes the
 * minimized boolean state.
 */
object RuntimeCapabilityRegistry {
    @Volatile
    private var xposedActivationConfirmed: Boolean = false

    fun setXposedActivationConfirmed(confirmed: Boolean) {
        xposedActivationConfirmed = confirmed
    }

    fun isXposedActivationConfirmed(): Boolean = xposedActivationConfirmed

    fun promptFragment(): String {
        val xposedLine = if (xposedActivationConfirmed) {
            "- Xposed/LSPosed module activation: CONFIRMED by a live injected host process."
        } else {
            "- Xposed/LSPosed module activation: NOT OBSERVED. This means unknown/not currently observed; it does NOT mean LSPosed/Xposed is absent or uninstalled."
        }

        return """
            ## Authoritative local runtime capability facts
            $xposedLine

            Capability-diagnosis rules (must follow):
            - Treat this runtime-capability section and actual tool execution results as authoritative.
            - Never infer that Xposed/LSPosed, Root, or Shizuku is installed, absent, active, inactive, authorized, or unauthorized from package lists, manager-app presence, process names, /system/framework files, APK manifests, or guessed service names.
            - If Xposed/LSPosed activation is NOT OBSERVED, say "module injection has not been observed" or "status unknown". Never say "LSPosed/Xposed is not installed" unless a dedicated authoritative runtime signal explicitly proves absence.
            - For Root/Shizuku, package presence is only a hint. Availability must be determined from an actual local terminal backend/identity execution result. If no such proof exists in the current task, report the state as unknown instead of guessing.
            - Android ASSIST/VoiceInteractionService and RECORD_AUDIO are NOT prerequisites for foreground app control through Accessibility. Do not diagnose missing ASSIST as the reason screen reading, tapping, text entry, or sending a foreground chat message cannot work.
            - When asked to operate a foreground app such as WeChat, prefer screen_operation_accessibility read -> editable field set_text -> send-button tap. Only claim the page is not controllable after the screen tool itself returns an unusable/empty tree or an explicit failure.
        """.trimIndent()
    }

    internal fun clearForTest() {
        xposedActivationConfirmed = false
    }
}
