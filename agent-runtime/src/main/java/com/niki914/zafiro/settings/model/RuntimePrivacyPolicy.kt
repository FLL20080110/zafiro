package com.niki914.zafiro.settings.model

data class RuntimePrivacyPolicy(
    val enabled: Boolean = false,
) {
    val allowCloudLlm: Boolean get() = !enabled
    val allowMcp: Boolean get() = !enabled
    val allowMemoryWrites: Boolean get() = !enabled
    val allowNetworkTools: Boolean get() = !enabled
    val allowSensitiveContextUpload: Boolean get() = !enabled
}
