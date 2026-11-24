package me.codex.yuanbaodaily.automation

/**
 * 「这一下能不能点发送」的**结构判定**（纯数据、无 Android API，可被纯 JVM 单测覆盖）。
 *
 * ## 为什么必须有这一层
 *
 * 元宝输入栏右侧那颗可点容器是**一颗语义随内容翻转的按钮**（真机 1272×2800 实测）：
 *
 * ```text
 * 空文案： [1048,2435][1216,2645] View clickable=true   ← 容器里只有"更多"，整颗是"切到语音"
 * 有文案： 同一矩形                                        ← 变成"发送 ⬆"
 * ```
 *
 * 也就是说**同一个坐标**在两种状态下含义相反。任何"先点一下看看"的策略都会在空文案时
 * 把用户切进语音模式（用户报的正是这个）。而且这颗容器**没有 text / content-desc / resource-id**，
 * 所以既不能用文字识别，也不能用 id 识别 —— 只能靠**同帧的其它结构证据**来判断当前是哪种状态。
 *
 * ## 判据（全部来自真机 dump，不含任何屏幕比例）
 *
 * 1. **正面证据**：同行里出现语义为"发送"的节点 → 直接点**它自己**的中心（最可靠）。
 * 2. **反面证据**：同行里还挂着 `切换语音`（或"按住 说话"占位）→ 说明输入框是**空**的，
 *    此刻那颗容器是"切到语音" ✗ → **拒点**。
 * 3. **宿主实证**：宿主进程能从 View 树读到输入框文字时，以它为准
 *    （`true` 有文案 / `false` 空 / `null` 读不到）。
 * 4. **证据不足 → 拒点**。调用方随后回退到"回车发送"这条**零误触**通道。
 *
 * 设计原则与 [ComposerGeometry] 一致：**宁可不发，也不误触**。
 */
internal object ComposerSendGuard {

    /** 判定结果。 */
    enum class Verdict {
        /** 找到了语义为"发送"的节点 → 点它自己的中心。 */
        SEND_BY_NODE,

        /** 没有发送节点，但有"输入框确实有文案"的实证 → 按真实几何点动作键。 */
        SEND_BY_GEOMETRY,

        /** 证据不足，或明确处于"空输入框"状态 → **拒点**。 */
        REFUSE,
    }

    /** 一次判定：结论 + 落点 + 人类可读原因（原因会写进日志，便于换版本后定位）。 */
    data class Decision(
        val verdict: Verdict,
        val x: Int = -1,
        val y: Int = -1,
        val reason: String = "",
    ) {
        val allowed: Boolean get() = verdict != Verdict.REFUSE
    }

    /** 空输入框的**结构签名**：元宝在输入框为空时会在同行挂一颗 `切换语音` 图标。 */
    const val EMPTY_MARKER = "切换语音"

    /** "按住 说话"占位：语音模式下的另一条空签名。 */
    private const val HOLD_TO_TALK = "按住说话"

    /** 发送键的语义标记（有文案时元宝会给它"发送"语义）。 */
    private val SEND_MARKERS = listOf("发送", "send")

    /** 与输入框同行的容差（**参考机型像素**，由 [ScreenScale.rowTolerance] 换算）。 */
    private const val ROW_TOLERANCE_REF = 24

    /**
     * 一行里"单个**控件**"允许的最大边长（**参考机型像素**，按屏宽等比换算）。
     *
     * 只用于找**发送节点**（发送键是按钮，边长远小于此值）。
     */
    private const val MAX_ROW_SIDE_REF = 400

    /**
     * 一行里"单个**文字节点**"允许的最大边长。
     *
     * 单独放宽的原因：空文案占位符 `发消息或按住说话...` 实测宽 **490px**
     * （`[224,2502][714,2579]`），用 [MAX_ROW_SIDE_REF] 会把它整个滤掉，
     * 于是"占位符出现即判空"这条最可靠的信号就失效了（单测当场抓到这个）。
     * 上限仍要挡住**整屏容器**（实测 `[0,0][1272,2800]` 这类），所以取 1200。
     */
    private const val MAX_TEXT_SIDE_REF = 1200

    /**
     * "一个文字节点最多能有多宽"的实际阈值。
     *
     * 它的作用是**挡住整屏容器**（`[0,0][1272,2800]`、`[0,0][1080,2400]` 这种）。
     * 老代码写死 1200：在 1272 宽机型上刚好能把整屏节点滤掉，但换到 **1080 宽**
     * 的手机上，整屏节点宽度就是 1080 < 1200 —— **筛不掉了**，于是"整屏容器"
     * 可能被当成输入框同行的那颗文字节点，"空输入框签名"判定随之失真。
     *
     * 现在按 **屏宽等比** 换算（参考 1200/1272 ≈ 94% 屏宽）：任何分辨率下整屏容器
     * 都必然超过阈值，而真实占位符（约 38% 屏宽）都远低于阈值。
     * 拿不到 [ScreenScale] 时退回 `0.9 × screenWidth`，再退参考值。
     */
    private fun textMaxSide(screenWidth: Int, scale: ScreenScale): Int = when {
        scale.valid -> scale.widthScaled(MAX_TEXT_SIDE_REF)
        screenWidth > 0 -> (screenWidth * 0.9f).toInt()
        else -> MAX_TEXT_SIDE_REF
    }

    /**
     * 输入栏那一行的节点（限定在输入框纵向范围内，避免把页面别处的"发送"误当本栏的发送键）。
     */
    private fun composerRow(
        nodes: List<ScreenScanner.DumpNode>,
        editor: Bounds,
        scale: ScreenScale = ScreenScale.FALLBACK,
        maxSide: Int = scale.controlMaxSide(),
    ): List<ScreenScanner.DumpNode> {
        val tolerance = scale.rowTolerance()
        return nodes.filter { node ->
            val box = node.box
            box.width > 0 && box.height > 0 &&
                box.centerY in (editor.top - tolerance)..(editor.bottom + tolerance) &&
                box.width <= maxSide && box.height <= maxSide
        }
    }

    /** 同行里语义为"发送"的节点（正面证据）。 */
    fun findSendNode(
        nodes: List<ScreenScanner.DumpNode>,
        editor: Bounds,
        scale: ScreenScale = ScreenScale.FALLBACK,
    ): ScreenScanner.DumpNode? = composerRow(nodes, editor, scale).firstOrNull { node ->
        val haystack = (node.text + " " + node.description).lowercase()
        SEND_MARKERS.any(haystack::contains)
    }

    /** 同行里是否出现"空输入框"签名（反面证据）。 */
    fun showsEmptySignature(
        nodes: List<ScreenScanner.DumpNode>,
        editor: Bounds,
        screenWidth: Int = 0,
        scale: ScreenScale = ScreenScale.FALLBACK,
    ): Boolean = composerRow(
        nodes,
        editor,
        scale,
        maxSide = textMaxSide(screenWidth, scale),
    ).any { node ->
        val haystack = (node.text + " " + node.description).replace(WHITESPACE, "")
        haystack.contains(EMPTY_MARKER) || haystack.contains(HOLD_TO_TALK)
    }

    /**
     * 决定这次"点发送"能不能做、以及点哪里。
     *
     * @param nodes 当前窗口的扁平节点列表。
     * @param editor 输入框真实 bounds（解析不出输入框时**不要**调用本方法，直接拒点）。
     * @param hostEditorText 宿主 View 树里输入框的文字状态：
     *   `true` = 确实有文案；`false` = 确实为空；`null` = 读不到（Compose 常读不到）。
     * @param geometryPoint 没有发送节点时，准备退而求其次点的那个"动作键"落点。
     */
    fun decide(
        nodes: List<ScreenScanner.DumpNode>,
        editor: Bounds,
        hostEditorText: Boolean?,
        geometryPoint: Pair<Int, Int>?,
        screenWidth: Int = 0,
        scale: ScreenScale = ScreenScale.FALLBACK,
    ): Decision {
        // ① 正面证据优先：找到"发送"节点就点它自己，不需要再猜容器。
        findSendNode(nodes, editor, scale)?.let { node ->
            return Decision(
                verdict = Verdict.SEND_BY_NODE,
                x = node.box.centerX,
                y = node.box.centerY,
                reason = "同行找到发送节点 ${node.box.toShortString()}",
            )
        }

        // ② 反面证据：输入框还是空的（同行挂着"切换语音"）→ 那颗容器此刻是"切到语音" ✗。
        //
        // 这条**优先于**宿主的文字实证：真机上宿主可能读到的是页面里**别的**输入框
        // （搜索框/残留编辑器），一旦据此放行就会点到"切换语音"。空签名是直接
        // 观察到的界面状态，比间接读到的文字更可信。
        if (showsEmptySignature(nodes, editor, screenWidth, scale)) {
            return Decision(verdict = Verdict.REFUSE, reason = "输入栏仍是空文案签名（$EMPTY_MARKER）")
        }

        // ③ 宿主实证：明确为空 → 拒点。
        if (hostEditorText == false) {
            return Decision(verdict = Verdict.REFUSE, reason = "宿主实测输入框为空")
        }

        // ④ 宿主实证：明确有文案 + 有可点落点 → 放行（按真实几何）。
        if (hostEditorText == true && geometryPoint != null) {
            return Decision(
                verdict = Verdict.SEND_BY_GEOMETRY,
                x = geometryPoint.first,
                y = geometryPoint.second,
                reason = "宿主实测有文案，按真实几何点动作键",
            )
        }

        // ⑤ 证据不足 → 拒点。调用方回退"回车发送"（零误触通道）。
        return Decision(
            verdict = Verdict.REFUSE,
            reason = "既无发送节点也不能确认有文案（hostText=$hostEditorText）",
        )
    }

    private val WHITESPACE = Regex("\\s+")
}
