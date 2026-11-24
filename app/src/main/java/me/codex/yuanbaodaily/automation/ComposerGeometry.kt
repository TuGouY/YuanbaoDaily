package me.codex.yuanbaodaily.automation

/**
 * 输入栏底部那一行的**真实几何**（纯数据，不依赖 `android.graphics.Rect`）。
 *
 * 为什么不用 `Rect`：这套判定要被纯 JVM 单测覆盖（回归测试锁定"写死比例坐标点会被拒绝"），
 * 而 `android.graphics.Rect` 在单测里是空壳，`Rect(...)` 构造与 `width()` 都会返回 0。
 * 用普通 Int 字段既能在真机上直接用，也能在测试里精确断言。
 */
internal data class Bounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val centerX: Int get() = left + width / 2
    val centerY: Int get() = top + height / 2

    fun contains(x: Int, y: Int): Boolean = x in left..right && y in top..bottom

    /**
     * 本矩形是否**完整包住** [other]。
     *
     * 用来识别"把 `＋/更多` 图标整颗包进去的那个容器" —— 真机上它不是发送键
     * （见 [ComposerGeometry.Bar.sendButton] 的说明）。
     */
    fun encloses(other: Bounds): Boolean =
        other.left >= left && other.right <= right && other.top >= top && other.bottom <= bottom
}

/** 日志用：与 `Rect.toShortString()` 同格式，单测里也能跑。 */
internal fun Bounds.toShortString(): String = "[$left,$top][$right,$bottom]"

/**
 * 对话页/能力页底部输入栏的**真实几何**。
 *
 * 为什么需要它：模块此前用"屏幕比例"猜按钮位置（`0.87 × 屏高`、`屏宽 - 172` 之类），
 * 而真机 1272×2800 上实测的布局是
 *
 * ```text
 * [210,2435][922,2645]   EditText      输入框
 * [1062,2435][1202,2645] Button        发送/语音（同一颗按钮，随有无文案切换语义）
 * [950,2498][1034,2582]  切换语音
 * [1090,2498][1174,2582] 更多(+)
 * [98,2498][182,2582]    拍照
 * ```
 *
 * 而比例法算出来的是：`0.87 × 2800 = 2724`，落在输入框**下方 79px** 的提示区；
 * `0.976 × 2800 = 2732` 更是偏出 87px。比例法对屏幕高度、状态栏/导航栏、键盘弹起高度
 * 都敏感，只要有一项对不上就会点空或误触旁边的 `+ / 相机 / 更多` —— 这正是"误触其他按键"。
 *
 * 所以这里**只相信无障碍树报出来的真实 bounds**：找不到节点就返回 null，由调用方
 * 放弃这一步（宁可不点，也不要点错）。
 */
internal object ComposerGeometry {

    /**
     * 输入栏一行的几何快照；所有坐标都是屏幕绝对像素。
     *
     * 注意真机布局的一个反直觉之处：**发送键那颗大容器的内部还嵌着"更多(+)"**。
     * 也就是说 `Button [1062,2435][1202,2645]` 的几何中心 (1132,2540) 恰好落在
     * `更多 [1090,2498][1174,2582]` 里 —— 直接点中心等于点"更多"，这正是误触的来源之一。
     * 所以 [actionX]/[actionY] 不是简单取中心，而是取容器里**避开干扰键的那一小块**的安全点。
     */
    data class Bar(
        val editor: Bounds,
        val sendButton: Bounds?,
        val voiceButton: Bounds?,
        val moreButton: Bounds?,
        val cameraButton: Bounds?,
        val rowCenterY: Int,
        /**
         * 落在**输入框矩形内部**的其它可点节点（图片缩略图、内嵌的发送/删除小按钮等）。
         *
         * 真机实测：图片编辑态下输入框是 `[119,2373][1006,2625]`，内部/同行还叠着
         * `图片1 [133,2387][189,2443]` 和两颗 `Button [1017,2485][1185,2653]`。
         * 点输入框时必须避开它们，否则会点开图片预览或触发发送 —— 这就是"点不到输入框"。
         */
        val overlays: List<Bounds> = emptyList(),
        /**
         * 输入框里**已经有文案**（真机实测的语义翻转开关）。
         *
         * 有文案时元宝会把输入栏右侧那颗按钮从「🎤 切换语音 / ＋ 更多」换成「⬆ 发送」，
         * 而 **`content-desc='更多'` 那颗图标仍然留在原位**（真机 dump：
         * 发送容器 `[866,2435][1034,2645]`，里面 still 有 `更多 [908,2498][992,2582]`）。
         * 也就是说：**空框时"更多"是干扰键，有文案时它只是发送键上的那个箭头图标**。
         *
         * 不区分这两种语义就会出现真机实测的失败：
         * 发送容器中心 (950,2540) 被判成"落在更多里"→ 绕到容器左边缘 (872,2441) 去点
         * → 那颗内层 Button 是 `[880,2435][1020,2645]`，872 落在它**外面** → 点击不生效、
         * 消息发不出去（`evidence/trace_c2`：输入框已清空=false）。
         */
        val textPresent: Boolean = false,
        /**
         * 当前屏幕尺度。所有"像素常数"都由它换算，**不再直接比较绝对像素**。
         *
         * 默认值是参考机型（1272×2800/480dpi），因此纯单测里不传它时行为与改造前一致；
         * 真机上必须传 `AutomationAccessibilityService.screenScale()` 得到的真实尺度，
         * 否则换到 1080/1440/平板宽度的机器上，边缘留白与"同行"容差都会失配。
         */
        val scale: ScreenScale = ScreenScale.FALLBACK,
    ) {
        /**
         * 需要绕开的干扰键。
         *
         * [textPresent] 为 true 时**排除 [moreButton]** —— 那时它是发送键的一部分，
         * 不是干扰（见 [textPresent] 的说明）。
         */
        private val distractors: List<Bounds>
            get() = listOfNotNull(
                voiceButton,
                if (textPresent) null else moreButton,
                cameraButton,
            )

        /**
         * 发送/操作键上的安全落点（避开内嵌的语音/更多/相机）。
         *
         * **注意**：`sendButton == null` 时这里会退化成一个**估算值**
         * （`editor.right + editor.width / 8`），它**没有安全保证**，只用于日志/诊断；
         * [isSafeActionPoint] 对这种 bar 一律返回 false，绝不会真的点出去。
         */
        val actionX: Int
            get() {
                val button = sendButton ?: return editor.right + editor.width / 8
                if (distractors.none { it.contains(button.centerX, button.centerY) }) {
                    return button.centerX
                }
                // 中心被干扰键占了：在按钮内部找一个不在任何干扰键里的 x。
                val margin = edgeMargin()
                val columns = generateSequence(button.left + margin) { it + step() }
                    .takeWhile { it <= button.right - margin }
                val safe = columns.firstOrNull { x ->
                    distractors.none { it.contains(x, button.centerY) }
                }
                return safe ?: button.centerX
            }

        val actionY: Int
            get() {
                val button = sendButton ?: return rowCenterY
                if (distractors.none { it.contains(button.centerX, button.centerY) }) {
                    return button.centerY
                }
                // 纵向也试一下：干扰键只占垂直中段时，贴着按钮上沿/下沿点也能避开。
                val margin = edgeMargin()
                val rows = listOf(
                    button.top + margin,
                    button.bottom - margin,
                    button.centerY,
                )
                val safe = rows.firstOrNull { y ->
                    distractors.none { it.contains(button.centerX, y) }
                }
                return safe ?: button.centerY
            }

        /**
         * 点输入框（取焦点）用的安全落点；解析不出安全位置返回 `null`。
         *
         * 与 [actionX]/[actionY] 的区别：这里是**点输入框**，所以必须落在输入框矩形**内部**，
         * 并且避开：
         * - 落在输入框里的 overlay（图片缩略图、内嵌按钮）—— 点到它们会开图片/触发发送；
         * - 同行的语音/更多/相机 —— 空输入框时它们就贴着输入框右缘，最容易误触。
         *
         * 优先取输入框中心；中心被占就在框内扫一个安全的列（纵向再试上下沿）。
         * 全都不可用则返回 null，由调用方放弃 —— **宁可不聚焦，也不误触**。
         */
        fun editorFocusPoint(): Pair<Int, Int>? {
            val blocked = overlays + distractors
            fun free(x: Int, y: Int): Boolean {
                if (!editor.contains(x, y)) return false
                return blocked.none { it.contains(x, y) }
            }

            if (free(editor.centerX, editor.centerY)) return editor.centerX to editor.centerY

            // 框内逐列扫；每列从上到下再试几个 y（避开只占中段的 overlay）。
            val margin = edgeMargin()
            val columns = generateSequence(editor.left + margin) { it + step() }
                .takeWhile { it <= editor.right - margin }
            for (x in columns) {
                val rows = listOf(editor.centerY, editor.top + margin, editor.bottom - margin)
                val y = rows.firstOrNull { free(x, it) } ?: continue
                return x to y
            }
            return null
        }

        /**
         * 扫描落点时的边缘留白（参考 6px @480dpi = 2dp）。
         *
         * 按 dp 换算：留白的作用是"不要贴着边界点"，属于手指尺度，与屏宽无关。
         */
        private fun edgeMargin(): Int = maxOf(2, scale.dpScaled(EDGE_MARGIN_REF))

        /** 扫描步长（参考 4px @480dpi ≈ 1.3dp），低 dpi 设备上至少 1px。 */
        private fun step(): Int = maxOf(1, scale.dpScaled(STEP_REF))
    }

    /**
     * 从当前窗口的节点里解析输入栏。
     *
     * [editor] 用**类名 + 屏幕区域**判定（元宝上报 `isVisibleToUser=false`，不能用可见性过滤），
     * 同一行里的其余按钮按"与输入框纵向重叠、水平在其右侧"来归类。
     */
    fun resolve(
        nodes: List<ScreenScanner.DumpNode>,
        textPresent: Boolean = false,
        scale: ScreenScale = ScreenScale.FALLBACK,
    ): Bar? {
        if (nodes.isEmpty()) return null
        // **输入栏 = 屏幕上最靠下的那颗 `EditText`**，不是"最高的"那颗。
        //
        // 真机踩坑（`evidence/trace_d5_ask`）：问答结果页上元宝会渲染一颗
        // **高 2016px 的可编辑/可选中文本节点** `[70,588][1202,2604]`。
        // 旧实现取 `maxByOrNull { height }`，它一出现就把真正的输入栏
        // `[70,2138][880,2645]` 挤掉 → 发送键解析失败 → 之后走进写死坐标的兜底 ✗。
        // 输入栏的位置可能随键盘/多行展开上下移动，但它**永远是那一竖列里最靠下**的。
        val editor = nodes
            .filter { it.box.width > 0 && it.box.height > 0 }
            .filter { it.className.contains("EditText") || it.className.contains("TextInput") }
            .maxByOrNull { it.box.top }
            ?: return null
        val eb = editor.box

        // 所有尺寸阈值都由 [scale] 换算：参考机型上等于原常数，其他分辨率按宽度/dp 缩放。
        val rowTolerance = scale.rowTolerance()
        val maxButtonSide = scale.controlMaxSide()
        val minButtonSide = scale.controlMinSide()
        val sendRowBottomTolerance =
            maxOf(scale.dpScaled(SEND_ROW_BOTTOM_TOLERANCE_REF), scale.widthScaled(SEND_ROW_BOTTOM_TOLERANCE_REF))
        val rightGapTolerance = scale.rowTolerance()

        // 与输入框同一行（纵向有重叠）的其它节点。
        val sameRow = nodes.filter { node ->
            val b = node.box
            b.width > 0 && b.height > 0 &&
                node !== editor &&
                b.centerY in (eb.top - rowTolerance)..(eb.bottom + rowTolerance) &&
                b.width <= maxButtonSide && b.height <= maxButtonSide
        }

        fun findButton(keyword: String): ScreenScanner.DumpNode? = sameRow
            .firstOrNull { node ->
                val haystack = (node.label + " " + node.description).lowercase()
                haystack.contains(keyword)
            }

        val voiceNode = findButton("切换语音") ?: findButton("语音")
        val moreNode = findButton("更多") ?: findButton("+")
        val cameraNode = findButton("拍照") ?: findButton("相机")
        val distractorNodes = listOfNotNull(voiceNode, moreNode, cameraNode)

        // 发送键候选：位于输入框右缘之外、**与底栏那一行纵向重叠**、尺寸像按钮、
        // 且不是"把 ＋/更多 图标整个包住的容器"。
        //
        // **1.1.5 修正（真机 `evidence/trace_d6_ask` 实证）**：行锚点必须用
        // `＋/更多`（或`语音`）图标，**不是**输入框自己的纵向范围。
        //
        // 原因：输入框在多行/长文案时会**往上长**（实测 `[70,2138][880,2645]`，高 507px），
        // 于是"落在输入框纵向范围内"这个条件会同时收进真正的底栏按钮 **和**
        // 页面右侧一颗 70×70 的小节点 `[1104,2166][1174,2236]`（y 远高于底栏）。
        // 旧规则只要求"在输入框右侧 + 取最靠右"，那颗小节点 left=1104 比真发送键的
        // 1027 更靠右 → 被选中 → **8 次点击全打在它身上，消息一次都没发出去** ✗。
        //
        // `＋/更多` 与发送 `⬆` 真机上**并排在同一条底栏**，所以拿它做行锚点后，
        // 位置明显偏上的那些节点会被结构性地排除掉。
        val moreBox = moreNode?.box
        val rowAnchor = moreBox ?: voiceNode?.box ?: eb
        val sendCandidates = nodes
            .filter { node ->
                val b = node.box
                b.width >= minButtonSide && b.height >= minButtonSide &&
                    b.width <= maxButtonSide && b.height <= maxButtonSide &&
                    b.left >= eb.right - rightGapTolerance &&
                    verticallyOverlaps(b, rowAnchor) &&
                    // **必须与输入框底部对齐**（1.1.9，真机截图 `evidence/w5_buttons.jpg` 实证）。
                    //
                    // 写作页里有个坑：无障碍树在 `[1104,1361][1174,1431]` 报了一颗
                    // **带文字 `全屏输入`** 的节点，它的 `left=1104` 比真正的发送 ⬆ 更靠右，
                    // 于是"取最靠右"的规则把它选中了 —— 但**截图里那个位置是一片空白**
                    // （红框），点它等于什么都没点，输入框里文案一字未少。
                    //
                    // 真正的发送 ⬆ 是 `[1027,1459][1195,1627]`（绿框，中心 1111,1543），
                    // 它与输入框 `[70,1333][1020,1648]` 的**底部只差 21px**；
                    // 而 `全屏输入` 差了 217px、`View[1055,1312][1223,1480]` 差了 168px
                    // —— 全部被这条约束排除。
                    // 阈值取 40：对话页真发送差 21 ✓；空输入框时那颗 `切换语音` 差 63，
                    // 会被排除（它本来也在 distractor 名单里，这是第二道保险）。
                    kotlin.math.abs(b.bottom - eb.bottom) <= sendRowBottomTolerance &&
                    distractorNodes.none { it === node } &&
                    (moreBox == null || !b.encloses(moreBox))
            }
        // **优先"无文字、无描述"的候选**：真正的发送 ⬆ 在所有真机 dump 里
        // 都是无文字无描述的（对话页 `Button[1027,2456][1195,2624]`、
        // 写作页 `Button[1027,1459][1195,1627]`），而干扰项 `全屏输入` 带文字。
        // 一个都没有时才回落到全部候选（防止某些版本的发送键真的带上了 content-desc）。
        val send = sendCandidates
            .filter { it.label.isEmpty() && it.description.isEmpty() }
            .ifEmpty { sendCandidates }
            .maxByOrNull { it.box.left }
            ?.box

        // 点输入框时要避开的 overlay：任何**与输入框矩形相交**的同行可点节点。
        // 实测图片编辑态输入框 [119,2373][1006,2625] 里就叠着 `图片1 [133,2387][189,2443]`，
        // 点中心还有可能踩到右侧按钮 —— 这些都不能当"点输入框"的落点。
        val overlays = sameRow
            .filter { node -> node.clickable }
            .filter { node -> distractorNodes.none { it === node } }
            .map { it.box }
            .filter { box -> intersects(eb, box) }

        return Bar(
            editor = eb,
            sendButton = send,
            voiceButton = voiceNode?.box,
            moreButton = moreNode?.box,
            cameraButton = cameraNode?.box,
            rowCenterY = eb.centerY,
            overlays = overlays,
            textPresent = textPresent,
            scale = scale,
        )
    }

    /** 两个矩形是否有实质重叠（用于判定输入框里的 overlay）。 */
    private fun intersects(a: Bounds, b: Bounds): Boolean {
        val overlapX = minOf(a.right, b.right) - maxOf(a.left, b.left)
        val overlapY = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)
        return overlapX > 0 && overlapY > 0
    }

    /** 两个矩形在**纵向**上是否有重叠（用于判定"是否同一条底栏"）。 */
    private fun verticallyOverlaps(a: Bounds, b: Bounds): Boolean =
        minOf(a.bottom, b.bottom) - maxOf(a.top, b.top) > 0

    /**
     * 目标点是否**安全**：必须落在输入栏行内、在输入框右侧那条按钮带里、且**不落在任何
     * 干扰键（语音 / 更多 / 相机）内部**。
     *
     * 用来在真正派发手势之前拦掉"点到底栏/提示区/其它 App 覆盖层"的坐标。
     * 这是把"误触"从"尽量少发生"变成"结构上不可能"的那道闸。
     */
    fun isSafeActionPoint(bar: Bar, x: Int, y: Int): Boolean {
        // ⓪ **解析不出发送键 = 结构上没有可用的动作点**。
        //    [Bar.actionX] 在 `sendButton == null` 时会退化成一个估算值
        //    （`editor.right + editor.width / 8`），那个值**没有安全保证**，
        //    绝不能直接拿去点 —— 这里必须一刀切掉。
        if (bar.sendButton == null) return false
        // ⓪' **动作点必须落在"我们认定的那颗发送键"矩形内部**。
        //     这条把"落点飘到同行其它地方"整体封死：真机有文案时同一行里
        //     既有 `⬆ [1027,2456][1195,2624]`，也有包着 `＋` 的容器 `[866,2435][1034,2645]`；
        //     只判"不在干扰键里"会把容器中心 (950,2540) 放行 ✗，加上这一条就不会。
        if (!bar.sendButton.contains(x, y)) return false
        // ⓵ **空文案时右侧那颗按钮根本不是发送键**（是 🎤切换语音 / ＋更多）。
        //
        // 真机实测（`evidence/trace_c4`）：空框时 [Bar.actionX] 算出来是 `(1054,2441)` ——
        // 它落在 `切换语音` 容器 `[1048,2435][1216,2645]` 里，几何上"恰好"避开了内层那颗
        // `切换语音 [950,2498][1034,2582]` 图标矩形，于是旧的干扰键判据认为它"安全"。
        // 但点下去容器照样把输入栏切成"按住 说话" ✗ —— 这就是用户报的误触。
        //
        // 语义上这才是正确的判据：**空框时这一带没有发送键**，所以没有安全落点。
        // 只有在解析不出语音切换键时才放行：能力页（P图/写作）的输入栏没有那颗键，
        // 它的发送 ⬆ 与文案有无无关，不能跟着一起否掉。
        if (!bar.textPresent && bar.voiceButton != null) return false
        val scale = bar.scale
        if (y !in (bar.editor.top - scale.rowTolerance())..(bar.editor.bottom + scale.rowTolerance())) return false
        if (x < bar.editor.right - scale.rowTolerance()) return false
        // 明确排除已知的干扰键区域（语音 / 相机；有文案时"更多"那颗其实是发送箭头，
        // 必须放行 —— 见 Bar.textPresent 的说明）。
        val blockers = listOfNotNull(
            bar.voiceButton,
            if (bar.textPresent) null else bar.moreButton,
            bar.cameraButton,
        )
        for (distractor in blockers) {
            if (distractor.contains(x, y)) return false
        }
        return true
    }

    /**
     * 目标点是否可以安全地用来**聚焦输入框**：必须落在输入框矩形内部，
     * 且不在任何 overlay / 干扰键上。
     *
     * 与 [isSafeActionPoint] 的区别：那个是"点发送键附近的动作点"，
     * 这个是"点输入框本身"，所以必须**在框内**（点框外会落到相邻控件上）。
     */
    fun isSafeFocusPoint(bar: Bar, x: Int, y: Int): Boolean {
        if (!bar.editor.contains(x, y)) return false
        for (blocked in bar.overlays + listOfNotNull(bar.voiceButton, bar.moreButton, bar.cameraButton)) {
            if (blocked.contains(x, y)) return false
        }
        return true
    }

    /**
     * 输入栏里那颗"语音/键盘"切换键的真实位置（用于把界面从语音模式切回键盘）。
     *
     * 真机实测两种形态：
     * - 语音模式：左侧 `[56,2435][224,2645]` 是切换键（点的就是它），右侧图标 desc=`切换键盘`；
     * - 键盘模式：左侧 `[56,2435][224,2645]` 是"+"，右侧图标 desc=`切换语音`。
     *
     * 所以切换键的**判据是左侧那颗**（desc 里带切换语义、且位于输入栏最左侧）；
     * 右侧那颗只是图标，点它同样能切换但语义上更像"发送区邻居"，不优先。
     */
    fun voiceToggle(nodes: List<ScreenScanner.DumpNode>): Bounds? {
        val candidates = nodes.filter { node ->
            val hay = (node.text + " " + node.description).lowercase()
            (hay.contains("切换语音") || hay.contains("切换键盘")) && node.box.width > 0
        }
        if (candidates.isEmpty()) return null
        // 最靠左的那颗是真正的切换键。
        return candidates.minByOrNull { it.box.left }?.box
    }

    /**
     * 当前输入栏是否停在**语音模式**（"按住说话"）。
     *
     * 真机实测：语音模式下输入栏区域是 `[224,2435][1048,2645] text='按住 说话'`，
     * **没有 EditText**。此时去点"输入框"是点不到的 —— 旧代码会盲点写死坐标，
     * 正好踩到左侧的 `切换语音/键盘` 键或右侧的 `更多`，这就是用户报的误触。
     * 正确做法是先把它切回键盘模式，再点输入框。
     */
    fun isVoiceMode(nodes: List<ScreenScanner.DumpNode>): Boolean {
        // 只要有输入框，就绝不是语音模式（语音模式下 EditText 不存在）。
        val hasEditor = nodes.any {
            (it.className.contains("EditText") || it.className.contains("TextInput")) &&
                it.box.width > 0 && it.box.height > 0
        }
        if (hasEditor) return false

        val holdToTalk = nodes.any { node ->
            // 必须**整体相等**：空框占位符"发消息或按住说话..."里含这个子串，
            // 用 contains 会把空框误判成语音模式（见 ComposerState.isHoldToTalkLabel）。
            ComposerState.isHoldToTalkLabel(node.text, node.description) &&
                node.box.width > 0 && node.box.height > 0
        }
        if (holdToTalk) return true
        // 没有输入框、且右侧图标是"切换键盘" → 判定为语音模式。
        return nodes.any { node ->
            (node.text + " " + node.description).contains("切换键盘") && node.box.width > 0
        }
    }

    private val WHITESPACE = Regex("\\s+")

    /** 输入框中心点，用于"聚焦输入框"这类绝不会误触的操作。 */
    fun editorCenter(bar: Bar): Pair<Int, Int> = bar.editor.centerX to bar.editor.centerY

    /**
     * 发送键底部与输入框底部的最大允许差值（**参考机型像素**，由 [ScreenScale] 换算）。
     *
     * 真机实证（`evidence/w5_buttons.jpg`）：真发送 ⬆ 与输入框底部差 **21px**；
     * 写作页那颗干扰节点 `全屏输入` 差 **217px**（截图里是空白）；`切换语音` 差 63px。
     * 参考值取 40 —— 放行 21，同时把 63/217 挡在外面。
     *
     * 换算时取 `max(dp 等比, 屏宽等比)`：
     * - 触控键盘上，这个差值是"按钮贴底对齐"的排版留白，跟 dp 走；
     * - 大屏平板上布局等比放大，差值也跟着放大，只按 dp 会偏小 → 用屏宽等比兜住。
     */
    private const val SEND_ROW_BOTTOM_TOLERANCE_REF = 40

    /** 在发送键内部找安全落点时的边距与步长（**参考机型像素**，按 dp 换算）。 */
    private const val EDGE_MARGIN_REF = 6
    private const val STEP_REF = 4
}
