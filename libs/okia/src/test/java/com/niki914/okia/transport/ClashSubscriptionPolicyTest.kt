package com.niki914.okia.transport

import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ClashSubscriptionPolicyTest {
    @Test
    fun firstEnabledCheckRefreshesImmediately() {
        assertTrue(ClashSubscriptionPolicy(enabled = true).shouldRefresh(null, 1L))
    }

    @Test
    fun refreshesAtFifteenMinutesButNotBefore() {
        val policy = ClashSubscriptionPolicy(enabled = true)
        val last = 1_000L
        assertFalse(policy.shouldRefresh(last, last + ClashSubscriptionPolicy.MIN_REFRESH_INTERVAL_MILLIS - 1L))
        assertTrue(policy.shouldRefresh(last, last + ClashSubscriptionPolicy.MIN_REFRESH_INTERVAL_MILLIS))
    }

    @Test
    fun disabledOrManualRefreshDoesNotAutoRefresh() {
        assertFalse(ClashSubscriptionPolicy(enabled = false).shouldRefresh(null, 1L))
        assertFalse(ClashSubscriptionPolicy(enabled = true, autoRefresh = false).shouldRefresh(null, 1L))
    }

    @Test
    fun elapsedClockRollbackRefreshesFailSafe() {
        assertTrue(ClashSubscriptionPolicy(enabled = true).shouldRefresh(10_000L, 1_000L))
    }

    @Test
    fun intervalBelowAndroidPeriodicMinimumIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            ClashSubscriptionPolicy(refreshIntervalMillis = ClashSubscriptionPolicy.MIN_REFRESH_INTERVAL_MILLIS - 1L)
        }
    }

    @Test
    fun enabledProxyCannotSilentlyFallBackToDirect() {
        assertThrows(IllegalArgumentException::class.java) {
            ClashSubscriptionPolicy(enabled = true, failClosed = false)
        }
    }
}
