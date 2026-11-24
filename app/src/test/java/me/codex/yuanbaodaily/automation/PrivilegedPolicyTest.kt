package me.codex.yuanbaodaily.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 特权通道选择的回归测试。
 *
 * 这一层判断错了的两种后果都很难从真机日志看出来：
 * - 明明能用却报"不可用" → 用户以为自愈坏了，白白去做 adb 授权；
 * - 该用 Shizuku 时去碰一次 `SecurityException` → 自愈静默失败，
 *   而日志里只有一句"写入失败"，看不出是权限问题还是别的问题。
 */
class PrivilegedPolicyTest {

    // --------------------------- 通道选择 ---------------------------

    /** 有 WRITE_SECURE_SETTINGS 就直接写：一次 binder 调用，比 fork 一个 sh 快一个量级。 */
    @Test
    fun directPermissionWinsEvenWithShizukuReady() {
        val channel = PrivilegedPolicy.decide(
            hasDirectPermission = true,
            shizukuInstalled = true,
            shizukuAlive = true,
            shizukuGranted = true,
        )
        assertEquals(PrivilegedPolicy.Channel.DIRECT, channel)
    }

    /** 没有直接权限、Shizuku 三态齐全 → 走 Shizuku。这是免 root 版的目标路径。 */
    @Test
    fun fallsBackToShizukuWhenNoDirectPermission() {
        val channel = PrivilegedPolicy.decide(
            hasDirectPermission = false,
            shizukuInstalled = true,
            shizukuAlive = true,
            shizukuGranted = true,
        )
        assertEquals(PrivilegedPolicy.Channel.SHIZUKU, channel)
    }

    /** 什么都没装 → 明确不可用（调用方据此给出"装 Shizuku 或 adb"的指引）。 */
    @Test
    fun noneWhenNothingAvailable() {
        val channel = PrivilegedPolicy.decide(
            hasDirectPermission = false,
            shizukuInstalled = false,
            shizukuAlive = false,
            shizukuGranted = false,
        )
        assertEquals(PrivilegedPolicy.Channel.NONE, channel)
    }

    /** 装了但没启动：`newProcess` 会抛异常，必须提前判掉。 */
    @Test
    fun notAliveIsNotUsable() {
        val channel = PrivilegedPolicy.decide(
            hasDirectPermission = false,
            shizukuInstalled = true,
            shizukuAlive = false,
            shizukuGranted = false,
        )
        assertEquals(PrivilegedPolicy.Channel.NONE, channel)
    }

    /** 在跑但本应用没授权：同样不能调，否则 Shizuku 会直接拒绝。 */
    @Test
    fun notGrantedIsNotUsable() {
        val channel = PrivilegedPolicy.decide(
            hasDirectPermission = false,
            shizukuInstalled = true,
            shizukuAlive = true,
            shizukuGranted = false,
        )
        assertEquals(PrivilegedPolicy.Channel.NONE, channel)
    }

    // --------------------------- 失败说明 ---------------------------

    /**
     * 三种"不可用"必须给出**不同的**下一步。
     *
     * 都写成"不可用"的话，用户看到的就是一句没法行动的话 ——
     * 而这三件事该做的事完全不同（装软件 / 启动软件 / 点允许）。
     */
    @Test
    fun explanationsDifferByCause() {
        val notInstalled = PrivilegedPolicy.explain(
            PrivilegedPolicy.Channel.NONE,
            shizukuInstalled = false,
            shizukuAlive = false,
            shizukuGranted = false,
            packageName = "me.codex.yuanbaodaily.lite",
        )
        val notAlive = PrivilegedPolicy.explain(
            PrivilegedPolicy.Channel.NONE,
            shizukuInstalled = true,
            shizukuAlive = false,
            shizukuGranted = false,
            packageName = "me.codex.yuanbaodaily.lite",
        )
        val notGranted = PrivilegedPolicy.explain(
            PrivilegedPolicy.Channel.NONE,
            shizukuInstalled = true,
            shizukuAlive = true,
            shizukuGranted = false,
            packageName = "me.codex.yuanbaodaily.lite",
        )

        assertTrue("没装时应提到 Shizuku", notInstalled.contains("Shizuku"))
        assertTrue("没装时应给出 adb 命令", notInstalled.contains("pm grant"))
        assertTrue("没启动时应说清服务没在运行", notAlive.contains("没在运行"))
        assertTrue("没授权时应指向应用内按钮", notGranted.contains("授权"))
        // 三条互不相同：说明它们确实分别对应不同的用户动作。
        assertTrue(notInstalled != notAlive && notAlive != notGranted)
    }

    /** 可用时要说明走的是哪条路，便于日志里区分。 */
    @Test
    fun usableExplanationsNameTheChannel() {
        assertTrue(
            PrivilegedPolicy.explain(
                PrivilegedPolicy.Channel.DIRECT,
                shizukuInstalled = false,
                shizukuAlive = false,
                shizukuGranted = false,
                packageName = "x",
            ).contains("WRITE_SECURE_SETTINGS"),
        )
        assertTrue(
            PrivilegedPolicy.explain(
                PrivilegedPolicy.Channel.SHIZUKU,
                shizukuInstalled = true,
                shizukuAlive = true,
                shizukuGranted = true,
                packageName = "x",
            ).contains("Shizuku"),
        )
    }

    // --------------------------- shell 转义 ---------------------------

    /**
     * 无障碍服务列表的真实值：冒号分隔的组件名。
     *
     * 必须原样传递 —— 这里少一个字符就会让系统解绑服务，比报错更难查。
     */
    @Test
    fun quotesRealAccessibilityServiceList() {
        val value = "com.omarea.vtools/.AccessibilitySceneMode:" +
            "me.codex.yuanbaodaily.lite/me.codex.yuanbaodaily.automation.AutomationAccessibilityService"
        assertEquals("'$value'", PrivilegedPolicy.shellQuote(value))
    }

    /**
     * 含单引号时必须转义成 `'\''`。
     *
     * 不转义的话，值在第一个 `'` 处就被截断，`settings put` 会把半截字符串写进系统设置
     * —— 无障碍列表直接损坏，而命令的退出码仍然是 0（看起来"成功"）。
     */
    @Test
    fun escapesSingleQuote() {
        assertEquals("'a'\\''b'", PrivilegedPolicy.shellQuote("a'b"))
    }

    /** 空格、分号、反引号都在单引号里失效，不需要额外处理。 */
    @Test
    fun singleQuotesNeutraliseShellMetacharacters() {
        val value = "a b; rm -rf / `id` \$HOME"
        assertEquals("'$value'", PrivilegedPolicy.shellQuote(value))
    }

    /** 空值也要能安全传（有些 ROM 用它来清空设置）。 */
    @Test
    fun quotesEmptyValue() {
        assertEquals("''", PrivilegedPolicy.shellQuote(""))
    }
}
