package com.niki914.okia.transport

/**
 * Secret-free policy for a model-only Clash/Mihomo subscription.
 *
 * The subscription URL/token is intentionally NOT part of this model. Android code must keep
 * that credential in app-private encrypted storage and only pass it to the Mihomo adapter at
 * refresh time.
 */
data class ClashSubscriptionPolicy(
    val enabled: Boolean = false,
    val autoRefresh: Boolean = true,
    val refreshIntervalMillis: Long = MIN_REFRESH_INTERVAL_MILLIS,
    val autoSwitch: Boolean = true,
    val failClosed: Boolean = true,
) {
    init {
        require(refreshIntervalMillis >= MIN_REFRESH_INTERVAL_MILLIS) {
            "Clash 订阅自动刷新间隔不能小于 15 分钟"
        }
        require(!enabled || failClosed) {
            "模型代理启用时必须使用 fail-closed，禁止失败后直连"
        }
    }

    fun shouldRefresh(lastRefreshElapsedMillis: Long?, nowElapsedMillis: Long): Boolean {
        if (!enabled || !autoRefresh) return false
        if (lastRefreshElapsedMillis == null) return true
        // elapsedRealtime normally cannot move backwards; fail safe after reboot/clock-domain reset.
        if (nowElapsedMillis < lastRefreshElapsedMillis) return true
        return nowElapsedMillis - lastRefreshElapsedMillis >= refreshIntervalMillis
    }

    companion object {
        const val MIN_REFRESH_INTERVAL_MILLIS: Long = 15L * 60L * 1000L
    }
}

data class ClashProxyStatus(
    val configured: Boolean = false,
    val activeNode: String? = null,
    val latencyMillis: Long? = null,
    val nodeCount: Int? = null,
    val lastRefreshElapsedMillis: Long? = null,
    val refreshing: Boolean = false,
) {
    init {
        require(latencyMillis == null || latencyMillis >= 0) { "节点延迟不能为负数" }
        require(nodeCount == null || nodeCount >= 0) { "节点数量不能为负数" }
    }
}
