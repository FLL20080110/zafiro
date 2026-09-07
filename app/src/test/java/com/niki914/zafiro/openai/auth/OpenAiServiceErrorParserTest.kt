package com.niki914.zafiro.openai.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiServiceErrorParserTest {
    @Test
    fun `plus usage limit is rendered in Chinese with reset time`() {
        val parsed = OpenAiServiceErrorParser.parse(
            """{"error":{"type":"usage_limit_reached","message":"The usage limit has been reached","plan_type":"plus","resets_at":1788798811,"resets_in_seconds":424}}"""
        )

        assertEquals("usage_limit_reached", parsed.type)
        assertEquals("plus", parsed.planType)
        assertEquals(1788798811L, parsed.resetsAtEpochSeconds)
        assertEquals(424L, parsed.resetsInSeconds)
        assertEquals("ChatGPT Plus 使用额度已用完，约 8 分钟后恢复", parsed.friendlyMessage)
    }

    @Test
    fun `does not invent used remaining or limit when service omits them`() {
        val parsed = OpenAiServiceErrorParser.parse(
            """{"error":{"type":"usage_limit_reached","resets_in_seconds":424}}"""
        )

        assertNull(parsed.used)
        assertNull(parsed.remaining)
        assertNull(parsed.limit)
    }

    @Test
    fun `preserves quota values when server explicitly supplies them`() {
        val parsed = OpenAiServiceErrorParser.parse(
            """{"error":{"type":"usage_limit_reached","used":80,"remaining":20,"limit":100}}"""
        )

        assertEquals(80L, parsed.used)
        assertEquals(20L, parsed.remaining)
        assertEquals(100L, parsed.limit)
    }

    @Test
    fun `auth and model errors get actionable Chinese messages`() {
        assertEquals(
            "ChatGPT 登录已失效，请重新登录",
            OpenAiServiceErrorParser.parse("""{"error":{"code":"invalid_api_key"}}""").friendlyMessage
        )
        assertEquals(
            "当前模型不可用，请刷新模型列表或更换模型",
            OpenAiServiceErrorParser.parse("""{"error":{"code":"model_not_found"}}""").friendlyMessage
        )
    }

    @Test
    fun `malformed response never crashes and gets Chinese fallback`() {
        val parsed = OpenAiServiceErrorParser.parse("HTTP 500 <<broken json>>")
        assertEquals("请求失败，请稍后重试", parsed.friendlyMessage)
    }

    @Test
    fun `embedded json is accepted`() {
        val parsed = OpenAiServiceErrorParser.parse(
            "HTTP 429: {\"error\":{\"type\":\"rate_limit_error\",\"message\":\"Too many requests\"}}"
        )
        assertEquals("请求过于频繁，请稍后重试", parsed.friendlyMessage)
    }

    @Test
    fun `tokens are removed from raw detail`() {
        val parsed = OpenAiServiceErrorParser.parse(
            "Authorization: Bearer secret.token.value access_token=super-secret"
        )
        val raw = parsed.rawMessage.orEmpty()

        assertFalse(raw.contains("secret.token.value"))
        assertFalse(raw.contains("super-secret"))
        assertTrue(raw.contains("[已隐藏]"))
    }
}
