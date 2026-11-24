package me.codex.yuanbaodaily.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ComposerState] 的回归测试。
 *
 * 夹具全部来自**元宝 2.87.20 / 1272×2800 真机 dump**，不是编出来的坐标：
 *
 * ```text
 * 空框（键盘收起）：
 *   EditText  text=""  id=""      [210,2435][922,2645]  focused=true
 *   TextView  '发消息或按住说话...' [224,2502][714,2579]
 *   ImageView desc='切换语音'      [950,2498][1034,2582]
 *   View      desc='更多'          [1090,2498][1174,2582]
 *
 * 空框（键盘弹起，输入框上移 997px）：
 *   EditText  text=""  id=""      [210,1438][922,1648]
 * ```
 */
class ComposerStateTest {

    private fun node(
        className: String,
        text: String = "",
        description: String = "",
        left: Int = 0,
        top: Int = 0,
        right: Int = 10,
        bottom: Int = 10,
    ) = ScreenScanner.DumpNode(
        packageName = "com.tencent.hunyuan.app.chat",
        text = text,
        description = description,
        resourceId = "",
        className = className,
        bounds = Bounds(left, top, right, bottom),
        clickable = false,
        scrollable = false,
    )

    /** 真机空框那一帧的节点集合。 */
    private fun emptyComposerNodes() = listOf(
        node("android.view.View", description = "拍照", left = 98, top = 2498, right = 182, bottom = 2582),
        node("android.widget.EditText", left = 210, top = 2435, right = 922, bottom = 2645),
        node("android.widget.TextView", text = "发消息或按住说话...", left = 224, top = 2502, right = 714, bottom = 2579),
        node("android.widget.ImageView", description = "切换语音", left = 950, top = 2498, right = 1034, bottom = 2582),
        node("android.view.View", description = "更多", left = 1090, top = 2498, right = 1174, bottom = 2582),
    )

    @Test
    fun emptyComposerWithPlaceholderAndVoiceIconIsEmpty() {
        assertEquals(ComposerState.Text.EMPTY, ComposerState.resolveFromNodes(emptyComposerNodes()))
    }

    @Test
    fun voiceModeIsDetectedBeforeAnythingElse() {
        val nodes = listOf(
            node("android.widget.TextView", text = "按住 说话", left = 512, top = 2498, right = 761, bottom = 2582),
            node("android.widget.ImageView", description = "切换键盘", left = 950, top = 2498, right = 1034, bottom = 2582),
        )
        assertEquals(ComposerState.Text.VOICE_MODE, ComposerState.resolveFromNodes(nodes))
    }

    @Test
    fun a11yNodeTextProvesHasText() {
        // Compose 会把 EditableText 映射到无障碍节点的 text —— 这是宿主唯一读得到的地方。
        val nodes = listOf(
            node("android.widget.EditText", text = "自检：你好 123", left = 210, top = 2435, right = 922, bottom = 2645),
            // 有文案时那颗麦克风图标**仍然在**（真机实测），所以不能靠它判空。
            node("android.widget.ImageView", description = "切换语音", left = 950, top = 2498, right = 1034, bottom = 2582),
        )
        assertEquals(ComposerState.Text.HAS_TEXT, ComposerState.resolveFromNodes(nodes))
    }

    @Test
    fun inputConnectionReadBackWinsOverEmptySignature() {
        // 直接读到的文字是最强证据：有文案时"切换语音"图标仍会留在原位。
        val state = ComposerState.resolveFromNodes(
            nodes = emptyComposerNodes(),
            inputConnectionText = "写一段关于秋天的文字",
        )
        assertEquals(ComposerState.Text.HAS_TEXT, state)
    }

    @Test
    fun hostNativeEditorTextProvesHasText() {
        val state = ComposerState.resolveFromNodes(
            nodes = emptyComposerNodes(),
            hostEditorText = "hello world",
        )
        assertEquals(ComposerState.Text.HAS_TEXT, state)
    }

    @Test
    fun placeholderIsNeverTreatedAsRealText() {
        // Compose 有时会把 hint 当成节点文字暴露出来 —— 采信它就会把空框当"有文案"。
        val nodes = listOf(
            node("android.widget.EditText", text = "发消息或按住说话...", left = 210, top = 2435, right = 922, bottom = 2645),
            node("android.widget.ImageView", description = "切换语音", left = 950, top = 2498, right = 1034, bottom = 2582),
        )
        assertEquals(ComposerState.Text.EMPTY, ComposerState.resolveFromNodes(nodes))
        assertFalse("发消息或按住说话...".isMeaningfulText())
    }

    @Test
    fun blankInputConnectionReadMeansEmpty() {
        // 读到**空串**（而不是 null）是"确认空"，不是"不知道"。
        val state = ComposerState.resolveFromNodes(
            nodes = listOf(
                node("android.widget.EditText", left = 210, top = 2435, right = 922, bottom = 2645),
            ),
            inputConnectionText = "",
        )
        assertEquals(ComposerState.Text.EMPTY, state)
    }

    @Test
    fun noEvidenceAtAllIsUnknown() {
        // 一个节点都没有（树读失败）→ 不能猜，必须 UNKNOWN（调用方按"宁可不发"处理）。
        assertEquals(ComposerState.Text.UNKNOWN, ComposerState.resolveFromNodes(emptyList()))
    }

    @Test
    fun editorNodeWithNothingElseIsUnknown() {
        // 只有一颗空 EditText、既无占位符也无切换语音图标 → 证据不足。
        val state = ComposerState.resolveFromNodes(
            nodes = listOf(node("android.widget.EditText", left = 210, top = 2435, right = 922, bottom = 2645)),
        )
        assertEquals(ComposerState.Text.UNKNOWN, state)
    }

    @Test
    fun whitespaceOnlyTextIsNotRealText() {
        assertFalse("   \n  ".isMeaningfulText())
        assertTrue("你好".isMeaningfulText())
    }

    @Test
    fun keyboardUpLayoutIsStillRecognised() {
        // 键盘弹起后输入框上移到 y≈1438，占位符/图标也跟着上移 —— 判据不能依赖绝对坐标。
        val nodes = listOf(
            node("android.widget.EditText", text = "写一首诗", left = 210, top = 1438, right = 922, bottom = 1648),
            node("android.widget.ImageView", description = "切换语音", left = 950, top = 1501, right = 1034, bottom = 1585),
        )
        assertEquals(ComposerState.Text.HAS_TEXT, ComposerState.resolveFromNodes(nodes))
    }

    @Test
    fun attachmentPlaceholderAloneIsNotRealText() {
        // **本命用例**（真机 `evidence/trace_reg_pic` 实证）：
        // P图页附着照片后，输入框节点文字只有一个对象替换符 `\uFFFC`。
        // 它一旦被当成"上一轮残留的提示词"，清残留逻辑就会执行 ACTION_SET_TEXT("")
        // 把**刚附加的照片抹掉** → P图永远提交失败（picture: selected=true submitted=false）。
        assertFalse("\uFFFC".isMeaningfulText())
        assertFalse("\uFFFC\uFFFC".isMeaningfulText())
        assertFalse("  \uFFFC \n".isMeaningfulText())
        // 零宽字符 / 替换字符同理
        assertFalse("\u200B\u200C\u200D\uFEFF".isMeaningfulText())
        assertFalse("\uFFFD".isMeaningfulText())
    }

    @Test
    fun attachmentPlaceholderPlusRealTextIsStillRealText() {
        // 素材 + 真文案同时存在时，那就是**真的有内容要发**，不能判成空。
        assertTrue("\uFFFC请优化这张图的色彩".isMeaningfulText())
    }
}
