package com.niki914.zafiro.repo

import android.content.Context
import android.content.ContextWrapper
import com.niki914.store.StoreDescriptorRegistry
import com.niki914.zafiro.app.util.SilentLoggerRule
import com.niki914.zafiro.chat.agentic.accessibility.SensitiveAppPolicyRegistry
import com.niki914.zafiro.chat.agentic.shell.SecurityAuditLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

class SensitiveAppSettingsTest {
    @get:Rule
    val silentLoggerRule = SilentLoggerRule()

    private val context: Context = object : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
        override fun getPackageName(): String = "com.niki914.zafiro"
    }

    @After
    fun tearDown() {
        SensitiveAppPolicyRegistry.clear()
        SecurityAuditLog.clear()
        XRepo.resetForTest()
    }

    @Test
    fun concurrentPolicyUpdatesKeepDurableAndRuntimeSnapshotsConsistent() = runTest {
        val store = FakeDomainSettingsStore(
            StoreDescriptorRegistry.APP_STATE_ID to AppStateSettingsCodec.encode(AppStateSettingsDocument())
        )
        XRepo.installStoreForTest(store)
        XRepo.init(context)

        val packages = (1..24).map { "com.example.sensitive$it" }.toSet()
        coroutineScope {
            packages.map { packageName ->
                async(Dispatchers.Default) {
                    SensitiveAppSettings.setPaused(packageName, paused = true)
                }
            }.awaitAll()
        }

        val durable = SensitiveAppSettings.packages()
        val runtime = SensitiveAppPolicyRegistry.snapshot().keys
        assertEquals(packages, durable)
        assertEquals(durable, runtime)
    }

    @Test
    fun malformedPersistedPackageNamesAreDropped() {
        assertEquals(
            setOf("com.example.good", "org.example._valid2"),
            SensitiveAppSettings.decode(
                " com.example.good ,bad-name,com..broken,org.example._valid2,9invalid.start "
            )
        )
    }

    @Test
    fun packageNameLengthIsBounded() {
        val tooLong = "a".repeat(256)
        assertNull(SensitiveAppSettings.normalizePackageName(tooLong))
    }

    @Test
    fun persistedPolicyCountIsBounded() {
        val csv = (1..700).joinToString(",") { "com.example.app$it" }
        assertEquals(512, SensitiveAppSettings.decode(csv).size)
    }
}
