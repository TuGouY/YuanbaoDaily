package me.codex.yuanbaodaily.automation

/**
 * **窗口栈判据**：从无障碍服务上报的窗口列表里，判断"最上层的是谁"。
 *
 * ## 为什么不能用 `snapshot.packageName`
 *
 * 那个字段取的是**第一个**窗口的包名，而系统 UI（`com.android.systemui`）
 * 常常排在前面。真机实证（1.4.2 免 root 版自检）：
 *
 * ```
 * selfTest:pic: 元宝不在前台 → 先拉起元宝
 * selfTest:pic: 20000ms 内没能把元宝拉到前台 → 放弃本轮
 * ```
 *
 * 而 `dumpsys activity` 明明显示 `topResumedActivity=...YBHomeActivityV2`。
 * 也就是说元宝**就在前台**，判据却说不算 —— 因为它比的是第一个窗口。
 *
 * ## 为什么"最上层"是窗口列表的**最后**一项
 *
 * `AccessibilityService.getWindows()` 按 Z 序返回，**后加入的在上层**。
 * 实测前台是元宝时上报 `[com.android.systemui, com.tencent.hunyuan.app.chat]`；
 * 系统弹窗盖住时只剩 `[com.oplus.safecenter]`。
 *
 * 这是纯字符串解析，所以放成独立对象 —— 判错了的后果不是"显示错"，
 * 而是**该拉起元宝时没拉起**（整轮任务零点击退出）或**在别人界面上盲点**，
 * 两种都很难从日志反推。
 */
internal object WindowStackPolicy {

    /**
     * 取窗口列表里**最上层**那个的包名，读不到返回空串。
     *
     * 输入形如 `1018:com.tencent.hunyuan.app.chat,5:com.android.systemui`，
     * 每项可能带 `windowId:` 前缀（服务端为了诊断加的），也可能不带。
     */
    fun topPackage(windows: String): String {
        if (windows.isBlank()) return ""
        val last = windows.split(',').lastOrNull()?.trim().orEmpty()
        if (last.isBlank()) return ""
        val colon = last.indexOf(':')
        val name = if (colon >= 0) last.substring(colon + 1) else last
        return name.trim()
    }

    /**
     * 宿主是否在最上层。
     *
     * @param windows 服务端上报的窗口列表字符串
     * @param hostPackage 宿主的包名（元宝）
     * @return true 表示宿主是当前最上层窗口；窗口列表读不到时返回 **false**
     *         （宁可不去动手，也不要盲点 —— 这是本工程的既定原则）
     */
    fun isHostOnTop(windows: String, hostPackage: String): Boolean {
        val top = topPackage(windows)
        if (top.isBlank() || hostPackage.isBlank()) return false
        return top == hostPackage
    }

    /**
     * 是否是我们自己的界面在最上层（免 root 版自己的 Activity）。
     *
     * 单独判一次是因为这种状态很容易被误认为"宿主还在前台"：
     * 系统不销毁后台窗口，所以元宝的窗口仍留在列表里，只是被压下去了。
     */
    fun isSelfOnTop(windows: String, selfPackage: String): Boolean {
        val top = topPackage(windows)
        return top.isNotBlank() && top == selfPackage
    }
}
