package me.codex.yuanbaodaily.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 误触回归测试。
 *
 * 喂进去的是**真机 dump 实测的节点 bounds**（PLQ110 / 1272x2800 / 元宝 2.87.20，
 * 见 evidence/dump_home.xml），锁定四件事：
 * 1. 解析器能从真实布局里挑出正确的发送键；
 * 2. 旧的写死比例坐标算出来的点会被判为**不安全**（这正是误触的来源）；
 * 3. 语音/更多/相机这些干扰键永远不是安全落点；
 * 4. 最终落点一定满足 [ComposerGeometry.isSafeActionPoint] —— 这是"结构上不误触"的保证。
 *
 * 注意：断言只读 [Bounds] 的 Int 字段，不调用 `Rect.centerX()/contains()` 这类方法 ——
 * 它们在纯 JVM 单测里是空壳。
 */
class ComposerGeometryTest {

    /** 实测：输入框 [210,2435][922,2645]。 */
    private val editor = node(
        className = "android.widget.EditText",
        left = 210, top = 2435, right = 922, bottom = 2645,
        label = "发消息或按住说话...",
    )

    /** 实测：发送/操作键 [1062,2435][1202,2645]（`android.widget.Button`）。 */
    private val sendButton = node(
        className = "android.widget.Button",
        left = 1062, top = 2435, right = 1202, bottom = 2645,
    )

    /** 实测：包住发送键的容器 [1048,2435][1216,2645]（同一行、更宽，也符合"候选"条件）。 */
    private val sendWrapper = node(
        className = "android.view.View",
        left = 1048, top = 2435, right = 1216, bottom = 2645,
    )

    /** 实测：切换语音 [950,2498][1034,2582] —— 在输入框右侧，是最容易顶替发送键的干扰项。 */
    private val voice = node(
        className = "android.widget.ImageView",
        left = 950, top = 2498, right = 1034, bottom = 2582,
        description = "切换语音",
    )

    /** 实测：更多(+) [1090,2498][1174,2582] —— **嵌在发送键矩形内部**（含发送键中心点）。 */
    private val more = node(
        className = "android.view.View",
        left = 1090, top = 2498, right = 1174, bottom = 2582,
        description = "更多",
    )

    /** 实测：拍照 [98,2498][182,2582]。 */
    private val camera = node(
        className = "android.view.View",
        left = 98, top = 2498, right = 182, bottom = 2582,
        description = "拍照",
    )

    private val realNodes = listOf(editor, sendButton, sendWrapper, voice, more, camera)

    /** 空框态（真机 `evidence/d_home2.xml`）：**没有**发送键，右侧那颗是「＋/切换语音」容器。 */
    private val realEmptyBar = ComposerGeometry.resolve(realNodes, textPresent = false)

    // ---------------------------------------------------------------
    // 有文案态（真机 `evidence/trace_c2` + 用户截屏实测）：
    //   输入框展开成 [70,2435][866,2645]，`＋` 挪到 [908,2498][992,2582]，
    //   而**真正的 ⬆ 发送**出现在最右侧 `[1027,2456][1195,2624]`（截图里那颗深色圆钮）。
    // ---------------------------------------------------------------

    private val filledEditor = node(
        className = "android.widget.EditText",
        left = 70, top = 2435, right = 866, bottom = 2645,
        label = "自检写入composer123",
    )

    /** `[866,2435][1034,2645]` —— 它**整个包住**下面的 `＋` 图标，所以是 ＋ 容器、不是发送键。 */
    private val filledPlusWrapper = node(
        className = "android.view.View",
        left = 866, top = 2435, right = 1034, bottom = 2645,
    )

    /** `＋` 图标本体（`content-desc='更多'`）。 */
    private val filledPlus = node(
        className = "android.view.View",
        left = 908, top = 2498, right = 992, bottom = 2582,
        description = "更多",
    )

    /** 容器里那颗内层 Button（旧代码曾把它左边缘外侧的 872 当落点 → 点不中）。 */
    private val filledInnerButton = node(
        className = "android.widget.Button",
        left = 880, top = 2435, right = 1020, bottom = 2645,
    )

    /** **真正的 ⬆ 发送键**。 */
    private val filledSendArrow = node(
        className = "android.widget.Button",
        left = 1027, top = 2456, right = 1195, bottom = 2624,
    )

    private val filledNodes =
        listOf(filledEditor, filledPlusWrapper, filledPlus, filledInnerButton, filledSendArrow)

    private val realFilledBar = ComposerGeometry.resolve(filledNodes, textPresent = true)

    @Test
    fun resolvesRealEmptyComposerLayoutWithoutInventingASendButton() {
        val bar = ComposerGeometry.resolve(realNodes)
        assertNotNull("应从真实布局里解析出输入栏", bar)
        requireNotNull(bar)
        assertEquals(210, bar.editor.left)
        assertEquals(2435, bar.editor.top)
        assertEquals(922, bar.editor.right)
        assertEquals(2645, bar.editor.bottom)
        // **1.1.4 关键**：空框时那颗 `[1048,2435][1216,2645]` 整个包着 `更多(+)`
        // → 它是「＋/切换语音」容器，绝不能当成发送键。识别不出发送键就必须
        // 老老实实返回 null（由 isSafeActionPoint 拒点），**不许**退化成估算坐标。
        assertNull("空框时不应凭空认出一颗发送键，实际=${bar.sendButton}", bar.sendButton)
        assertEquals(1034, bar.voiceButton?.right)
        assertEquals(1090, bar.moreButton?.left)
    }

    @Test
    fun emptyComposerNeverYieldsASafeActionPoint() {
        val bar = requireNotNull(realEmptyBar)
        assertNotNull("前提：空框态确实解析出了语音切换键", bar.voiceButton)
        assertNull("前提：空框态解析不出发送键", bar.sendButton)
        // 真机算出来的那个点，必须被拒。
        assertFalse(
            "空框时右侧动作点必须被拒（那就是切换语音 ✗）",
            ComposerGeometry.isSafeActionPoint(bar, 1054, 2441),
        )
        // 以及算法自己给出的落点也必须被拒 —— 空框时它本来就不该被用来点。
        assertFalse(
            "空框时 bar.actionX/actionY 不能通过安全校验",
            ComposerGeometry.isSafeActionPoint(bar, bar.actionX, bar.actionY),
        )
        // 语音键裸中心同样不行。
        assertFalse("语音图标中心不是安全落点", ComposerGeometry.isSafeActionPoint(bar, 992, 2540))
        // ＋容器 / 相机 也不行。
        assertFalse("更多(+)不是安全落点", ComposerGeometry.isSafeActionPoint(bar, 1132, 2540))
        assertFalse("相机键不是安全落点", ComposerGeometry.isSafeActionPoint(bar, 140, 2540))
    }

    /**
     * **1.1.4 核心回归**：有文案时发送键是**最右侧那颗 ⬆**，
     * 而不是"最靠左、把 ＋ 图标整个包住的那个容器"。
     *
     * 旧实现 `sortedBy { left }.first()` 在真机上必然选中 `[866,2435][1034,2645]`
     * （中心 950）—— 那是 `＋`。用户截屏 `evidence/d3_composer_strip.jpg` 证实：
     * 有文案时屏幕 x≈1107 那颗深色圆钮才是发送 ⬆，而 950 处是 `＋`。
     * 点 950 的结果是打开附件面板 → 输入栏被替换 → 假成功（`trace_d3` 实测）。
     */
    @Test
    fun filledComposerPicksTheRightmostArrowNotThePlusContainer() {
        val bar = requireNotNull(realFilledBar)
        requireNotNull(bar.sendButton)
        assertEquals("发送键左边界应为真正那颗 ⬆ 的 1027", 1027, bar.sendButton!!.left)
        assertEquals("发送键右边界应为真正那颗 ⬆ 的 1195", 1195, bar.sendButton!!.right)
        assertEquals("动作点 x 应为 ⬆ 的中心", 1111, bar.actionX)
        assertEquals("动作点 y 应为 ⬆ 的中心", 2540, bar.actionY)
        assertTrue(
            "该落点必须通过安全校验",
            ComposerGeometry.isSafeActionPoint(bar, bar.actionX, bar.actionY),
        )
        // 反面：`＋` 容器中心 950 绝不能当落点 —— 它落在 ＋ 图标那一带。
        assertFalse(
            "包着 ＋ 的容器中心 (950,2540) 不能当发送落点",
            ComposerGeometry.isSafeActionPoint(bar, 950, 2540),
        )
    }

    /**
     * 只有 `＋` 容器、**没有** ⬆ 时（例如布局中间帧），必须解析不出发送键 → 无安全落点。
     *
     * 这是"宁可不点，也不误触"的结构保证：宁可不发送，也不能把 `＋` 当成发送键。
     */
    @Test
    fun plusContainerAloneIsNeverASendTarget() {
        val onlyPlus = listOf(filledEditor, filledPlusWrapper, filledPlus, filledInnerButton)
        val bar = ComposerGeometry.resolve(onlyPlus, textPresent = true)
        requireNotNull(bar)
        assertNull("只有 ＋ 容器时不应认出发送键，实际=${bar.sendButton}", bar.sendButton)
        assertFalse(
            "解析不出发送键 → 没有安全落点",
            ComposerGeometry.isSafeActionPoint(bar, bar.actionX, bar.actionY),
        )
        assertFalse("＋ 容器中心也不能点", ComposerGeometry.isSafeActionPoint(bar, 950, 2540))
    }

    @Test
    fun oldHardcodedFractionsAreRejectedAsUnsafe() {
        // 用"有文案"的语义跑，避免被上面那条"空框直接拒"的短路掩盖掉几何判定本身。
        val bar = requireNotNull(realFilledBar)
        // 1.1.0 里 `tapChatSend` 用的 0.87 / 0.973 → (1106, 2724)，落在输入框下方 79px。
        assertFalse(
            "旧的 (0.87, 0.973) 落点必须被判为不安全",
            ComposerGeometry.isSafeActionPoint(bar, (1272 * 0.87f).toInt(), (2800 * 0.973f).toInt()),
        )
        // `tapEditorCenter` 的 (0.445, 0.976) → (566, 2732)，落在框外 87px。
        assertFalse(
            "旧的 (0.445, 0.976) 回退落点必须被判为不安全",
            ComposerGeometry.isSafeActionPoint(bar, (1272 * 0.445f).toInt(), (2800 * 0.976f).toInt()),
        )
        // `CHAT_SEND_X` 配 row=0.60 的兜底 → (1106, 1680)，在页面上半部分的聊天区。
        assertFalse(
            "旧的 row=0.60 兜底落点必须被判为不安全",
            ComposerGeometry.isSafeActionPoint(bar, (1272 * 0.87f).toInt(), (2800 * 0.60f).toInt()),
        )
    }

    @Test
    fun filledComposerSendButtonIsASafePoint() {
        // 必须在"已确认有文案"的语义下跑：空框时同一颗按钮是语音切换键，
        // 落点被 [emptyComposerNeverYieldsASafeActionPoint] 明确否掉（这是正确的）。
        val bar = requireNotNull(realFilledBar)
        assertTrue(
            "有文案时解析出来的落点必须安全",
            ComposerGeometry.isSafeActionPoint(bar, bar.actionX, bar.actionY),
        )
        // 且落点必须落在发送键矩形内（不能飘到别处）。
        assertTrue(
            "落点必须落在发送键内 x=${bar.actionX} y=${bar.actionY}",
            bar.sendButton!!.contains(bar.actionX, bar.actionY),
        )
    }

    @Test
    fun returnsNullInsteadOfGuessingWhenNoEditor() {
        val noEditor = listOf(sendButton, voice, more)
        assertNull("看不到输入框时必须返回 null（宁可不点）", ComposerGeometry.resolve(noEditor))
    }

    // ---------------------------------------------------------------
    // 点输入框（取焦点）的回归：这是用户反馈"点击不到输入框、反而误触 +/语音"的那条路径。
    // 布局取自真机 dump（图片编辑态，输入框被图片缩略图叠住）。
    // ---------------------------------------------------------------

    /** 实测：图片编辑态输入框 [119,2373][1006,2625]。 */
    private val peEditor = node(
        className = "android.widget.EditText",
        left = 119, top = 2373, right = 1006, bottom = 2625,
        label = "请编辑这张图片：提亮花束主体并优化色彩层次",
    )

    /** 实测：输入框**内部**的图片缩略图 [133,2387][189,2443]，可点。 */
    private val peThumb = node(
        className = "android.view.View",
        left = 133, top = 2387, right = 189, bottom = 2443,
        description = "图片1",
    )

    /** 实测：输入框右侧第 1 颗 Button [1016,2338][1184,2506]。 */
    private val peButton1 = node(
        className = "android.widget.Button",
        left = 1016, top = 2338, right = 1184, bottom = 2506,
    )

    /** 实测：输入框右侧第 2 颗 Button [1017,2485][1185,2653]。 */
    private val peButton2 = node(
        className = "android.widget.Button",
        left = 1017, top = 2485, right = 1185, bottom = 2653,
    )

    private val picEditNodes = listOf(peEditor, peThumb, peButton1, peButton2)

    @Test
    fun resolvesEditorOnPictureEditLayout() {
        val bar = ComposerGeometry.resolve(picEditNodes)
        assertNotNull("图片编辑态也应能解析出输入框", bar)
        requireNotNull(bar)
        assertEquals(119, bar.editor.left)
        assertEquals(1006, bar.editor.right)
    }

    @Test
    fun editorFocusPointIsInsideEditor() {
        val bar = ComposerGeometry.resolve(picEditNodes)
        requireNotNull(bar)
        val point = bar.editorFocusPoint()
        assertNotNull("应能在输入框内找到安全聚焦点", point)
        val (x, y) = requireNotNull(point)
        assertTrue("聚焦点必须在输入框内 xy=$x,$y", bar.editor.contains(x, y))
        assertTrue(
            "聚焦点必须通过 isSafeFocusPoint 校验",
            ComposerGeometry.isSafeFocusPoint(bar, x, y),
        )
        // 绝不能落在图片缩略图上（点到它会打开图片预览）。
        assertFalse("聚焦点不能落在图片缩略图上", peThumb.bounds.contains(x, y))
    }

    @Test
    fun editorFocusPointAvoidsOverlappingThumbnail() {
        // 构造一个"中心正好被缩略图占住"的输入框，验证算法会绕开。
        val tinyEditor = node(
            className = "android.widget.EditText",
            left = 100, top = 2400, right = 300, bottom = 2500,
        )
        val centerThumb = node(
            className = "android.view.View",
            left = 180, top = 2440, right = 220, bottom = 2460,
            description = "图片1",
        )
        val bar = ComposerGeometry.resolve(listOf(tinyEditor, centerThumb))
        requireNotNull(bar)
        val (x, y) = requireNotNull(bar.editorFocusPoint())
        assertFalse(
            "聚焦点必须避开占住中心的缩略图 xy=$x,$y",
            centerThumb.bounds.contains(x, y),
        )
        assertTrue("且仍在输入框内", bar.editor.contains(x, y))
    }

    @Test
    fun isSafeFocusPointRejectsPointsOutsideEditor() {
        val bar = ComposerGeometry.resolve(picEditNodes)
        requireNotNull(bar)
        // 输入框右边的按钮：虽然同属输入栏行，但点它 = 触发按钮，不是聚焦。
        assertFalse(
            "输入框外的点不能算安全聚焦点",
            ComposerGeometry.isSafeFocusPoint(bar, 1100, 2420),
        )
        // 缩略图上也不行。
        assertFalse(
            "缩略图上的点不能算安全聚焦点",
            ComposerGeometry.isSafeFocusPoint(bar, 160, 2415),
        )
        // 框内空白处可以。
        assertTrue(
            "框内空白处应可聚焦",
            ComposerGeometry.isSafeFocusPoint(bar, bar.editor.centerX, bar.editor.bottom - 20),
        )
    }

    @Test
    fun emptyComposerDoesNotFallBackToHardcodedPoint() {
        // 关键回归：输入框解析不出来时，绝不能给出可用的聚焦点。
        // 1.1.0 在这里盲点 (0.445, 0.976)，空输入框时正好踩到 +/语音 —— 这就是用户报的误触。
        val noEditor = listOf(peThumb, peButton1, peButton2)
        assertNull("解析不出输入框时 resolve 必须返回 null", ComposerGeometry.resolve(noEditor))

        // 而旧写死点在真机上是 (566, 2732)：
        // - 若真机停在普通对话页，它落在输入框 [210,2435][922,2645] 下方 87px，框外；
        // - 若停在键盘弹起态（输入框上移到 y≈1438），它离输入框更远。
        // 两种情况下它都不该被视为"可以点输入框"。
        val normalBar = ComposerGeometry.resolve(realNodes)
        requireNotNull(normalBar)
        assertFalse(
            "旧的 (0.445,0.976) 盲点不能作为聚焦点",
            ComposerGeometry.isSafeFocusPoint(normalBar, (1272 * 0.445f).toInt(), (2800 * 0.976f).toInt()),
        )
    }

    // ---------------------------------------------------------------
    // 语音模式识别：用户在语音模式下点"输入框"必然点不到，
    // 旧代码会盲点写死坐标踩到"切换语音/键盘"键 —— 必须先切回键盘模式。
    // 布局取自真机 dump（语音模式）。
    // ---------------------------------------------------------------

    /** 实测（语音模式）：输入栏位置显示"按住 说话"，没有 EditText。 */
    private val holdToTalk = node(
        className = "android.view.View",
        left = 224, top = 2435, right = 1048, bottom = 2645,
    )

    private val holdToTalkLabel = node(
        className = "android.widget.TextView",
        left = 512, top = 2498, right = 761, bottom = 2582,
        label = "按住 说话",
    )

    /** 实测（语音模式）：左侧切换键 [56,2435][224,2645]，可点。 */
    private val voiceToggleSwitch = node(
        className = "android.view.View",
        left = 56, top = 2435, right = 224, bottom = 2645,
    )

    /** 实测（语音模式）：右侧图标是"切换键盘"（键盘模式下才叫"切换语音"）。 */
    private val toggleIcon = node(
        className = "android.widget.ImageView",
        left = 950, top = 2498, right = 1034, bottom = 2582,
        description = "切换键盘",
    )

    private val voiceModeNodes = listOf(
        holdToTalk, holdToTalkLabel, voiceToggleSwitch, toggleIcon,
    )

    @Test
    fun detectsVoiceModeWhenNoEditorButHoldToTalk() {
        assertTrue(
            "有'按住 说话'时应识别为语音模式",
            ComposerGeometry.isVoiceMode(voiceModeNodes),
        )
        // 语音模式下解析不出输入框 —— 这正是"点不到输入框"的现场。
        assertNull(
            "语音模式下没有输入框，resolve 必须返回 null",
            ComposerGeometry.resolve(voiceModeNodes),
        )
    }

    @Test
    fun keyboardModeIsNotMisdetectedAsVoiceMode() {
        assertFalse(
            "键盘模式（有 EditText）不能被误判为语音模式",
            ComposerGeometry.isVoiceMode(realNodes),
        )
        assertFalse(
            "图片编辑态也不能被误判为语音模式",
            ComposerGeometry.isVoiceMode(picEditNodes),
        )
    }

    /**
     * 回归：空框占位符 `发消息或按住说话...` **含有**"按住说话"这三个字。
     *
     * 旧判据用 `hay.contains("按住说话")`，只差 `hasEditor` 那一道兜底就能误判 ——
     * 一旦判成语音模式，`ensureKeyboardMode` 就会去点"切换语音"，
     * 把用户从键盘模式**切进**语音界面（正是用户报的误触）。
     */
    @Test
    fun placeholderDoesNotLookLikeHoldToTalk() {
        assertFalse(
            "占位符不是语音模式标签",
            ComposerState.isHoldToTalkLabel("发消息或按住说话..."),
        )
        assertTrue(
            "真正的语音模式标签整体相等",
            ComposerState.isHoldToTalkLabel("按住 说话"),
        )
        // 没有 EditText 的空框（极端情况）也不能被判成语音模式。
        val placeholderOnly = listOf(
            node(
                className = "android.widget.TextView",
                left = 224, top = 2502, right = 714, bottom = 2579,
                label = "发消息或按住说话...",
            ),
            voice,
        )
        assertFalse(
            "只有占位符 + 切换语音图标 → 空框，不是语音模式",
            ComposerGeometry.isVoiceMode(placeholderOnly),
        )
    }

    @Test
    fun findsVoiceToggleInVoiceMode() {
        val toggle = ComposerGeometry.voiceToggle(voiceModeNodes)
        assertNotNull("语音模式下应能找到切换键", toggle)
        requireNotNull(toggle)
        // voiceModeNodes 里只有右侧那颗"切换键盘"图标带切换语义。
        assertEquals(950, toggle.left)
        assertEquals(1034, toggle.right)
    }

    @Test
    fun voiceTogglePrefersLeftmostWhenBothPresent() {
        // 真机上左侧 [56,224] 与右侧 [950,1034] 都可能带切换语义；
        // 左侧那颗才是输入栏里的主切换键，应优先选它。
        val leftSwitch = node(
            className = "android.view.View",
            left = 56, top = 2435, right = 224, bottom = 2645,
            description = "切换键盘",
        )
        val found = ComposerGeometry.voiceToggle(listOf(toggleIcon, leftSwitch))
        assertNotNull(found)
        assertEquals("应优先取最左侧的切换键", 56, found?.left)
    }

    @Test
    fun voiceToggleAcceptsBothSwitchLabels() {
        // 键盘模式下图标文案是"切换语音"，同样要能找到（用于来回切换）。
        val keyboardModeToggle = node(
            className = "android.widget.ImageView",
            left = 950, top = 2498, right = 1034, bottom = 2582,
            description = "切换语音",
        )
        val found = ComposerGeometry.voiceToggle(listOf(editor, keyboardModeToggle))
        assertNotNull("'切换语音'文案也要能识别为切换键", found)
        assertEquals(950, found?.left)
    }

    /**
     * 回归：**有文案**时"更多"那颗图标其实是发送键上的箭头，不是干扰键。
     *
     * 真机 dump（`evidence/trace_c2/001_tap_send_safe_872_2441.xml`）：
     * ```text
     * EditText '自检写入composer123' [70,2435][866,2645]
     * View  clickable=true           [866,2435][1034,2645]   ← 发送键容器
     * View  desc='更多'               [908,2498][992,2582]   ← 容器里那颗箭头图标
     * ```
     *
     * 不区分语义时：容器中心 (950,2540) 落在"更多"里 → 绕到容器左边缘 (872,2441)，
     * 而内层 `Button [880,2435][1020,2645]` 从 880 开始 —— 872 在它**外面**，
     * 点击不生效、消息发不出去（真机实测 `输入框已清空=false`）。
     */
    @Test
    fun filledComposerUsesSendButtonCenterNotTheLeftEdge() {
        // 真机有文案态的几何（`evidence/trace_c2` + 用户截屏）：容器 `[866,2435][1034,2645]`
        // 里包着 `＋ [908,2498][992,2582]`，**真正的 ⬆ 在最右侧 [1027,2456][1195,2624]**。
        val bar = requireNotNull(realFilledBar)
        assertEquals("有文案时动作点应取真正 ⬆ 的中心 x", 1111, bar.actionX)
        assertEquals("有文案时动作点应取真正 ⬆ 的中心 y", 2540, bar.actionY)
        assertTrue(
            "该落点必须通过安全校验",
            ComposerGeometry.isSafeActionPoint(bar, bar.actionX, bar.actionY),
        )
        // 旧实现绕到容器左边缘外侧 (872,2441) 去点 —— 那颗内层 Button 从 880 开始，
        // 872 在它外面，点了等于没点（旧日志 `sendSafe xy=872,2441` 的来历）。
        assertFalse(
            "旧实现的绕行落点 (872,2441) 必须被拒",
            ComposerGeometry.isSafeActionPoint(bar, 872, 2441),
        )
    }

    /**
     * **空框**时"＋/更多"是真干扰键；而且此时**认不出发送键** → 直接没有安全落点。
     *
     * 注意这里的 fixture **没有** `切换语音` 图标（模拟能力页那种输入栏），
     * 用来单独验证"＋容器不能被当发送键"这条结构性判据。
     */
    @Test
    fun emptyComposerWithoutVoiceIconStillRefusesThePlusContainer() {
        val empty = listOf(
            node("android.widget.EditText", 210, 2435, 922, 2645),
            node("android.view.View", 1048, 2435, 1216, 2645),
            node("android.view.View", 1090, 2498, 1174, 2582, description = "更多"),
        )
        val bar = ComposerGeometry.resolve(empty, textPresent = false)
        requireNotNull(bar)
        assertNull("包着 ＋ 的容器不能被认成发送键，实际=${bar.sendButton}", bar.sendButton)
        assertFalse(
            "没有发送键 → 没有安全落点",
            ComposerGeometry.isSafeActionPoint(bar, bar.actionX, bar.actionY),
        )
        assertFalse(
            "＋ 图标中心 (1132,2540) 不能点",
            ComposerGeometry.isSafeActionPoint(bar, 1132, 2540),
        )
    }

    /**
     * **1.1.5 核心回归**（真机 `evidence/trace_d6_ask`）：长文案把输入框撑高到
     * `[70,2138][880,2645]` 之后，页面右侧还有一颗 70×70 的小节点
     * `[1104,2166][1174,2236]`（y 远高于底栏）。旧规则"在输入框右侧 + 取最靠右"
     * 会选中它（left=1104 > 真发送键的 1027）→ **8 次点击全打在它身上，消息一次都没发出去** ✗。
     *
     * 修复：发送键候选必须以 **`＋/更多` 图标所在的那一行**做行锚点（真机上
     * `＋` 与发送 `⬆` 并排在同一条底栏），位置明显偏上的节点被结构性排除。
     *
     * 同时这条 fixture 也钉住"输入栏 = 最靠下的 EditText"（页面里那颗更高的
     * 2016px 可编辑文本节点不能顶替输入栏）。
     */
    @Test
    fun tallPageNodeDoesNotStealTheSendTarget() {
        val tallText = node(
            className = "android.widget.EditText",
            left = 70, top = 588, right = 1202, bottom = 2604,
            label = "元宝生成的一大段回答（可编辑/可选中文本节点）",
        )
        val composer = node(
            className = "android.widget.EditText",
            left = 70, top = 2138, right = 880, bottom = 2645,
            label = "关于改善久坐后的疲劳",
        )
        val plus = node(
            className = "android.view.View",
            left = 908, top = 2498, right = 992, bottom = 2582,
            description = "更多",
        )
        /** 真机那颗"位置明显偏上"的 70×70 小节点，绝不能当发送键。 */
        val strayNode = node(
            className = "android.widget.ImageView",
            left = 1104, top = 2166, right = 1174, bottom = 2236,
        )
        /** 真正的 ⬆（与 `＋` 同一条底栏）。 */
        val realSend = node(
            className = "android.widget.Button",
            left = 1027, top = 2456, right = 1195, bottom = 2624,
        )
        val bar = ComposerGeometry.resolve(
            listOf(tallText, composer, plus, strayNode, realSend),
            textPresent = true,
        )
        requireNotNull(bar)
        assertEquals("必须选中真正靠下的输入栏 top", 2138, bar.editor.top)
        requireNotNull(bar.sendButton)
        assertEquals("发送键必须是底栏那颗 ⬆ 的左边界", 1027, bar.sendButton!!.left)
        assertEquals("发送键必须是底栏那颗 ⬆ 的右边界", 1195, bar.sendButton!!.right)
        assertFalse(
            "偏上的那颗小节点 (1139,2201) 绝不能通过安全校验",
            ComposerGeometry.isSafeActionPoint(bar, 1139, 2201),
        )
        assertTrue(
            "真正 ⬆ 的中心必须通过安全校验",
            ComposerGeometry.isSafeActionPoint(bar, bar.actionX, bar.actionY),
        )
    }

    /**
     * **AI写作页：真发送 ⬆ 与那片"空白热区"**（1.1.9，用户报的"AI写作定位不到输入框/发不出去"）。
     *
     * 数据全部取自真机：
     * - `evidence/write_filled.xml`（无障碍树原始 bounds）
     * - `evidence/w5_buttons.jpg`（截图，把三个候选框都画出来了）
     *
     * **截图是决定性的**：无障碍树在 `[1104,1361][1174,1431]` 报了一颗带文字 `全屏输入`
     * 的节点，但那个位置在截图上**是一片空白**；真正的深色 ⬆ 是 `[1027,1459][1195,1627]`。
     *
     * 区别在于**底部是否与输入框对齐**：真发送差 21px，`全屏输入` 差 217px，
     * `View[1055,1312][1223,1480]` 差 168px。
     *
     * 旧规则只要求"在输入框右侧 + 取最靠右"，`全屏输入` 的 left=1104 比真发送的 1027 更靠右
     * → 被选中 → 三次点击全打在那片空白上，输入框里文案**一字未少**（`evidence/trace_w5`）。
     */
    @Test
    fun writingPageNeverTreatsTheBlankFullscreenHotspotAsSend() {
        val nodes = listOf(
            node(
                "android.widget.EditText", 70, 1333, 1020, 1648,
                label = "请围绕总结今天完成的工作写简短通知",
            ),
            // 干扰项①：带文字 `全屏输入`，比真发送更靠右 —— 旧规则必然选中它。
            node(
                "android.view.View", 1104, 1361, 1174, 1431,
                label = "全屏输入", description = "全屏输入",
            ),
            // 干扰项②：偏上 168px。
            node("android.view.View", 1055, 1312, 1223, 1480),
            // 干扰项③：底部差 189px。
            node("android.widget.Button", 1076, 1333, 1202, 1459),
            // 真发送 ⬆（截图绿框）与同一颗按钮的外包围盒（截图蓝框，中心仍在圆按钮内）。
            node("android.widget.Button", 1027, 1459, 1195, 1627),
            node("android.widget.Button", 1055, 1501, 1223, 1669),
        )
        val bar = requireNotNull(ComposerGeometry.resolve(nodes, textPresent = true))
        val send = requireNotNull(bar.sendButton) { "写作页应当能解析出发送键" }
        assertTrue(
            "发送键底部必须与输入框底部对齐（真机差 21px），实际 bottom=${send.bottom}",
            kotlin.math.abs(send.bottom - 1648) <= 40,
        )
        assertFalse(
            "绝不能把 [1104,1361][1174,1431] 那片空白热区当成发送键（top=${send.top}）",
            send.top <= 1440,
        )
        assertTrue(
            "落点 (${bar.actionX},${bar.actionY}) 必须命中真发送 ⬆ 的矩形 [1027,1459][1195,1627]",
            bar.actionX in 1027..1195 && bar.actionY in 1459..1627,
        )
        assertTrue(
            "该落点必须通过安全校验",
            ComposerGeometry.isSafeActionPoint(bar, bar.actionX, bar.actionY),
        )
        // 反面：那片空白热区的中心绝不能是安全落点。
        assertFalse(
            "空白热区中心 (1139,1396) 不能当发送落点",
            ComposerGeometry.isSafeActionPoint(bar, 1139, 1396),
        )
    }

    private fun node(
        className: String,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        label: String = "",
        description: String = "",
    ) = ScreenScanner.DumpNode(
        packageName = "com.tencent.hunyuan.app.chat",
        text = label,
        description = description,
        resourceId = "",
        className = className,
        bounds = Bounds(left, top, right, bottom),
        clickable = true,
        scrollable = false,
    )
}
