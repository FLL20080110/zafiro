package com.niki914.okia.transport

import java.net.URI

/** Validation and redaction for remote Clash/Mihomo subscription URLs. */
object ClashSubscriptionUrlPolicy {
    private const val MAX_URL_CHARS = 8_192

    fun validate(rawUrl: String): String {
        val value = rawUrl.trim()
        require(value.isNotEmpty()) { "Clash 订阅地址不能为空" }
        require(value.length <= MAX_URL_CHARS) { "Clash 订阅地址过长" }

        val uri = runCatching { URI(value) }
            .getOrElse { throw IllegalArgumentException("Clash 订阅地址格式无效") }
        require(uri.scheme.equals("https", ignoreCase = true)) {
            "远程 Clash 订阅必须使用 HTTPS"
        }
        require(uri.userInfo == null) { "Clash 订阅地址不能包含用户名或密码" }
        require(!uri.host.isNullOrBlank()) { "Clash 订阅地址缺少主机地址" }
        require(uri.fragment == null) { "Clash 订阅地址不能包含片段" }
        return value
    }

    /** Never returns path/query/token; suitable for Settings UI. */
    fun redactedLabel(rawUrl: String): String {
        val uri = URI(validate(rawUrl))
        val port = if (uri.port == -1 || uri.port == 443) "" else ":${uri.port}"
        return "${uri.host}$port · 已配置"
    }
}
