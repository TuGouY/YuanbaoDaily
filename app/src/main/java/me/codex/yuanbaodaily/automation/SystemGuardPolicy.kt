package me.codex.yuanbaodaily.automation

/**
 * **厂商安全中心的"无障碍提示"处理策略**。
 *
 * ## 它是什么、为什么必须处理
 *
 * 国产 ROM 在无障碍服务**每次重新绑定**后，会弹一个全屏提示，大意是
 * "检测到某应用获取无障碍权限，可能影响隐私和资金安全"，两个按钮通常是
 * 「**关闭无障碍**」（主按钮，高亮）和「保持开启」。
 *
 * 真机实证（ColorOS，`com.oplus.safecenter/.accessibility.AccessibilityGuideCloseActivity`）：
 *
 * ```
 * selfTest:pic: 元宝不在前台 → 先拉起元宝
 * selfTest:pic: 35000ms 内没能把元宝拉到前台 → 放弃本轮
 * health: windows=[com.oplus.safecenter] 看到宿主=false
 * ```
 *
 * 元宝**其实已经被拉起**（`topResumedActivity=YBHomeActivityV2`），是这个提示
 * 盖在最上层，导致无障碍树里只剩 safecenter 一个窗口 —— 所有基于"看到元宝"的判定
 * 全部失败，`ensureHostForeground` 白等 35 秒后放弃。
 *
 * 而免 root 版**每次自愈都会重新绑定**，所以这个提示会反复出现 ——
 * 不处理的话，自愈等于"自毁"：服务恢复了，但屏幕上多了个随时会被误触
 * "关闭无障碍"的弹窗。
 *
 * ## 安全边界（这是本类存在的全部意义）
 *
 * 只做两件事，且都必须**极窄**：
 *
 * 1. **只对白名单包名**动手（各家安全中心）；
 * 2. **只点精确等于**「保持开启 / 保持使用 / 保持允许 / 继续使用」的节点。
 *
 * 明确禁止：
 * - 不按坐标点（各家布局不同，且主按钮恰好是危险的那个）；
 * - 不点任何含「关闭 / 停用 / 取消」的节点；
 * - 找不到符合的节点就**什么都不做**，把情况写进日志让用户手动处理。
 *
 * 换句话说：这个类宁可失效，也绝不能误点 —— 点错一次就是把无障碍关了，
 * 而那正是免 root 版唯一的执行能力。
 */
internal object SystemGuardPolicy {

    /**
     * 各家"安全中心 / 权限管理"的包名。
     *
     * 用 `startsWith` 而不是全等：这些包在同一厂商的不同版本上带过后缀
     * （例如 `com.miui.securitycenter` / `com.miui.securitycenter.remote`）。
     */
    private val GUARD_PACKAGES = listOf(
        "com.oplus.safecenter",          // OPPO / 一加 / realme（ColorOS）
        "com.coloros.safecenter",        // 早期 ColorOS
        "com.oplus.securitypermission",  // ColorOS 权限弹窗（关联启动确认也在这里）
        "com.miui.securitycenter",       // 小米 / 红米
        "com.vivo.permissionmanager",    // vivo
        "com.iqoo.secure",               // iQOO
        "com.huawei.systemmanager",      // 华为 / 荣耀
        "com.hihonor.systemmanager",     // 新荣耀
        "com.samsung.android.lool",      // 三星
        "com.meizu.safe",                // 魅族
    )

    /**
     * **只点这些**（精确相等，`trim` 之后）。
     *
     * 全部是"保留无障碍"的语义。刻意不收录「允许」这类泛词 ——
     * 它在别的弹窗里可能表示"允许后台启动"或"允许读取"，含义完全不同。
     */
    private val KEEP_LABELS = setOf(
        "保持开启",
        "保持使用",
        "保持允许",
        "继续使用",
        "保持",
    )

    fun isGuardPackage(packageName: String): Boolean {
        if (packageName.isBlank()) return false
        val normalized = packageName.lowercase()
        return GUARD_PACKAGES.any { normalized.startsWith(it) }
    }

    /** 是否是我们可以安全点击的"保留"按钮。 */
    fun isKeepLabel(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false
        return trimmed in KEEP_LABELS
    }

    /**
     * 是否是**绝不能点**的节点。
     *
     * 这个判断在点之前再核一次：即使 `isKeepLabel` 因为文案变化误判，
     * 只要节点文字里带这些词就一律放弃。
     */
    fun isDangerousLabel(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false
        return trimmed.contains("关闭") ||
            trimmed.contains("停用") ||
            trimmed.contains("禁用") ||
            trimmed == "取消"
    }

    /**
     * 综合判断：这个节点的文字能不能点。
     *
     * 两个条件必须同时满足 —— 是已知的保留文案，且不含任何危险词。
     */
    fun isSafeToClick(text: String): Boolean =
        isKeepLabel(text) && !isDangerousLabel(text)
}
