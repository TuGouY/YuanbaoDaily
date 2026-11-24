package me.codex.yuanbaodaily.automation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **多分辨率沙箱**：把真机 dump 按不同屏幕宽度缩放后，重跑同一套导航判定。
 *
 * ## 为什么要这个沙箱
 *
 * 这套自动化最初是照着开发机（PLQ110，**1272×2800**）调的。真机换成**小米 14 Pro
 * （1080×2400）**后，用户报"点了左上角抽屉，进不去福利中心，然后开始瞎点"。
 * 根因就是判定里写了绝对像素：
 *
 * ```kotlin
 * const val RIGHT_HALF_X = 1100   // ← 在 1272 宽上是"右半屏"，在 1080 宽上永远不成立
 * ```
 *
 * 侧栏展开后，关闭按钮（= 挪到右侧的抽屉入口）在 1272 宽机型上 `left=1128`，
 * 在 1080 宽机型上约 `left≈958`。`958 < 1100` →「侧栏已展开」恒判 false →
 * 点入口的循环反复开/关侧栏 5 次 → 判定失败 → 掉进旧的坐标兜底乱点。
 *
 * ## 这个测试怎么模拟
 *
 * 元宝的界面是 dp 布局，同一套 UI 在不同分辨率下**按比例缩放**。
 * 所以把 1272 宽的真机坐标乘以 `目标宽度 / 1272`，就得到该分辨率下应有的节点矩形。
 * 判定函数必须对**每一个**宽度都给出同样的结论。
 *
 * 覆盖：1080（小米 14 Pro）、1272（开发机）、1440（2K 屏）。
 */
class WelfareNavResolutionTest {

    /** 开发机基准宽度 —— 所有 fixture 坐标都是这个宽度下量到的。 */
    private val baseWidth = 1272

    private data class Case(val label: String, val width: Int)

    private val cases = listOf(
        Case("小米 14 Pro 1080×2400", 1080),
        Case("开发机 1272×2800", 1272),
        Case("2K 屏 1440×3200", 1440),
    )

    // ------------------------------ 基准 fixture（1272 宽） ------------------------------

    private fun node(
        left: Int, top: Int, right: Int, bottom: Int,
        label: String = "",
        description: String = "",
        resourceId: String = "",
        clickable: Boolean = false,
    ) = ScreenScanner.DumpNode(
        packageName = "com.tencent.hunyuan.app.chat",
        text = label,
        description = description,
        resourceId = resourceId,
        className = "android.view.View",
        bounds = Bounds(left, top, right, bottom),
        clickable = clickable,
        scrollable = false,
    )

    /** 侧栏**未展开**：入口在左上角，首页自带一张"福利中心"卡片。 */
    private val closedNodes = listOf(
        node(91, 218, 175, 302, description = "抽屉页入口", resourceId = "ic_navigation_show"),
        node(210, 708, 959, 765, label = "福利中心"),
    )

    /** 侧栏**展开**：福利中心行 + 挪到右侧的关闭入口。 */
    private val openNodes = listOf(
        node(84, 694, 168, 778, description = "福利中心"),
        node(210, 708, 959, 765, label = "福利中心"),
        node(210, 386, 406, 443, label = "新建对话"),
        node(1128, 288, 1208, 368, description = "抽屉页入口"),
    )

    /** 动画中间帧：行已经可见，但入口还在左边。 */
    private val midAnimationNodes = listOf(
        node(210, 708, 959, 765, label = "福利中心"),
        node(91, 218, 175, 302, description = "抽屉页入口"),
    )

    /** 按目标宽度等比缩放（dp 布局在不同分辨率下就是等比）。 */
    private fun scale(nodes: List<ScreenScanner.DumpNode>, width: Int): List<ScreenScanner.DumpNode> {
        if (width == baseWidth) return nodes
        fun s(v: Int): Int = Math.round(v.toFloat() * width / baseWidth)
        return nodes.map { n ->
            n.copy(bounds = Bounds(s(n.bounds.left), s(n.bounds.top), s(n.bounds.right), s(n.bounds.bottom)))
        }
    }

    // ------------------------------ 每个分辨率都要成立 ------------------------------

    @Test
    fun drawerOpenIsDetectedOnEveryResolution() {
        cases.forEach { case ->
            val nodes = scale(openNodes, case.width)
            assertTrue(
                "${case.label}: 侧栏展开必须能被识别（旧写法 RIGHT_HALF_X=1100 在这里会失败）",
                WelfareNav.isDrawerOpen(nodes, case.width),
            )
            assertTrue(
                "${case.label}: 宽松判据（用于停止连点）同样要为真",
                WelfareNav.looksOpen(nodes, case.width),
            )
        }
    }

    @Test
    fun homePageIsNotMistakenForOpenDrawerOnEveryResolution() {
        cases.forEach { case ->
            val nodes = scale(closedNodes, case.width)
            assertFalse(
                "${case.label}: 首页（含福利卡片文案）不能判成侧栏已开",
                WelfareNav.isDrawerOpen(nodes, case.width),
            )
            assertFalse(case.label, WelfareNav.looksOpen(nodes, case.width))
        }
    }

    @Test
    fun midAnimationFrameIsNotTreatedAsOpen() {
        cases.forEach { case ->
            val nodes = scale(midAnimationNodes, case.width)
            assertFalse(
                "${case.label}: 入口还在左侧时不能判为已展开",
                WelfareNav.isDrawerOpen(nodes, case.width),
            )
        }
    }

    @Test
    fun welfareRowIsFoundAndLandableOnEveryResolution() {
        cases.forEach { case ->
            val nodes = scale(openNodes, case.width)
            val row = WelfareNav.findWelfareRow(nodes, case.width)
            assertNotNull("${case.label}: 必须能找到侧栏里的福利中心行", row)
            assertTrue(
                "${case.label}: 行的中心点必须落在屏幕内（x=${row!!.centerX}, w=${case.width}）",
                row.centerX in 0 until case.width && row.centerY > 0,
            )
        }
    }

    @Test
    fun closeEntryIsFoundInsideTheScreenOnEveryResolution() {
        cases.forEach { case ->
            val nodes = scale(openNodes, case.width)
            val close = WelfareNav.findRightDrawerEntry(nodes, case.width)
            assertNotNull("${case.label}: 必须能拿到侧栏关闭入口", close)
            assertTrue(
                "${case.label}: 关闭入口的 left 必须在阈值右侧且在屏幕内",
                close!!.left > WelfareNav.rightThreshold(case.width) && close.left < case.width,
            )
        }
    }

    // --------------------- 把"旧写法为什么错"钉死（防止有人改回去） ---------------------

    /**
     * 1080 宽机型上：真实的关闭按钮 `left` 约 958，
     * **小于**旧常量 1100 —— 所以旧写法在该分辨率下必然判"侧栏没开"。
     * 同时新阈值（0.72W=778）必须**小于** 958，判据才有意义。
     *
     * 这一条同时锁住两端：既证明旧写法是错的，也证明新阈值留了足够余量。
     */
    @Test
    fun theOldAbsoluteThresholdWasWrongAt1080Width() {
        val width = 1080
        val close = WelfareNav.findRightDrawerEntry(scale(openNodes, width), width)
        assertNotNull(close)
        val realLeft = close!!.left
        assertTrue("前提：1080 宽下关闭按钮 left($realLeft) 应小于旧常量 1100", realLeft < 1100)
        assertTrue(
            "新阈值(${WelfareNav.rightThreshold(width)}) 必须小于真实位置($realLeft)",
            WelfareNav.rightThreshold(width) < realLeft,
        )
        // 余量断言：阈值与真实位置之间至少留出 10% 屏宽，避免边缘抖动。
        assertTrue(
            "阈值与真实位置之间的余量太小",
            realLeft - WelfareNav.rightThreshold(width) > width * 0.10f,
        )
    }

    /** 所有分辨率下，阈值都必须落在屏幕内且给右侧入口留位置。 */
    @Test
    fun thresholdAlwaysLandsInsideTheScreen() {
        cases.forEach { case ->
            val t = WelfareNav.rightThreshold(case.width)
            assertTrue("${case.label}: 阈值必须 > 0", t > 0)
            assertTrue("${case.label}: 阈值必须小于屏宽", t < case.width)
        }
    }

    /** 读不到屏幕宽度时退化成参考宽度，不能把整条路线判死。 */
    @Test
    fun unknownScreenWidthFallsBackInsteadOfFailing() {
        assertTrue(
            "读不到宽度时要退化成 1272 的参考值，而不是判定失败",
            WelfareNav.isDrawerOpen(openNodes, 0),
        )
        assertNull(WelfareNav.findRightDrawerEntry(closedNodes, 0))
    }
}
