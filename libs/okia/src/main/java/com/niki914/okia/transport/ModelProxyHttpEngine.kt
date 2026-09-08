package com.niki914.okia.transport

import okhttp3.OkHttpClient
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI

internal data class ModelProxySpec(
    val scheme: String,
    val host: String,
    val port: Int,
)

/**
 * Parses the explicit local listener used only for model traffic.
 *
 * Clash/Mihomo subscription URLs do NOT belong here. They may contain credentials and are handled
 * by the app-private subscription layer. This value is deliberately limited to a plain listener
 * address so secrets cannot be smuggled through user-info, path, query or fragment components.
 */
internal fun parseModelProxySpec(proxyUrl: String): ModelProxySpec? {
    val value = proxyUrl.trim()
    if (value.isEmpty()) return null

    val uri = runCatching { URI(value) }
        .getOrElse { throw IllegalArgumentException("模型代理地址格式无效") }
    require(uri.userInfo.isNullOrBlank()) { "模型代理地址不能包含用户名或密码" }
    require(uri.rawPath.isNullOrEmpty()) { "模型代理地址不能包含路径" }
    require(uri.rawQuery.isNullOrEmpty()) { "模型代理地址不能包含查询参数" }
    require(uri.rawFragment.isNullOrEmpty()) { "模型代理地址不能包含片段" }

    val scheme = uri.scheme?.lowercase()
    require(scheme == "http" || scheme == "socks" || scheme == "socks5") {
        "模型代理仅支持 http://、socks:// 或 socks5://"
    }
    val host = uri.host?.takeIf { it.isNotBlank() }
        ?: throw IllegalArgumentException("模型代理缺少主机地址")
    val port = uri.port
    require(port in 1..65535) { "模型代理端口无效" }

    return ModelProxySpec(scheme = scheme, host = host, port = port)
}

/** Creates an [HttpEngine] for an explicit model-only Clash/Mihomo listener. */
fun modelProxyHttpEngine(proxyUrl: String): HttpEngine? {
    val spec = parseModelProxySpec(proxyUrl) ?: return null
    val proxyType = when (spec.scheme) {
        "http" -> Proxy.Type.HTTP
        "socks", "socks5" -> Proxy.Type.SOCKS
        else -> error("Validated model proxy scheme changed unexpectedly")
    }
    val client = OkHttpClient.Builder()
        .proxy(Proxy(proxyType, InetSocketAddress.createUnresolved(spec.host, spec.port)))
        .build()
    return OkHttpEngine(client)
}
