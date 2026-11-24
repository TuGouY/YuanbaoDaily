package me.codex.yuanbaodaily.automation

import kotlin.math.roundToInt

/**
 * **屏幕尺度换算**：所有"像素常数"都必须经过这里，禁止再直接比较绝对像素。
 *
 * ## 为什么需要它
 *
 * 这套自动化最早是对着开发机（PLQ110，**1272×2800 / 480dpi**）调的，判定里留下了
 * 一批"在那个分辨率上刚好合适"的像素常数。换一台机器（小米 1080×2400 / 440dpi、
 * vivo 1260×2800 / 460dpi、平板 1600×2560 / 320dpi）就会出现两类错误：
 *
 * - **常数太小**：`MAX_TEXT_SIDE = 1200` 在 1080 宽机型上比整屏还宽 → "整屏容器"
 *   过滤不掉 → 被当成输入框同行的文字节点 → 空框签名判据失真 → 点到语音键。
 * - **常数太大**：`MIN_BUTTON_SIDE = 40` 在 320dpi 平板上约为 20dp，正常按钮被当成
 *   "装饰节点"滤掉 → 找不到发送键 → 走兜底乱点。
 *
 * ## 两种换算，用途不同
 *
 * | 方法 | 基准 | 适用 |
 * | --- | --- | --- |
 * | [widthScaled] | 屏宽等比（1272 → 目标宽） | **结构尺寸**：控件大小、整屏容器、缩略图格子。布局是 `match_parent`，宽度变了尺寸就等比变 |
 * | [dpScaled] | dp 等比（480dpi → 目标 dpi） | **触控/视觉容差**：同一行判定、边缘留白、最小可点边长。这些是"手指尺度"，跟 dp 走 |
 *
 * 两者在参考机型上都还原成原常数，所以开发机行为不变；其他机型按各自规律缩放。
 *
 * ## 纯数据
 *
 * 不依赖任何 Android API，可以在纯 JVM 单测里按任意分辨率断言（见
 * `ResolutionInvarianceTest`）。真机上由 `AutomationAccessibilityService.screenScale()`
 * 用 `Display.getRealSize()` + `densityDpi` 构造 —— **必须用全屏尺寸**，
 * `resources.displayMetrics` 是可用窗口（实测 2603 vs 2800），拿它当基准会整体偏移。
 */
internal data class ScreenScale(
    val widthPx: Int,
    val heightPx: Int,
    val densityDpi: Int,
) {
    val valid: Boolean get() = widthPx > 0 && heightPx > 0

    /** 屏宽比例 → 绝对 x。 */
    fun xOf(fraction: Float): Int = (widthPx * fraction).roundToInt()

    /** 屏高比例 → 绝对 y。 */
    fun yOf(fraction: Float): Int = (heightPx * fraction).roundToInt()

    /**
     * 把**参考机型（1272 宽）上量出来的像素**按屏宽等比换算。
     *
     * 用于"控件有多大 / 是不是整屏容器 / 缩略图格子多大"这类跟布局宽度一起缩放的判据。
     */
    fun widthScaled(referencePx: Int): Int = scaleBy(referencePx, widthPx, REFERENCE_WIDTH)

    /**
     * 把参考像素按 **dp** 换算（参考机 480dpi ≈ 3x）。
     *
     * 用于"同一行"容差、边缘留白、最小可点边长这类手指尺度的判据。
     * `densityDpi` 不可用时退回 [widthScaled]。
     */
    fun dpScaled(referencePx: Int): Int =
        if (densityDpi > 0) scaleBy(referencePx, densityDpi, REFERENCE_DENSITY_DPI)
        else widthScaled(referencePx)

    /**
     * 输入栏"同一行"的纵向容差。
     *
     * 参考值 24px @1272/480dpi = 8dp。取 dp 换算，另外兜一个"不小于屏宽 1%"，
     * 避免在极低 dpi 的设备上容差小到 0 导致同行节点全被排除。
     */
    fun rowTolerance(): Int = maxOf(dpScaled(ROW_TOLERANCE_REF), widthScaled(12))

    /** 单个**控件**允许的最大边长（超过视为整屏/整块容器）。参考 400px @1272。 */
    fun controlMaxSide(): Int = widthScaled(CONTROL_MAX_SIDE_REF)

    /** 单个控件的**最小**边长（低于它视为 0×0/装饰占位节点）。参考 40px @480dpi。 */
    fun controlMinSide(): Int = maxOf(dpScaled(CONTROL_MIN_SIDE_REF), 8)

    /**
     * 距屏幕**底部**固定偏移 → 该机上的 y 比例。
     *
     * ## 为什么不能写死高度百分比
     *
     * 底栏快捷入口、输入栏都是 `layout_gravity=bottom` 的贴底布局，它们的屏幕位置
     * 由"距底多少 dp"决定，**跟距顶的百分比无关**。写死 `0.845` 只在 1272×2800
     * （宽高比 2.20）上成立：
     *
     * | 机型 | 屏高 | 宽高比 | 固定 0.845 的落点 | 真实底栏 |
     * | --- | --- | --- | --- | --- |
     * | 开发机 PLQ110 | 2800 | 2.20 | 2366 | 2366 ✓ |
     * | 一加 Ace 6（1080 渲染档） | 2378 | 2.20 | 2009 | 1980 ✗ 低 29px |
     * | Mate 90 Pro Max | 2848 | 2.158 | 2407 | 2414 ✓ |
     * | 1440 宽 2K 机 | 3168 | 2.20 | 2677 | 2662 ✗ 高 15px |
     *
     * 近 3 年直板机的宽高比在 **2.158–2.225** 之间（跨度 3%），换算到 y 上就是
     * 40–95px 的偏差 —— 足够从"底栏入口行"滑到"输入栏"或"上方卡片"。
     *
     * @param referenceInsetPx 参考机型上"距屏幕底部的像素数"（@1272/480dpi）
     */
    fun bottomAnchoredFraction(referenceInsetPx: Int): Float {
        if (heightPx <= 0) return 0f
        val inset = dpScaled(referenceInsetPx)
        return (1f - inset.toFloat() / heightPx).coerceIn(0.50f, 0.995f)
    }

    /**
     * 屏宽档位标签，**只用于日志诊断**。
     *
     * 近 3 年机型实测（`手机屏幕分辨率分析.xlsx`，54 台）的实际渲染宽度集中在三段：
     * `~1080` / `1200–1320`（占 63%）/ `1440`（占 26%）。打日志时带上档位，
     * 用户在朋友机器上抓日志就能一眼看出走的是哪一档。
     *
     * 注意：**同一档位内的宽度仍不相同**，所以换算一律用实际宽度，绝不能用档位代表值。
     */
    fun widthTierLabel(): String = when {
        widthPx <= 0 -> "unknown"
        widthPx < 1180 -> "fhd(~1080)"
        widthPx < 1340 -> "wqhd1500(1200-1320)"
        widthPx < 1520 -> "wqhd(1440)"
        else -> "wide(>1520)"
    }

    override fun toString(): String = "${widthPx}x$heightPx@${densityDpi}dpi"

    companion object {
        /** 参考机型（PLQ110）的真实像素宽度，所有 `*_REF` 常数都在它上面量出来。 */
        const val REFERENCE_WIDTH = 1272

        /** 参考机型真实像素高度（仅用于日志/兜底，判据一律走宽度或 dp）。 */
        const val REFERENCE_HEIGHT = 2800

        /** 参考机型 dpi（≈3x）。 */
        const val REFERENCE_DENSITY_DPI = 480

        const val ROW_TOLERANCE_REF = 24
        const val CONTROL_MAX_SIDE_REF = 400
        const val CONTROL_MIN_SIDE_REF = 40

        /**
         * 底栏快捷入口行中心距**屏幕底部**的像素数（@1272/480dpi，开发机实测）。
         *
         * 2800 - 2366 = 434。手势导航下单这一行就在这个高度；三键导航会整体上移，
         * 所以兜底要试一组偏移，而不是一组百分比。
         */
        const val BOTTOM_BAR_INSET_REF = 434

        /**
         * 近 3 年主流机型的**实际渲染**屏宽（去重，升序）。
         *
         * 数据来源：`D:/codex/手机屏幕分辨率分析.xlsx`（54 台，19 台已核实分辨率）。
         * 分布：`~1080` 占 10%、`1200–1320` 占 63%、`1440` 占 26%。
         *
         * **只用于自检日志与单测覆盖，不参与任何换算** —— 同一宽度档内各机型仍不同宽，
         * 换算必须用运行时实测宽度。另注意面板分辨率 ≠ 渲染分辨率
         * （一加 Ace 6 面板 2800×1272，系统默认按 2378×1080 渲染），
         * 所以任何"按型号查表"的做法都是错的，只能读 `getRealSize()`。
         */
        val KNOWN_RENDER_WIDTHS = intArrayOf(
            1080, 1156, 1200, 1208, 1220, 1256, 1260, 1264, 1272, 1280, 1320, 1440,
        )

        /** 读不到屏幕尺寸时的退化值：保证"在开发机能跑的行为"，而不是把路线判死。 */
        val FALLBACK = ScreenScale(REFERENCE_WIDTH, REFERENCE_HEIGHT, REFERENCE_DENSITY_DPI)

        fun of(widthPx: Int, heightPx: Int, densityDpi: Int): ScreenScale =
            if (widthPx > 0 && heightPx > 0) {
                ScreenScale(widthPx, heightPx, densityDpi)
            } else {
                FALLBACK
            }

        /**
         * 只拿得到屏宽时用的换算（例如平台 dump 通道没有 dpi 信息）。
         *
         * [screenWidth] <= 0 时原样返回参考值 —— 宁可退化成开发机行为，也不要把判据判死。
         */
        fun scaleWidth(screenWidth: Int, referencePx: Int): Int =
            if (screenWidth > 0) scaleBy(referencePx, screenWidth, REFERENCE_WIDTH) else referencePx

        private fun scaleBy(referencePx: Int, actual: Int, reference: Int): Int {
            if (referencePx <= 0) return 0
            if (actual <= 0 || reference <= 0) return referencePx
            val scaled = (referencePx.toFloat() * actual / reference).roundToInt()
            return maxOf(1, scaled)
        }
    }
}
