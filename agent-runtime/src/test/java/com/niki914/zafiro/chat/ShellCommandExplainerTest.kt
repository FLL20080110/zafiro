package com.niki914.zafiro.chat

import com.niki914.zafiro.chat.agentic.shell.ShellCommandExplainer
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellCommandExplainerTest {
    @Test
    fun explanationsAreCategoryLevelAndDoNotRepeatSensitiveArguments() {
        val secretPath = "/data/local/tmp/private-token.txt"
        val secretPackage = "com.example.secretbank"
        val secretUrl = "https://example.invalid/upload?token=super-secret-token"
        val otp = "123456"

        val commands = listOf(
            "rm -rf $secretPath",
            "pm uninstall $secretPackage",
            "curl -H 'Authorization: Bearer super-secret-token' '$secretUrl' --data otp=$otp",
        )

        commands.forEach { command ->
            val rendered = ShellCommandExplainer.explain(command) + "\n" +
                ShellCommandExplainer.worstCaseImpact(command)

            assertFalse(rendered.contains(secretPath))
            assertFalse(rendered.contains(secretPackage))
            assertFalse(rendered.contains(secretUrl))
            assertFalse(rendered.contains("super-secret-token"))
            assertFalse(rendered.contains(otp))
        }
    }

    @Test
    fun destructiveAndPrivilegedCommandsGetSpecificExplanations() {
        val uninstall = ShellCommandExplainer.explain("pm uninstall com.example.app")
        val remove = ShellCommandExplainer.explain("rm -rf /data/local/tmp/example")
        val root = ShellCommandExplainer.explain("su -c id")
        val network = ShellCommandExplainer.explain("iptables -F")

        assertTrue(uninstall.contains("卸载应用"))
        assertTrue(remove.contains("删除文件或目录"))
        assertTrue(root.contains("更高系统权限"))
        assertTrue(network.contains("网络路由、防火墙或流量规则"))
    }

    @Test
    fun commandBoundaryMatchingDoesNotMistakeOrdinaryWordsForRm() {
        val explanation = ShellCommandExplainer.explain("echo firmware")
        val impact = ShellCommandExplainer.worstCaseImpact("echo firmware")

        assertTrue(explanation.contains("这条命令将通过 Shell"))
        assertTrue(impact.contains("这条 Shell 命令会以当前工具权限执行"))
    }
}
