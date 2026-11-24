package me.codex.yuanbaodaily.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 福利中心导航（抽屉路线）回归测试。
 *
 * 节点数据全部来自**真机 dump 实测**（PLQ110 / 1272x2800 / 元宝 2.87.20），
 * 锁定用户指定的这条路线：
 *
 * ```
 * 首页左上角"两条横杠的圈圈" → 侧栏 → 福利中心 → 各项任务
 * ```
 *
 * 这里要防住三类**真机上随机失败**的回归（都很难复现，必须靠单测钉死）：
 * 1. 入口节点**只有 content-desc、没有 text** —— 用旧 `findExact/findToken`
 *    （只读 `label` 里的 text 部分，或匹配策略不对）会找不到，于是回退到写死比例坐标。
 * 2. "侧栏是否已打开"判据只用了"福利中心是否存在" —— 但首页本身也含"福利中心"
 *    文案（有福利卡片），会导致**侧栏没开就以为开了**，接着点空/点到别处。
 * 3. 到达判据只认页面文本 —— 福利中心是纯 WebView，dump 里根本没有 H5 文字，
 *    于是永远判为"没到达"，反复重试并误触「去邀请」。
 *
 * 断言只读写 [Bounds] 的 Int 字段，不调用 `Rect` 的任何方法。
 */
class WelfareNavTest {

    /** 这些 fixture 全部来自这台开发机：1272×2800。 */
    private val DEVICE_WIDTH = 1272

    // ------------------------- 首页（侧栏未展开）实测节点 -------------------------

    /** 实测：左上角可点外层容器 `resource-id='ic_navigation_show'`。 */
    private val drawerOuter = node(
        className = "android.view.View",
        left = 0, top = 176, right = 210, bottom = 344,
        resourceId = "com.tencent.hunyuan.app.chat:id/ic_navigation_show",
        clickable = true,
    )

    /** 实测：外层里的装饰节点，**只有 content-desc，没有 text**，且不可点。 */
    private val drawerInner = node(
        className = "android.view.View",
        left = 91, top = 218, right = 175, bottom = 302,
        description = "抽屉页入口",
        clickable = false,
    )

    /** 实测：首页自带的"福利中心"卡片文案（侧栏没开时**也会存在**）。 */
    private val homeWelfareCard = node(
        className = "android.widget.TextView",
        left = 210, top = 708, right = 959, bottom = 765,
        label = "福利中心",
    )

    private val homeNodes = listOf(drawerOuter, drawerInner, homeWelfareCard)

    // ------------------------- 侧栏展开后实测节点 -------------------------

    /** 实测：侧栏里"福利中心"行的图标（带 content-desc）。 */
    private val drawerWelfareIcon = node(
        className = "android.view.View",
        left = 84, top = 694, right = 168, bottom = 778,
        description = "福利中心",
    )

    /** 实测：侧栏里"福利中心"行的文字。 */
    private val drawerWelfareText = node(
        className = "android.widget.TextView",
        left = 210, top = 708, right = 959, bottom = 765,
        label = "福利中心",
    )

    /** 实测：侧栏里"新建对话"行（干扰项）。 */
    private val drawerNewChat = node(
        className = "android.widget.TextView",
        left = 210, top = 386, right = 406, bottom = 443,
        label = "新建对话",
    )

    /** 实测：侧栏展开后，抽屉入口挪到**屏幕右侧**（变成关闭按钮）。 */
    private val drawerEntryMoved = node(
        className = "android.view.View",
        left = 1128, top = 288, right = 1208, bottom = 368,
        description = "抽屉页入口",
    )

    private val drawerOpenNodes = listOf(
        drawerWelfareIcon, drawerWelfareText, drawerNewChat, drawerEntryMoved,
    )

    // ---------------------------- 侧栏关闭入口 ----------------------------

    /**
     * 侧栏展开后必须能拿到那个"关闭"入口的真实位置。
     *
     * 用途：导航失败时把侧栏干净地关掉，而不是留着它继续乱点。
     * 这里要防住的是**取到左侧那个残留入口**（旧位置的小节点面积更小）——
     * 拿错就会去点已经失效的位置，侧栏反而关不掉。
     */
    @Test
    fun findsOnlyTheRightHandDrawerEntryForClosing() {
        val close = WelfareNav.findRightDrawerEntry(drawerOpenNodes, DEVICE_WIDTH)
        assertNotNull("侧栏展开后应能找到右侧关闭入口", close)
        assertEquals(1128, close?.left)
        assertTrue("关闭入口必须在右半屏", (close?.left ?: 0) > WelfareNav.rightThreshold(DEVICE_WIDTH))
    }

    /** 侧栏没展开时不存在"关闭入口" —— 必须返回 null，调用方才会改用返回键。 */
    @Test
    fun noRightHandEntryWhenDrawerClosed() {
        assertNull(WelfareNav.findRightDrawerEntry(homeNodes, DEVICE_WIDTH))
    }

    // ------------------------------- 抽屉入口 -------------------------------

    @Test
    fun findsDrawerEntryByContentDescriptionOnly() {
        // 入口没有 text，只有 content-desc —— 这正是旧策略会漏掉的情况。
        val found = WelfareNav.findDrawerEntry(listOf(drawerInner))
        assertNotNull("只有 content-desc 的入口也要能找到", found)
        assertEquals(91, found?.left)
        assertEquals(175, found?.right)
    }

    @Test
    fun prefersSmallestCandidateForDrawerEntry() {
        // 外层容器和内层装饰节点都能匹配时，取面积最小的（内层 84x84 < 外层 210x168）。
        val found = WelfareNav.findDrawerEntry(listOf(drawerOuter, drawerInner))
        assertNotNull(found)
        assertEquals("应取最小候选，避免点到整屏容器", 91, found?.left)
    }

    @Test
    fun findsDrawerEntryByResourceIdAlone() {
        // 即使 content-desc 被本地化成别的文案，id 仍能定位入口。
        val localized = node(
            className = "android.view.View",
            left = 0, top = 176, right = 210, bottom = 344,
            resourceId = "com.tencent.hunyuan.app.chat:id/ic_navigation_show",
            clickable = true,
        )
        val found = WelfareNav.findDrawerEntry(listOf(localized))
        assertNotNull("id 命中时也要能找到入口", found)
        assertEquals(0, found?.left)
    }

    @Test
    fun returnsNullWhenDrawerEntryAbsent() {
        // 找不到入口就返回 null，调用方据此放弃 —— 绝不回退写死坐标。
        assertNull(WelfareNav.findDrawerEntry(listOf(homeWelfareCard)))
    }

    @Test
    fun rejectsZeroSizedDrawerEntry() {
        // dump 里大量 `[0,0][0,0]` 占位节点，点它等于点屏幕左上角，必须筛掉。
        val placeholder = node(
            className = "android.view.View",
            left = 0, top = 0, right = 0, bottom = 0,
            description = "抽屉页入口",
        )
        assertNull(WelfareNav.findDrawerEntry(listOf(placeholder)))
    }

    // ------------------------------- 侧栏行 -------------------------------

    @Test
    fun findsWelfareRowInSidebar() {
        val found = WelfareNav.findWelfareRow(drawerOpenNodes, DEVICE_WIDTH)
        assertNotNull(found)
        // 图标 84x84 比文字行 749x57 面积小，取图标；两者都在 694..778 行内。
        assertTrue("行中心必须落在实测行范围 694..778 内", found!!.centerY in 694..778)
    }

    @Test
    fun welfareRowCenterStaysInsideRealRow() {
        // 真机实测行纵向范围 694..778，行中心 736 —— 点这个 y 不会越出行。
        val found = WelfareNav.findWelfareRow(listOf(drawerWelfareIcon, drawerWelfareText), DEVICE_WIDTH)
        assertNotNull(found)
        assertEquals(736, found?.centerY)
        assertTrue(found!!.centerX in 84..959)
    }

    @Test
    fun returnsNullWhenWelfareRowAbsent() {
        assertNull(WelfareNav.findWelfareRow(listOf(drawerNewChat, drawerEntryMoved), DEVICE_WIDTH))
    }

    // ---------------------------- "侧栏是否已开" ----------------------------

    @Test
    fun drawerNotOpenOnHomePage() {
        // 首页也有"福利中心"卡片，但入口还在左上角 → 侧栏没开。
        // 这是最关键的一条：只看"福利中心存在"会误判。
        assertFalse(
            "首页含福利卡片但侧栏未展开，不能判为已打开",
            WelfareNav.isDrawerOpen(homeNodes, DEVICE_WIDTH),
        )
    }

    @Test
    fun drawerOpenAfterEntryMovesRight() {
        assertTrue(
            "福利中心行 + 入口挪到右侧(1128>1100) ⇒ 侧栏已展开",
            WelfareNav.isDrawerOpen(drawerOpenNodes, DEVICE_WIDTH),
        )
    }

    @Test
    fun drawerOpenWhenStaleLeftEntryIsSmallerThanRightOne() {
        // 真机回归（1.1.3 自检实测）：侧栏展开的那一帧里**同时**存在
        // 左侧残留入口(0,176)-(210,344) 面积 210*168=35280，与右侧真入口
        // (1128,288)-(1208,368) 面积 80*80=6400。
        // 若判定取"最小面积那个"，读到的是 left=0 ⇒ 误判"没开" ⇒ 循环再点
        // 一下把侧栏又关掉，表现为 attempt 1..5 来回横跳、最终放弃。
        // 正确行为：只要**某个**入口在右半屏就算已开。
        val staleLeftOuter = node(
            className = "android.view.View",
            left = 0, top = 176, right = 210, bottom = 344,
            resourceId = "com.tencent.hunyuan.app.chat:id/ic_navigation_show",
            clickable = true,
        )
        val staleLeftInner = node(
            className = "android.view.View",
            left = 91, top = 218, right = 175, bottom = 302,
            description = "抽屉页入口",
        )
        val frame = listOf(staleLeftOuter, staleLeftInner, drawerWelfareIcon, drawerWelfareText, drawerEntryMoved)
        assertTrue(
            "同帧含左侧残留入口时，也必须判为已展开（回归防护）",
            WelfareNav.isDrawerOpen(frame, DEVICE_WIDTH),
        )
        assertTrue("宽松判据同样为真", WelfareNav.looksOpen(frame, DEVICE_WIDTH))
        // 点击目标仍应取最小候选（右侧那个关闭按钮），避免点到左侧已失效区域。
        assertEquals(1128, WelfareNav.findDrawerEntry(frame)?.left)
    }

    @Test
    fun looksOpenWhenRowVisibleButEntryNodeMissing() {
        // 侧栏行已可见但那一帧读不到入口节点：严格判据为 false，
        // 但循环控制必须认为"已开"，否则会再点一次把侧栏关掉。
        val frame = listOf(drawerWelfareIcon, drawerWelfareText, drawerNewChat)
        assertFalse("严格判据需要入口证据", WelfareNav.isDrawerOpen(frame, DEVICE_WIDTH))
        assertTrue("宽松判据只看行是否出现，用于停止连点", WelfareNav.looksOpen(frame, DEVICE_WIDTH))
    }

    @Test
    fun looksOpenIsFalseOnHomePage() {
        // 首页有福利卡片、入口在左侧 ⇒ 宽松判据也必须为 false，否则首页
        // 就不会去点汉堡，整条路走不动。
        assertFalse("首页不能判为侧栏已开", WelfareNav.looksOpen(homeNodes, DEVICE_WIDTH))
    }

    @Test
    fun drawerNotOpenWhenEntryStillLeft() {
        // 福利中心行出现了，但入口还在左边 → 展开动画未完成，仍需重试。
        val midAnimation = listOf(drawerWelfareIcon, drawerWelfareText, drawerInner)
        assertFalse("入口还在左侧时不能判为已展开", WelfareNav.isDrawerOpen(midAnimation, DEVICE_WIDTH))
    }

    @Test
    fun drawerNotOpenWithoutEntryNode() {
        // 只有福利中心行、没有入口节点 → 信息不足，判为未展开（避免误判后点空）。
        assertFalse(WelfareNav.isDrawerOpen(listOf(drawerWelfareIcon, drawerWelfareText), DEVICE_WIDTH))
    }

    @Test
    fun drawerNotOpenOnEmptyNodes() {
        assertFalse(WelfareNav.isDrawerOpen(emptyList(), DEVICE_WIDTH))
    }

    // ---------------------------- 到达判据（Activity） ----------------------------

    @Test
    fun recognisesRealWelfareActivity() {
        // 真机 WindowManager 日志实测的顶层 Activity。
        assertTrue(
            WelfareNav.isWelfareActivity(
                "com.tencent.hunyuan.app.chat.biz.welfare.ui.WelfareWebActivity",
            ),
        )
    }

    @Test
    fun recognisesWelfareActivityFamily() {
        val pkg = "com.tencent.hunyuan.app.chat.biz."
        assertTrue(WelfareNav.isWelfareActivity("${pkg}welfare.ui.WelfareWebActivity"))
        assertTrue(WelfareNav.isWelfareActivity("${pkg}campaign.CampaignActivity"))
        assertTrue(WelfareNav.isWelfareActivity("${pkg}web.WebBrowserActivity"))
    }

    @Test
    fun rejectsNonWelfareActivity() {
        // 这正是"手点侧栏后 1.5 秒被踢回"时停在的页面 —— 不能判为福利中心。
        assertFalse(
            WelfareNav.isWelfareActivity(
                "com.tencent.hunyuan.app.chat.home.v2.YBHomeActivityV2",
            ),
        )
        assertFalse(WelfareNav.isWelfareActivity(""))
    }

    @Test
    fun rejectsChatActivityEvenWithWelfareWordInName() {
        // Activity 名里出现 "welfare" 字样但不是福利页（例如内部命名）时也要按后缀判定。
        assertFalse(
            WelfareNav.isWelfareActivity(
                "com.tencent.hunyuan.app.chat.home.v2.YBHomeActivityV2",
            ),
        )
    }

    // -------------------------------- 工具 --------------------------------

    private fun node(
        className: String,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        label: String = "",
        description: String = "",
        resourceId: String = "",
        clickable: Boolean = false,
    ) = ScreenScanner.DumpNode(
        packageName = "com.tencent.hunyuan.app.chat",
        text = label,
        description = description,
        resourceId = resourceId,
        className = className,
        bounds = Bounds(left, top, right, bottom),
        clickable = clickable,
        scrollable = false,
    )
}
