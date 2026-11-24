package me.codex.yuanbaodaily.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `enabled_accessibility_services` 改写规则的回归测试。
 *
 * 为什么值得单测：真机上这条设置字符串写错，结果是**无障碍列表里的开关直接消失**
 * （比服务掉线更难恢复，用户只能重新去设置里勾）。而自愈的核心动作恰恰就是
 * "摘掉 → 装回"两次改写，所以这两步必须证明是**保序、保内容、幂等**的。
 *
 * 下面用到的真机值来自本机（ColorOS，PLQ110）实测：
 * ```
 *     com.omarea.vtools/.AccessibilitySceneMode:me.codex.yuanbaodaily/.automation.AutomationAccessibilityService
 * ```
 */
class AccessibilityServiceListTest {

    private val ours =
        "me.codex.yuanbaodaily/me.codex.yuanbaodaily.automation.AutomationAccessibilityService"
    private val others = "com.omarea.vtools/.AccessibilitySceneMode"

    @Test
    fun parsesRealDeviceValue() {
        val raw = "$others:$ours"
        assertEquals(listOf(others, ours), AccessibilityServiceList.parse(raw))
    }

    @Test
    fun parseToleratesBlankAndEmptyEntries() {
        assertEquals(listOf(others, ours), AccessibilityServiceList.parse(" $others : : $ours :"))
        assertEquals(emptyList<String>(), AccessibilityServiceList.parse(null))
        assertEquals(emptyList<String>(), AccessibilityServiceList.parse(""))
        assertEquals(emptyList<String>(), AccessibilityServiceList.parse("   "))
    }

    @Test
    fun parseDeduplicates() {
        assertEquals(listOf(others, ours), AccessibilityServiceList.parse("$others:$ours:$others"))
    }

    @Test
    fun withoutRemovesOnlyOurEntryAndKeepsOrder() {
        val raw = "$others:$ours"
        assertEquals(others, AccessibilityServiceList.without(raw, ours))
        // 顺序不能变：系统按列表顺序决定优先级，重排会影响别人的服务。
        assertEquals("$ours:b/c", AccessibilityServiceList.without("$others:$ours:b/c", others))
    }

    @Test
    fun withoutIsNoOpWhenAbsent() {
        assertEquals(others, AccessibilityServiceList.without(others, ours))
        assertEquals("", AccessibilityServiceList.without(null, ours))
    }

    @Test
    fun withComponentAppendsWhenMissing() {
        assertEquals("$others:$ours", AccessibilityServiceList.withComponent(others, ours))
    }

    @Test
    fun withComponentIsIdempotent() {
        val once = AccessibilityServiceList.withComponent(others, ours)
        val twice = AccessibilityServiceList.withComponent(once, ours)
        assertEquals(once, twice)
        assertEquals(1, AccessibilityServiceList.parse(twice).count { it == ours })
    }

    @Test
    fun withComponentOnEmptyListYieldsOnlyUs() {
        assertEquals(ours, AccessibilityServiceList.withComponent("", ours))
        assertEquals(ours, AccessibilityServiceList.withComponent(null, ours))
    }

    /** 自愈的核心动作：摘掉再装回，必须**完全复原**原字符串。 */
    @Test
    fun removeThenAddRoundTripsToOriginal() {
        val raw = "$others:$ours"
        val restored = AccessibilityServiceList.withComponent(
            AccessibilityServiceList.without(raw, ours),
            ours,
        )
        assertEquals(raw, restored)
    }

    @Test
    fun containsMatchesRealValue() {
        assertTrue(AccessibilityServiceList.contains("$others:$ours", ours))
        assertFalse(AccessibilityServiceList.contains(others, ours))
        assertFalse(AccessibilityServiceList.contains(null, ours))
    }

    /** 我们自己永远不会被"部分匹配"命中 —— 组件名必须整段相等。 */
    @Test
    fun containsDoesNotPartiallyMatch() {
        val prefix = "me.codex.yuanbaodaily/me.codex.yuanbaodaily.automation"
        assertFalse(AccessibilityServiceList.contains(prefix, ours))
    }

    @Test
    fun joinNormalizesInput() {
        assertEquals("$others:$ours", AccessibilityServiceList.join(listOf(" $others ", "", ours, others)))
        assertEquals("", AccessibilityServiceList.join(emptyList()))
    }
}
