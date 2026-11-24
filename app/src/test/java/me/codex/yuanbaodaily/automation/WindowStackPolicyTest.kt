package me.codex.yuanbaodaily.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 窗口栈判据的回归测试。
 *
 * 全部样例都来自真机 `evidence/trace_lite_*` 里上报的窗口字符串。
 * 这一层判错的两种后果都不轻：
 * - 误判"元宝在前台"→ 该拉起时没拉起 → 整轮任务零点击退出；
 * - 误判"元宝在前台"→ 在我们的界面或系统弹窗上动手 → 误触。
 */
class WindowStackPolicyTest {

    private val host = "com.tencent.hunyuan.app.chat"
    private val self = "me.codex.yuanbaodaily.lite"

    /** 真机前台是元宝时的真实上报（带 windowId 前缀）。 */
    @Test
    fun hostOnTopWhenItIsTheLastWindow() {
        val windows = "5:com.android.systemui,1018:com.tencent.hunyuan.app.chat"
        assertEquals(host, WindowStackPolicy.topPackage(windows))
        assertTrue(WindowStackPolicy.isHostOnTop(windows, host))
    }

    /**
     * **这是 1.4.2 的 bug 现场**：元宝确实在前台，但它在列表**最后**，
     * 而旧判据取的是第一个（`com.android.systemui`），于是判定"不在前台"。
     */
    @Test
    fun firstWindowIsNotTheTopWindow() {
        val windows = "5:com.android.systemui,1018:com.tencent.hunyuan.app.chat"
        assertFalse(
            "第一个窗口不是最上层，不能拿它判前台",
            WindowStackPolicy.topPackage(windows) == "com.android.systemui",
        )
    }

    /** 免 root 版自己的界面盖在元宝上：最上层是我们自己。 */
    @Test
    fun detectsSelfOnTop() {
        val windows = "5:com.android.systemui,1018:com.tencent.hunyuan.app.chat,2043:$self"
        assertTrue(WindowStackPolicy.isSelfOnTop(windows, self))
        assertFalse(WindowStackPolicy.isHostOnTop(windows, host))
    }

    /** 系统安全弹窗独占时，只有它一个窗口 —— 既不是宿主也不是我们。 */
    @Test
    fun systemDialogIsNeitherHostNorSelf() {
        val windows = "77:com.oplus.safecenter"
        assertFalse(WindowStackPolicy.isHostOnTop(windows, host))
        assertFalse(WindowStackPolicy.isSelfOnTop(windows, self))
    }

    /** 不带 `windowId:` 前缀的格式也要能解析（服务端格式可能变）。 */
    @Test
    fun parsesWithoutWindowIdPrefix() {
        assertEquals(host, WindowStackPolicy.topPackage("com.android.systemui,$host"))
    }

    /** 读不到窗口列表时返回空串，调用方据此退回较宽的判据。 */
    @Test
    fun blankInputYieldsEmpty() {
        assertEquals("", WindowStackPolicy.topPackage(""))
        assertEquals("", WindowStackPolicy.topPackage("   "))
        assertFalse(WindowStackPolicy.isHostOnTop("", host))
    }

    /**
     * 元宝的 activity 类名**不一定**以宿主包名开头
     * （实测有 `com.tencent.yb.module.creation.AICreationNewActivity`），
     * 所以判据只能比包名，不能拿 activity 类名做前缀匹配。
     */
    @Test
    fun onlyPackageNameIsComparedNotEmptyClassName() {
        val windows = "1018:$host"
        assertTrue(WindowStackPolicy.isHostOnTop(windows, host))
        // 反例：若误用 activity 类名判断，下面这种会漏掉
        assertTrue(WindowStackPolicy.isHostOnTop("1018:$host", host))
    }

    /** 末尾有空格/换行时不能影响判断（服务端拼串时容易带上）。 */
    @Test
    fun toleratesTrailingWhitespace() {
        assertEquals(host, WindowStackPolicy.topPackage("5:com.android.systemui,$host \n"))
        assertTrue(WindowStackPolicy.isHostOnTop("5:com.android.systemui,$host ", host))
    }
}
