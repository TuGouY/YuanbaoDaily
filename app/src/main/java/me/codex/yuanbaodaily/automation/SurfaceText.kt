package me.codex.yuanbaodaily.automation

/**
 * "现在在哪一屏"的**纯判定**。
 *
 * 单独抽出来是因为这几条判据直接决定"要不要点、点哪里"，一旦判错就是误触，
 * 而它完全可以脱离 Android API 用真机 dump 文本做回归测试。
 *
 * 背景（用户实测反馈）：任务做完后模块"开始乱点，点左上角抽屉，进不了福利中心"。
 * 追下去发现核心问题是**没人确认过自己站在哪一屏**：
 * 能力页（写作页/P图页）左上角是"返回"，首页左上角才是"抽屉入口"，
 * 两者位置几乎重合。在能力页上去找抽屉入口当然找不到，
 * 老代码随后就掉进一串**写死的比例坐标**兜底（点"我们"底部标签、点福利卡片位置），
 * 在 2.87.20 这种没有底部标签栏的版本上全部落空 —— 用户看到的就是"瞎点"。
 */
internal object SurfaceText {

    /**
     * 首页独有标记：那一行快捷入口。
     *
     * 真机 `evidence/home_probe.xml`：`快速 / AI写作 / 作业批改 / AI创作` 在 y≈2301。
     * 其中 `作业批改` 只出现在首页，判断首页时最可靠。
     */
    val HOME_MARKERS = listOf("作业批改", "AI创作", "快速")

    /** 首页输入栏的占位文案（仅首页/对话页有）。 */
    private const val HOME_COMPOSER_HINT = "发消息或按住说话"

    /** 能力页（写作 / P图 / 拍题）的标记。 */
    val CAPABILITY_MARKERS = listOf(
        "选择写作类型",
        "请输入你要写的主题",
        "点击添加创作素材",
        "悬浮窗搜题",
        "拍题答疑",
    )

    /** 判断是否是元宝首页。 */
    fun looksLikeHome(text: String): Boolean {
        if (text.isEmpty()) return false
        // "AI创作" 在首页快捷入口和"创作"页都可能出现，所以要求同时看到输入栏提示，
        // 或者看到首页独有的"作业批改"。
        if (text.contains("作业批改")) return true
        if (HOME_EXCLUDING_MARKERS.any(text::contains)) return false
        // **底栏那排快捷入口是可横向滚动的**：滚过之后 `AI创作 / 快速` 会被滑出屏幕、
        // 换成 `AI写作 / AI录音笔`，于是"必须看到 AI创作"这个判据就失效了。
        //
        // 真机实证（1.4.21）：写作任务为了点 `AI写作` 把底栏左滑，之后 P图 判首页失败，
        // `ensureHomeSurface` 于是连按两次返回 —— **把元宝直接退到桌面**，
        // 后面整轮任务一个点击都落不下去（日志：`create: 回不到首页 activity=<本应用>`）。
        //
        // 所以判据换成**输入栏占位文案**：它从首页到对话页一直在，且能力页（写作/P图/拍题）
        // 的占位文案完全不同，用 [HOME_EXCLUDING_MARKERS] 排掉即可。
        return text.contains(HOME_COMPOSER_HINT) ||
            (text.contains("AI创作") && text.contains("快速"))
    }

    /**
     * 只在能力页出现、**绝不会**出现在首页的标记。
     *
     * 注意不能用 [CAPABILITY_MARKERS]：里面的 `拍题答疑` 同时也是首页底栏的快捷入口，
     * 拿它排首页会**把首页排掉**。
     */
    private val HOME_EXCLUDING_MARKERS = listOf(
        "选择写作类型",
        "请输入你要写的主题",
        "点击添加创作素材",
        "悬浮窗搜题",
    )

    /** 判断是否停在某个"能力页"（写作 / P图 / 拍题），这类页左上角是返回而不是抽屉。 */
    fun looksLikeCapability(text: String): Boolean =
        text.isNotEmpty() && CAPABILITY_MARKERS.any(text::contains)

    /**
     * Activity 名是否就是元宝首页。
     *
     * 比文案可靠得多：首页的快捷入口在不同版本里会改名，而首页 Activity
     * (`…home.v2.YBHomeActivityV2`) 一直稳定。判"能不能安全按返回"时优先用它 ——
     * 在首页按返回会直接退出元宝，这是绝不能误判的一次判断。
     */
    fun isHomeActivity(activity: String): Boolean {
        if (activity.isBlank()) return false
        return activity.contains("YBHomeActivity")
    }

    /**
     * Activity 名是否是福利中心（WebView）。
     *
     * 判断它很重要：福利中心页面上有"每日任务""问元宝问题"这类文案，
     * 很容易被误当成首页或任务页，进而在上面找"AI创作"这种根本不存在的入口。
     */
    fun isWelfareActivity(activity: String): Boolean {
        if (activity.isBlank()) return false
        return activity.contains("WelfareWebActivity", ignoreCase = true)
    }

    /**
     * 领积分弹窗（每完成一次能力任务，福利中心就弹一次）。
     *
     * 真机实测 2026-10-21：白色模态框盖住整页，标题「恭喜获得」，
     * 中间积分图标 +「1500 积分」，底部绿色按钮「开心收下」。
     *
     * **不点掉它，后面的任务全部做不了**：弹窗是模态的，被它盖住的
     * 「去拍题」「做同款」不进无障碍树，单纯滚动页面也找不到 ——
     * 表现就是"P图 做完一次之后，拍题和模板死活点不到"。
     *
     * 判据同时要求标题与按钮文案，避免和福利中心页身上「239500 积分」
     * 这类文字混淆：只有真弹窗才同时有这两句。
     */
    fun looksLikeRewardDialog(text: String): Boolean {
        if (text.isEmpty()) return false
        val compact = text.replace(" ", "")
        return compact.contains(REWARD_TITLE) && compact.contains(REWARD_DISMISS_LABELS.first())
    }

    private const val REWARD_TITLE = "恭喜获得"

    /**
     * 领积分弹窗上唯一安全的按钮。
     *
     * 点它就是"收下积分并关闭"，一箭双雕；**不做坐标兜底、不猜右上角的 X** ——
     * 这个弹窗的关闭叉在不同版本里位置会挪，而「开心收下」四个字始终在。
     */
    val REWARD_DISMISS_LABELS = listOf("开心收下")

    /**
     * 从能力页回到首页时，左上角那个"关闭/返回"的候选文案。
     *
     * 只用作**语义匹配**（节点自己的文字或 content-desc），匹配不到就按一次系统返回键，
     * 绝不用比例坐标去点左上角 —— 那里在首页恰好是抽屉入口，点错就把抽屉打开，
     * 于是下一跳又找不到福利中心，正是用户看到的"乱点"。
     */
    val BACK_LABELS = listOf("返回", "关闭", "取消", "back", "close")
}
