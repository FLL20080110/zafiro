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

    fun promptFragment(): String = if (xposedActivationConfirmed) {
        "## Local runtime capabilities\n- Xposed/LSPosed module activation: confirmed by a live injected host process."
    } else {
        "## Local runtime capabilities\n- Xposed/LSPosed module activation: not confirmed. Do not claim Xposed/LSPosed hooks are active based on installed apps or manager presence."
    }

    internal fun clearForTest() {
        xposedActivationConfirmed = false
    }
}
