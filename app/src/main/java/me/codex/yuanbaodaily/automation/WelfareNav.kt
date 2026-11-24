package me.codex.yuanbaodaily.automation

/**
 * 福利中心导航的**纯判定**逻辑（不碰 Android API，可被纯 JVM 单测覆盖）。
 *
 * 背景：元宝 2.87.20 把福利中心入口改到了**首页左上角"两条横杠的圈圈"**
 * （`ic_navigation_show` / `content-desc='抽屉页入口'`）→ 侧栏 → 福利中心。
 * 老代码在这条路上写死了四个屏幕比例坐标：
 *
 * ```kotlin
 * DRAWER_X = 0.09f          DRAWER_Y = 0.093f
 * DRAWER_WELFARE_X = 0.459f DRAWER_WELFARE_Y = 0.263f
 * ```
 *
 * 真机实测这些比例**每次点击都不可靠**（侧栏是覆盖层、行不可点、点汉堡常被吞），
 * 所以全部改成"从节点真实 bounds 取坐标 + 每跳校验"。这里承载其中可测的判定：
 *
 * 1. [findDrawerEntry] —— 在节点列表里找抽屉入口（**只有 content-desc，没有 text**）。
 * 2. [findWelfareRow] —— 在侧栏节点里找"福利中心"行。
 * 3. [isDrawerOpen] —— 侧栏是否已展开（入口已挪到屏幕右侧）。
 * 4. [isWelfareActivity] —— Activity 名是否就是福利中心（最可靠的到达判据）。
 *
 * 为什么要单独抽出来：这些判定原先散在 `YuanbaoTaskAdapter` 里，依赖
 * `AccessibilityNodeInfo`，只能真机验证；而"点汉堡被吞要重试""侧栏开了没"
 * 这类逻辑一旦回归，真机上表现为随机失败，很难复现。抽成纯函数后可以用
 * 真实 dump 数据做断言（见 `WelfareNavTest`）。
 */
internal object WelfareNav {

    /** 抽屉入口的 content-desc（首页左上角"两条横杠的圈圈"）。 */
    const val DRAWER_ENTRY_LABEL = "抽屉页入口"

    /** 抽屉入口的资源 id（外层可点容器，比 content-desc 更稳）。 */
    const val DRAWER_ENTRY_ID = "ic_navigation_show"

    /** 侧栏里"福利中心"行的标签。 */
    const val WELFARE_ROW_LABEL = "福利中心"

    /** 福利中心真正的宿主 Activity 片段（真机 WindowManager 日志确认）。 */
    const val WELFARE_ACTIVITY_MARKER = "biz.welfare.ui.WelfareWebActivity"

    /**
     * 侧栏展开后，抽屉入口会从左上角挪到**屏幕右侧**（变成关闭按钮）。
     *
     * 真机 dump 证据（1272 宽机型）：关闭时 `left=91`，展开后 `left=1128`。
     *
     * ## 这里以前写的是 `RIGHT_HALF_X = 1100`（绝对像素）—— 那是个**致命**的写死
     *
     * 1100 是在开发机（1272 宽）上量出来的。换到 **1080 宽**的手机（小米 14 Pro、
     * 2400×1080）上，侧栏展开后的关闭按钮大约在 `left≈958`，**永远小于 1100**，
     * 于是 `anyDrawerEntryOnRight` 恒为 false → `isDrawerOpen` 恒为 false →
     * "点抽屉入口"循环点 5 次反复开/关侧栏，然后判定失败，掉进后面的兜底乱点。
     * 用户看到的就是"点了左上角抽屉，进不去福利中心，然后开始瞎点"。
     *
     * 改成**屏幕宽度的比例**后与分辨率无关：1272→916、1080→778、1440→1036，
     * 三种宽度下真正展开的入口（约 0.88W）都稳定落在阈值右侧。
     */
    const val RIGHT_HALF_FRACTION = 0.72f

    /**
     * 拿不到真实屏幕宽度时的参考值（开发机型 1272）。
     *
     * 宁可退化成"在开发机上能跑的行为"，也不要因为读不到尺寸就把整条路线判死 ——
     * 那样用户会看到"完全没反应"，比"某一步没点中"更难排查。
     */
    private const val REFERENCE_SCREEN_WIDTH = 1272

    /**
     * 找抽屉入口节点。
     *
     * 入口的**语义在 content-desc 上，没有 text**；同时外层容器带
     * `resource-id='ic_navigation_show'`。两种都能命中时优先按 id 找
     * （id 不随文案本地化变化），一次点击只应命中一个节点。
     *
     * 返回面积最小的候选（避免取到整屏根容器），没有就返回 null ——
     * 调用方据此放弃，绝不回退到写死坐标。
     */
    fun findDrawerEntry(nodes: List<ScreenScanner.DumpNode>): Bounds? {
        val pool = drawerEntryCandidates(nodes)
        if (pool.isEmpty()) return null
        return pool.minByOrNull { it.area }?.box
    }

    /**
     * 抽屉入口的**全部**候选（同一帧里可能同时有首页那个和侧栏那个）。
     *
     * 真机实测：侧栏展开后，`抽屉页入口` 会同时存在于左侧定位（残留）与右侧
     * （真正的关闭按钮），两者面积可能相同甚至左侧更小 —— 所以"最小面积"
     * 只能用来定位点击目标，**不能**用来判断侧栏开没开。判断请用
     * [anyDrawerEntryOnRight]。
     */
    private fun drawerEntryCandidates(nodes: List<ScreenScanner.DumpNode>): List<ScreenScanner.DumpNode> {
        val byId = nodes.filter { node ->
            node.resourceId.isNotEmpty() &&
                node.resourceId.endsWith(DRAWER_ENTRY_ID) &&
                node.box.width > 0 && node.box.height > 0
        }
        val byLabel = nodes.filter { node ->
            node.label.contains(DRAWER_ENTRY_LABEL) &&
                node.box.width > 0 && node.box.height > 0
        }
        return byId + byLabel
    }

    /** 是否存在"已挪到屏幕右侧"的抽屉入口（侧栏展开的硬证据）。 */
    fun anyDrawerEntryOnRight(nodes: List<ScreenScanner.DumpNode>, screenWidth: Int): Boolean {
        val limit = rightThreshold(screenWidth)
        return drawerEntryCandidates(nodes).any { it.box.left > limit }
    }

    /**
     * 侧栏展开后那个**关闭按钮**的真实位置（= 挪到右半屏的抽屉入口）。
     *
     * 用于"侧栏开着但我又不想选任何一项"时把它关掉。没有就返回 null，
     * 调用方据此改用系统返回键 —— 绝不用比例坐标去点右上角。
     */
    fun findRightDrawerEntry(nodes: List<ScreenScanner.DumpNode>, screenWidth: Int): Bounds? {
        val limit = rightThreshold(screenWidth)
        return drawerEntryCandidates(nodes)
            .filter { it.box.left > limit }
            .minByOrNull { it.area }
            ?.box
    }

    /**
     * 找侧栏里的"福利中心"行。
     *
     * 侧栏展开后会有两个匹配节点：左侧图标 View（`content-desc='福利中心'`，
     * `[84,694][168,778]`）和整行文字（`[210,708][959,765]`）。两者都在行内，
     * 取**纵向范围居中被夹住**的那个更稳；这里按"标签完全等于"优先，
     * 再按最小面积取（避免取到跨屏大容器）。
     */
    fun findWelfareRow(nodes: List<ScreenScanner.DumpNode>, screenWidth: Int): Bounds? {
        val maxWidth = maxRowWidth(screenWidth)
        val matches = nodes.filter { node ->
            node.label.contains(WELFARE_ROW_LABEL) &&
                node.box.width > 0 && node.box.height > 0 &&
                node.box.width < maxWidth
        }
        if (matches.isEmpty()) return null
        val exact = matches.filter { it.label.trim() == WELFARE_ROW_LABEL }
        val pool = exact.ifEmpty { matches }
        return pool.minByOrNull { it.area }?.box
    }

    /**
     * 侧栏是否已展开。
     *
     * 判据（真机 dump 双向验证）：
     * 1. 节点里出现"福利中心"行；
     * 2. **至少有某个**抽屉入口已挪到屏幕右侧（`left > [RIGHT_HALF_X]`）。
     *
     * 只满足第一条不够 —— 首页本身也可能含"福利中心"文案（首页有福利卡片）。
     * 两条同时满足才说明侧栏真的滑出来了。
     *
     * 注意第二条必须用 [anyDrawerEntryOnRight] 而不是 `findDrawerEntry().left`：
     * 侧栏展开时同帧里残留的左侧节点可能面积更小，取"最小那个"会读到 left=133
     * 从而误判成"没开"，于是循环再点一下把侧栏又关掉（真机实测踩过）。
     */
    fun isDrawerOpen(nodes: List<ScreenScanner.DumpNode>, screenWidth: Int): Boolean {
        if (findWelfareRow(nodes, screenWidth) == null) return false
        return anyDrawerEntryOnRight(nodes, screenWidth)
    }

    /**
     * 侧栏是否已展开 —— **循环控制专用**判据，用于决定"还要不要继续点汉堡"。
     *
     * 与 [isDrawerOpen] 的区别只有一处：**入口节点一时读不到时也算已开**。
     *
     * 为什么需要它：真机上点一次是开、再点一次就是关。若那一帧恰好没读到入口
     * 节点，严格判据返回 false，循环会以为"没开"再点一下，把刚打开的侧栏又关掉。
     * 所以宁可"认为开了而不再点"（多一次也没事，后面取行坐标时会校验），
     * 也不要"认为没开而多点一次"（必错）。
     *
     * 但不能退化成"只要看到福利中心就行" —— 首页自带的福利卡片文案会让它命中
     * （见 [looksOpenIsFalseOnHomePage] 回归用例），那样首页就不会去点汉堡了。
     * 所以判据是：**入口已经在右半屏**，或者**行可见且入口确实不在左半屏**。
     */
    fun looksOpen(nodes: List<ScreenScanner.DumpNode>, screenWidth: Int): Boolean {
        val limit = rightThreshold(screenWidth)
        if (anyDrawerEntryOnRight(nodes, screenWidth)) return true
        if (findWelfareRow(nodes, screenWidth) == null) return false
        // 行可见，但还要排除"入口仍在首页左上角"的首页帧。
        val entry = findDrawerEntry(nodes) ?: return true // 入口读不到 → 视为已开
        return entry.left > limit
    }

    /** Activity 名是否就是福利中心（`WelfareWebActivity` 及其同族）。 */
    fun isWelfareActivity(activity: String): Boolean {
        if (activity.isBlank()) return false
        if (activity.contains(WELFARE_ACTIVITY_MARKER)) return true
        return activity.endsWith(".WelfareWebActivity") ||
            activity.endsWith(".CampaignActivity") ||
            activity.endsWith(".WebBrowserActivity")
    }

    /**
     * 侧栏展开后"福利中心"行应当在的**纵向范围**，用于诊断。
     *
     * 真机实测行范围是 `694..778`（行高 84），这里放宽成"上三分之一屏"以外的
     * 合理区间，只用于日志提示，不参与点击决策。
     */
    fun describeRow(bounds: Bounds): String =
        "row=[${bounds.left},${bounds.top}][${bounds.right},${bounds.bottom}] " +
            "center=(${bounds.centerX},${bounds.centerY})"

    /** 一行 Sidebar 条目的最大合理宽度：屏幕宽度的 90%（超过说明取到了跨屏容器）。 */
    private const val MAX_ROW_WIDTH_FRACTION = 0.90f

    private fun widthOf(screenWidth: Int): Int =
        if (screenWidth > 0) screenWidth else REFERENCE_SCREEN_WIDTH

    /** "右半屏"的绝对阈值，由屏幕宽度算出。 */
    fun rightThreshold(screenWidth: Int): Int =
        (widthOf(screenWidth) * RIGHT_HALF_FRACTION).toInt()

    /** 一行侧栏条目的最大合理宽度，由屏幕宽度算出。 */
    fun maxRowWidth(screenWidth: Int): Int =
        (widthOf(screenWidth) * MAX_ROW_WIDTH_FRACTION).toInt()
}
