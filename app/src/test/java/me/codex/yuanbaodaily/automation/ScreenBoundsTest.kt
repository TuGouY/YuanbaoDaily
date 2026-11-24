package me.codex.yuanbaodaily.automation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ScreenBounds] 的回归测试。
 *
 * 全部用例都用**真机 `evidence/trace_e3_ask` 的原始读数**（元宝 2.87.20 / PLQ110 /
 * 1272×2800），而不是编造的坐标 —— 这样一旦有人在几何上"优化"出偏差，测试会直接红。
 */
class ScreenBoundsTest {

    /** 本机物理屏幕（`wm size` 实测）。 */
    private val w = 1272
    private val h = 2800

    @Test
    fun normalComposerPointIsInsideTheScreen() {
        // 正常帧：首页空输入框 [210,2435][922,2645]，点它的中心。
        assertTrue(ScreenBounds.containsPoint(566, 2540, w, h))
        assertTrue(ScreenBounds.containsBox(210, 2435, 922, 2645, w, h))
    }

    @Test
    fun normalChatPageSendPointIsInsideTheScreen() {
        // 对话页有文案时真正的发送 ⬆ ＝ [1027,2456][1195,2624]，落点 1111,2540。
        assertTrue(ScreenBounds.containsBox(1027, 2456, 1195, 2624, w, h))
        assertTrue(ScreenBounds.containsPoint(1111, 2540, w, h))
    }

    @Test
    fun emptyStateRightContainerTouchingTheScreenEdgeIsStillInside() {
        // trace_d7 空框帧里右侧容器正好贴着右边缘 [1118,2456][1272,2624]。
        // `right == screenWidth` 必须算"在屏幕内"（半开区间），否则正常帧会被误拦。
        assertTrue(ScreenBounds.containsBox(1118, 2456, 1272, 2624, w, h))
    }

    @Test
    fun drawerOpenSendPointIsRejectedBecauseItIsOffScreen() {
        // **本命用例**：侧栏展开时 trace_e3_ask 的真实读数，落点 x=2087 超出 1272。
        assertFalse(ScreenBounds.containsPoint(2087, 2471, w, h))
        assertFalse("发送按钮整块在屏幕外", ScreenBounds.containsBox(2008, 2392, 2167, 2551, w, h))
    }

    @Test
    fun drawerOpenEditorIsRejectedEvenThoughItPartlyOverlapsTheScreen() {
        // 输入框 [1108,2274][1871,2571] 与屏幕**是相交的**（左边 164px 还看得见），
        // 所以判据必须是"完整包含"，用"相交"根本发现不了问题。
        assertFalse(ScreenBounds.containsBox(1108, 2274, 1871, 2571, w, h))
        // 但它的左上角那一点确实在屏幕内 —— 这正是"只看点、不看框"会漏判的地方。
        assertTrue(ScreenBounds.containsPoint(1108, 2274, w, h))
    }

    @Test
    fun negativeCoordinatesAreRejected() {
        assertFalse(ScreenBounds.containsPoint(-1, 100, w, h))
        assertFalse(ScreenBounds.containsPoint(100, -1, w, h))
        assertFalse(ScreenBounds.containsBox(-5, 0, 100, 100, w, h))
    }

    @Test
    fun pointsOnOrBeyondTheFarEdgeAreRejected() {
        assertFalse("右下角之外", ScreenBounds.containsPoint(w, h, w, h))
        assertFalse(ScreenBounds.containsPoint(0, h, w, h))
        assertFalse(ScreenBounds.containsPoint(w, 0, w, h))
    }

    @Test
    fun unknownScreenSizeDisablesTheGuardInsteadOfBlockingEverything() {
        // 拿不到尺寸时必须**放行**：宁可退化成旧行为，也不能把正常点击全拦掉
        // （那会让整个模块彻底不可用，是比漏拦严重得多的故障）。
        assertTrue(ScreenBounds.containsPoint(99999, 99999, 0, 0))
        assertTrue(ScreenBounds.containsBox(0, 0, 99999, 99999, 0, 0))
        assertTrue(ScreenBounds.containsPoint(99999, 99999, -1, -1))
    }
}
