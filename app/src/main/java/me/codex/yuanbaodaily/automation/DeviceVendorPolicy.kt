package me.codex.yuanbaodaily.automation

/**
 * **国产 ROM 差异适配**（小米 / vivo / OPPO 系 / 华为系 / 三星 …）。
 *
 * ## 为什么需要一层
 *
 * "免 root 版能不能自动跑"取决于四件事，而**每一件在不同厂商上的开关位置都不一样**：
 *
 * | 需要什么 | 影响 | 各家的名字 |
 * | --- | --- | --- |
 * | 自启动 | 开机后闹钟能不能被送达 | 自启动 / 应用启动管理 / 允许自启动 |
 * | 关联启动 | 能不能从后台拉起元宝 | 关联启动 / 后台弹出界面 / 允许被关联启动 |
 * | 后台存活 | 进程不被冻结 | 省电策略无限制 / 后台高耗电 / 允许后台活动 |
 * | 通知权限 | 前台服务通知能不能显示（Android 13+） | 通知权限（系统统一，非厂商定制） |
 *
 * ## 设计约束（重要）
 *
 * **不硬跳厂商设置页**。
 *
 * 网上常见的做法是 `Intent().setComponent(ComponentName("com.miui.securitycenter", ...))`
 * 直接跳到"自启动管理"。这个做法在**同一厂商的不同版本之间**就会失效
 * （组件名改过至少三次），失败时要么静默无反应、要么跳到安全中心的首页 ——
 * 用户以为点了没坏，其实什么都没设置。
 *
 * 所以这里只做两件**厂商无关**的事：
 * 1. 识别厂商，给出**准确的文字路径**（用户可以照着找）；
 * 2. 跳**系统标准**的应用详情页（`ACTION_APPLICATION_DETAILS_SETTINGS`），
 *    这个 intent 所有 ROM 都实现了，是唯一可靠的落点。
 */
internal object DeviceVendorPolicy {

    enum class Vendor {
        XIAOMI,
        VIVO,
        OPPO,
        HUAWEI,
        HONOR,
        SAMSUNG,
        MEIZU,
        OTHER,
    }

    /**
     * 识别厂商。
     *
     * 用 `manufacturer` 为主、`brand` 为辅：两者在个别机型上不一致
     * （例如 `manufacturer=OPPO` 但 `brand=OnePlus`，或 `manufacturer=vivo` 但 `brand=iQOO`），
     * 合并判断比只看一个稳。
     */
    fun detect(manufacturer: String, brand: String): Vendor {
        val key = (manufacturer + " " + brand).lowercase()
        return when {
            key.contains("xiaomi") || key.contains("redmi") || key.contains("poco") -> Vendor.XIAOMI
            key.contains("vivo") || key.contains("iqoo") -> Vendor.VIVO
            key.contains("oppo") || key.contains("oneplus") || key.contains("realme") -> Vendor.OPPO
            // **荣耀必须先判**：早期荣耀机型 manufacturer=HUAWEI、brand=HONOR，
            // 合并串里两个词同时出现。先判 huawei 的话，这类机器会被指到 EMUI 的路径，
            // 而它实际跑 MagicOS —— 用户照着找不到开关。
            key.contains("honor") -> Vendor.HONOR
            key.contains("huawei") -> Vendor.HUAWEI
            key.contains("samsung") -> Vendor.SAMSUNG
            key.contains("meizu") -> Vendor.MEIZU
            else -> Vendor.OTHER
        }
    }

    /** 界面上显示的名字（不是所有厂商都需要单独说明）。 */
    fun displayName(vendor: Vendor): String = when (vendor) {
        Vendor.XIAOMI -> "小米 / 红米"
        Vendor.VIVO -> "vivo / iQOO"
        Vendor.OPPO -> "OPPO / 一加 / realme"
        Vendor.HUAWEI -> "华为"
        Vendor.HONOR -> "荣耀"
        Vendor.SAMSUNG -> "三星"
        Vendor.MEIZU -> "魅族"
        Vendor.OTHER -> "其他品牌"
    }

    /**
     * 该机型上**必须由用户手动确认**的开关，按重要性排序。
     *
     * 每一条都是"照着名字去系统设置里找得到"的路径描述，不含任何组件名。
     */
    fun requiredToggles(vendor: Vendor): List<String> = when (vendor) {
        Vendor.XIAOMI -> listOf(
            "自启动：设置 → 应用设置 → 元宝每日任务·免root → 自启动",
            "省电策略：同上页面 → 省电策略 → 选「无限制」",
            "后台弹出界面：同上页面 → 其他权限 → 显示后台界面（允许）",
        )
        Vendor.VIVO -> listOf(
            "自启动：i管家 → 应用管理 → 权限管理 → 自启动（允许）",
            "后台高耗电：i管家 → 应用管理 → 权限管理 → 后台高耗电（允许）",
            "关联启动：同上页面 → 关联启动（允许）",
        )
        Vendor.OPPO -> listOf(
            "自启动：设置 → 应用 → 自启动管理（允许）",
            "关联启动：同上页面 → 关联启动（允许）",
            "耗电管理：设置 → 电池 → 应用耗电管理 → 允许后台活动",
        )
        Vendor.HUAWEI -> listOf(
            "应用启动管理：设置 → 应用 → 应用启动管理 → 找到本应用 → 关闭「自动管理」",
            "手动管理里把「自启动 / 关联启动 / 后台活动」三项都打开",
            "电池优化：设置 → 电池 → 更多电池设置 → 忽略电池优化",
        )
        Vendor.HONOR -> listOf(
            "应用启动管理：设置 → 应用 → 应用启动管理 → 找到本应用 → 关闭「自动管理」",
            "手动管理里把「自启动 / 关联启动 / 后台活动」三项都打开",
            "电池优化：设置 → 电池 → 更多电池设置 → 忽略电池优化",
        )
        Vendor.SAMSUNG -> listOf(
            "休眠应用：设置 → 电池 → 后台使用限制 → 把本应用从「休眠应用」里移出",
            "自启动：设置 → 应用程序 → 本应用 → 电池 → 不受限制",
        )
        Vendor.MEIZU -> listOf(
            "自启动：手机管家 → 权限管理 → 后台管理 → 允许后台运行",
            "省电模式：设置 → 电量管理 → 把本应用设为「不做限制」",
        )
        Vendor.OTHER -> listOf(
            "自启动：在系统设置的「应用管理」里找到本应用，允许自启动",
            "电池优化：把本应用的省电策略设为「无限制 / 允许后台活动」",
        )
    }

    /**
     * 一句总述，放在界面顶部。
     *
     * 刻意**不提"关联启动确认框"的具体样子** —— 那是 ColorOS 的形态，
     * 小米是"后台弹出界面"权限、vivo 是"关联启动"开关，表现完全不同。
     */
    fun hint(vendor: Vendor): String =
        "当前机型：${displayName(vendor)}。这类系统默认会限制后台应用，" +
            "要让它到点自动跑，下面几项必须手动允许一次（只需一次）。"
}
