package com.niki914.okia.transport

import okhttp3.OkHttpClient
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI

/**
 * Creates an [HttpEngine] for a model-only proxy URL.
 *
 * Supported Clash/Mihomo local proxy forms:
 * - http://127.0.0.1:7890
 * - socks://127.0.0.1:7891
 * - socks5://127.0.0.1:7891
 *
 * Blank means direct connection and returns null so callers can retain the normal engine.
 * Credentials are deliberately rejected for now so secrets cannot leak through URL/log output.
 */
fun modelProxyHttpEngine(proxyUrl: String): HttpEngine? {
    val value = proxyUrl.trim()
    if (value.isEmpty()) return null

    val uri = runCatching { URI(value) }
        .getOrElse { throw IllegalArgumentException("Invalid model proxy address") }
    require(uri.userInfo.isNullOrBlank()) { "Authenticated model proxy is not supported yet" }
    val host = uri.host?.takeIf { it.isNotBlank() }
        ?: throw IllegalArgumentException("Model proxy host is missing")
    val port = uri.port
    require(port in 1..65535) { "Model proxy port is invalid" }

    val proxyType = when (uri.scheme?.lowercase()) {
        "http" -> Proxy.Type.HTTP
        "socks", "socks5" -> Proxy.Type.SOCKS
        else -> throw IllegalArgumentException("Model proxy must use http://, socks:// or socks5://")
    }
    val client = OkHttpClient.Builder()
        .proxy(Proxy(proxyType, InetSocketAddress.createUnresolved(host, port)))
        .build()
    return OkHttpEngine(client)
}
