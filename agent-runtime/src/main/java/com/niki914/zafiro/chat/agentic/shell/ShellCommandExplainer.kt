package com.niki914.zafiro.chat.agentic.shell

/**
 * Local, deterministic explanation for protected shell commands shown in the
 * permission prompt.
 *
 * Explanations intentionally describe only the command category and likely
 * effect. They never copy paths, package names, URLs, tokens, passwords, or
 * other command arguments into a second UI/log string.
 */
object ShellCommandExplainer {
    fun explain(command: String): String {
        val normalized = command.normalizedForExplanation()

        return when {
            normalized.matchesAny("pm install", "cmd package install") ->
                "用于安装应用或应用包。常见用途是安装 APK、更新应用；执行后会改变设备上的已安装应用。"

            normalized.matchesAny("pm uninstall", "cmd package uninstall") ->
                "用于卸载应用。常见用途是移除不需要的应用；执行后可能同时删除该应用的数据。"

            normalized.matchesAny(
                "pm disable",
                "pm disable-user",
                "pm enable",
                "pm suspend",
                "pm unsuspend",
                "pm clear",
                "cmd package",
                "appops",
                "dpm",
            ) ->
                "用于管理应用状态、权限或设备策略。常见用途是启停应用、清除数据或调整权限；可能改变应用能否正常运行。"

            normalized.matchesAny("settings put", "settings delete") ->
                "用于修改 Android 系统设置。常见用途是调整系统行为或开关；错误参数可能影响网络、显示、权限等系统功能。"

            normalized.containsCommand("settings") ->
                "用于读取或操作 Android 系统设置。常见用途是查询或调整系统配置。"

            normalized.containsCommand("setprop") ->
                "用于修改 Android 系统属性。常见用途是改变系统或服务的运行参数；部分修改可能需要重启后才完全生效。"

            normalized.containsCommand("rm") ->
                "用于删除文件或目录。常见用途是清理文件；删除重要数据后可能无法恢复。"

            normalized.containsCommand("mv") ->
                "用于移动或重命名文件、目录。常见用途是整理文件；目标位置或名称错误可能导致应用找不到原文件。"

            normalized.containsAnyCommand("chmod", "chown", "chgrp") ->
                "用于修改文件的权限或所有者。常见用途是修复访问权限；设置不当可能让文件无法访问或扩大访问范围。"

            normalized.matchesAny("am force-stop") ||
                normalized.containsAnyCommand("kill", "pkill", "killall") ->
                "用于停止正在运行的应用或进程。常见用途是结束卡死程序、重启服务；未保存的数据可能丢失。"

            normalized.containsAnyCommand("reboot", "shutdown", "poweroff") ->
                "用于重启或关闭设备。常见用途是让系统变更生效或重新启动设备；会中断当前运行中的任务。"

            normalized.containsAnyCommand("su", "magisk") ->
                "用于请求或使用更高系统权限。常见用途是执行普通应用无权完成的系统操作；获得高权限后命令影响范围会显著扩大。"

            normalized.containsAnyCommand("curl", "wget") ->
                "用于通过网络发送请求、下载或传输数据。常见用途是获取文件或调用网络接口；可能产生外部网络连接和数据传输。"

            normalized.containsAnyCommand("iptables", "ip6tables", "nft") ||
                normalized.matchesAny("ip route", "ip rule") ->
                "用于修改网络路由、防火墙或流量规则。常见用途是控制联网方式；设置错误可能导致部分或全部网络不可用。"

            normalized.containsCommand("mount") ->
                "用于挂载或重新挂载文件系统。常见用途是访问存储或改变挂载方式；对系统分区操作可能影响系统稳定性。"

            else ->
                "这条命令将通过 Shell 在设备上执行。具体作用取决于命令和参数；允许前请确认它是否与当前任务一致。"
        }
    }

    /**
     * A concise worst-case impact for the permission prompt. This intentionally
     * stays category-level and never repeats command arguments or identifiers.
     */
    fun worstCaseImpact(command: String): String {
        val normalized = command.normalizedForExplanation()

        return when {
            normalized.matchesAny("pm uninstall", "pm clear", "cmd package uninstall") ->
                "应用及其本地数据可能被删除，且部分数据无法恢复。"

            normalized.matchesAny("pm install", "cmd package install") ->
                "设备可能安装或更新一个应用，从而获得该应用声明的能力与权限。"

            normalized.matchesAny("pm disable", "pm disable-user", "pm suspend", "appops", "dpm", "cmd package") ->
                "应用可能无法正常运行，或其权限、设备管理能力发生改变。"

            normalized.matchesAny("settings put", "settings delete") || normalized.containsCommand("setprop") ->
                "系统行为可能被改变；错误设置可能造成网络、权限或系统功能异常。"

            normalized.containsCommand("rm") ->
                "目标文件或目录可能永久丢失。"

            normalized.containsCommand("mv") ->
                "文件位置或名称改变后，应用可能无法再找到原数据。"

            normalized.containsAnyCommand("chmod", "chown", "chgrp") ->
                "文件可能变得无法访问，或被授予过大的访问权限。"

            normalized.matchesAny("am force-stop") || normalized.containsAnyCommand("kill", "pkill", "killall") ->
                "正在运行的任务会被强制中断，未保存的数据可能丢失。"

            normalized.containsAnyCommand("reboot", "shutdown", "poweroff") ->
                "设备会中断当前任务并重启或关机。"

            normalized.containsAnyCommand("su", "magisk") ->
                "命令可能获得高权限，从而修改普通应用无法修改的系统数据或设置。"

            normalized.containsAnyCommand("curl", "wget") ->
                "设备可能向外部服务器发送数据或下载内容。"

            normalized.containsAnyCommand("iptables", "ip6tables", "nft") || normalized.matchesAny("ip route", "ip rule") ->
                "网络规则可能被改坏，导致应用或整台设备无法正常联网。"

            normalized.containsCommand("mount") ->
                "文件系统访问方式可能改变，严重时会影响系统或应用稳定性。"

            else ->
                "允许后，这条 Shell 命令会以当前工具权限执行；若参数不符合预期，可能产生不可逆影响。"
        }
    }

    private fun String.normalizedForExplanation(): String =
        lowercase().replace(WHITESPACE_REGEX, " ").trim()

    private fun String.matchesAny(vararg fragments: String): Boolean =
        fragments.any { fragment -> contains(fragment) }

    private fun String.containsAnyCommand(vararg commands: String): Boolean =
        commands.any(::containsCommand)

    private fun String.containsCommand(command: String): Boolean {
        return Regex("(?:^|[\\s;&|()])${Regex.escape(command)}(?:$|[\\s;&|()])")
            .containsMatchIn(this)
    }

    private val WHITESPACE_REGEX = Regex("\\s+")
}
