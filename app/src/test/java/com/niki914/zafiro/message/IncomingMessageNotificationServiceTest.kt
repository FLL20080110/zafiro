package com.niki914.zafiro.message

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class IncomingMessageNotificationServiceTest {

    @Before
    fun setUp() {
        IncomingMessageReplyRegistry.clear()
    }

    @After
    fun tearDown() {
        IncomingMessageReplyRegistry.clear()
    }

    @Test
    fun listenerDisconnectRevokesAllRemoteInputHandles() {
        val handle = registerReplyHandle("notification-key")
        assertNotNull(handle)
        assertTrue(IncomingMessageReplyRegistry.hasHandleForTest(handle!!))

        val service = Robolectric.buildService(IncomingMessageNotificationService::class.java)
            .create()
            .get()
        service.onListenerDisconnected()

        assertFalse(IncomingMessageReplyRegistry.hasHandleForTest(handle))
    }

    @Test
    fun listenerDestroyRevokesAllRemoteInputHandles() {
        val handle = registerReplyHandle("notification-key")
        assertNotNull(handle)
        assertTrue(IncomingMessageReplyRegistry.hasHandleForTest(handle!!))

        val controller = Robolectric.buildService(IncomingMessageNotificationService::class.java)
            .create()
        controller.destroy()

        assertFalse(IncomingMessageReplyRegistry.hasHandleForTest(handle))
    }

    @Test
    fun revokedNotificationHandleIsNotReportedAvailable() {
        val handle = registerReplyHandle("notification-key")
        assertNotNull(handle)
        assertTrue(IncomingMessageReplyRegistry.isHandleAvailable(handle))

        IncomingMessageReplyRegistry.revokeForNotification("notification-key")

        assertFalse(IncomingMessageReplyRegistry.isHandleAvailable(handle))
    }

    @Test
    fun sensitiveContentBlocksCredentialsPaymentsAndLongAccountNumbers() {
        assertTrue(isSensitiveMessageContent("验证码 482193，请勿告诉他人"))
        assertTrue(isSensitiveMessageContent("把付款码发我一下"))
        assertTrue(isSensitiveMessageContent("Please send your access token"))
        assertTrue(isSensitiveMessageContent("卡号是 6222021234567890123"))
    }

    @Test
    fun ordinaryChatIsNotMarkedSensitive() {
        assertFalse(isSensitiveMessageContent("今晚八点一起吃饭吗？"))
        assertFalse(isSensitiveMessageContent("Call me at 13800138000 when you arrive"))
    }

    private fun registerReplyHandle(notificationKey: String): String? {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            42,
            Intent("com.niki914.zafiro.TEST_REPLY"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val remoteInput = RemoteInput.Builder("reply")
            .setAllowFreeFormInput(true)
            .build()
        val action = Notification.Action.Builder(null, "Reply", pendingIntent)
            .addRemoteInput(remoteInput)
            .build()
        return IncomingMessageReplyRegistry.register(action, notificationKey)
    }
}
