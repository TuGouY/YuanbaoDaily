package me.codex.yuanbaodaily.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「能不能点发送」的结构判定回归测试。
 *
 * 节点数据全部来自**真机 dump 实测**（PLQ110 / 1272x2800 / 元宝 2.87.20，问元宝对话页）。
 *
 * 这里要钉死的是用户报的那个误触：
 *
 * > 找不到输入框；输入框还没文案时，模块误以为文案已经写进去了，
 * > 就去点"切换语音"；有文案时同一个位置才是"发送"。
 *
 * 真机实测，输入栏一行（**空文案**）是：
 *
 * ```text
 * [56,2435][210,2645]    View                    clickable=true   ← 左侧按钮容器
 * [98,2498][182,2582]    View      重描述=拍照
 * [210,2435][922,2645]   EditText  text=''                        ← 输入框（空）
 * [224,2502][714,2579]   TextView  text='发消息或按住说话...'      ← 空文案占位符
 * [950,2498][1034,2582]  ImageView 描述=切换语音                   ← 空文案签名 ★
 * [1048,2435][1216,2645] View      clickable=true                 ← 动作容器（语义随内容翻转）★
 * [1062,2435][1202,2645] Button
 * [1090,2498][1174,2582] View      描述=更多
 * ```
 *
 * 关键：`[1048,2435][1216,2645]` 这颗容器**没有 text/描述/resource-id**，
 * 空文案时它是"切到语音"，有文案时才变成"发送"。所以**必须**靠同帧的
 * 「有没有 `切换语音` / `按住说话` 占位」来判断，绝不能"先点一下看看"。
 */
class ComposerSendGuardTest {

    /** 实测：真正的输入框。 */
    private val editor = node(
        className = "android.widget.EditText",
        left = 210, top = 2435, right = 922, bottom = 2645,
        clickable = true,
    )

    /** 实测：空文案占位符（只有输入框为空时才会出现）。 */
    private val placeholder = node(
        className = "android.widget.TextView",
        left = 224, top = 2502, right = 714, bottom = 2579,
        label = "发消息或按住说话...",
    )

    /** 实测：空文案时右侧挂着"切换语音"图标。 */
    private val voiceIcon = node(
        className = "android.widget.ImageView",
        left = 950, top = 2498, right = 1034, bottom = 2582,
        description = "切换语音",
    )

    /** 实测：语义随内容翻转的动作容器（无 text/描述/id）。 */
    private val actionSlot = node(
        className = "android.view.View",
        left = 1048, top = 2435, right = 1216, bottom = 2645,
        clickable = true,
    )

    private val moreIcon = node(
        className = "android.view.View",
        left = 1090, top = 2498, right = 1174, bottom = 2582,
        description = "更多",
    )

    private val cameraIcon = node(
        className = "android.view.View",
        left = 98, top = 2498, right = 182, bottom = 2582,
        description = "拍照",
    )

    private val leftSlot = node(
        className = "android.view.View",
        left = 56, top = 2435, right = 210, bottom = 2645,
        clickable = true,
    )

    /** 真机空文案时的完整一行。 */
    private val emptyRow = listOf(leftSlot, cameraIcon, editor, placeholder, voiceIcon, actionSlot, moreIcon)

    /** 动作容器中心（真机上"最右那颗容器"的落点，就是误触发生的位置）。 */
    private val slotCenter = actionSlot.box.centerX to actionSlot.box.centerY

    // ------------------------------ 空输入框：必须拒点 ------------------------------

    @Test
    fun refusesWhenComposerIsEmpty() {
        val decision = ComposerSendGuard.decide(
            nodes = emptyRow,
            editor = editor.box,
            hostEditorText = null,
            geometryPoint = slotCenter,
        )
        assertEquals(
            "空输入栏那一刻，那颗容器是“切换语音”→ 必须拒点",
            ComposerSendGuard.Verdict.REFUSE,
            decision.verdict,
        )
        assertFalse(decision.allowed)
    }

    @Test
    fun refusesWhenComposerIsEmptyEvenIfHostClaimsText() {
        // 用户报的核心场景：宿主读到的是**别的**输入框（搜索框/残留编辑器）里有文字，
        // 于是"误以为文案已经写进去了"→ 放行 → 点到"切换语音" ✗。
        // 空签名是直接观察到的界面状态，必须压过间接读到的文字。
        val decision = ComposerSendGuard.decide(
            nodes = emptyRow,
            editor = editor.box,
            hostEditorText = true,
            geometryPoint = slotCenter,
        )
        assertEquals(
            "宿主说有文案，但界面仍是空签名 → 仍须拒点（回归防护）",
            ComposerSendGuard.Verdict.REFUSE,
            decision.verdict,
        )
    }

    @Test
    fun placeholderAloneIsEnoughToProveEmpty() {
        // 占位符"发消息或按住说话..."只有空输入框才出现 —— 单靠它就要判空。
        val rowWithoutVoiceIcon = listOf(editor, placeholder, actionSlot)
        assertTrue(
            "占位符出现即判空",
            ComposerSendGuard.showsEmptySignature(rowWithoutVoiceIcon, editor.box),
        )
        assertEquals(
            ComposerSendGuard.Verdict.REFUSE,
            ComposerSendGuard.decide(rowWithoutVoiceIcon, editor.box, true, slotCenter).verdict,
        )
    }

    @Test
    fun refusesWhenHostSaysEditorIsEmpty() {
        // 没有空签名（例如布局变了），但宿主明确读到输入框是空的 → 同样拒点。
        val row = listOf(editor, actionSlot)
        val decision = ComposerSendGuard.decide(row, editor.box, hostEditorText = false, geometryPoint = slotCenter)
        assertEquals(ComposerSendGuard.Verdict.REFUSE, decision.verdict)
        assertFalse(decision.allowed)
    }

    @Test
    fun refusesWhenNothingIsKnown() {
        // 既没有发送节点、又没有空签名、宿主也读不到 → 证据不足，拒点（宁可不发）。
        val row = listOf(editor, actionSlot)
        val decision = ComposerSendGuard.decide(row, editor.box, hostEditorText = null, geometryPoint = slotCenter)
        assertEquals(ComposerSendGuard.Verdict.REFUSE, decision.verdict)
        assertTrue(decision.reason.contains("hostText=null"))
    }

    @Test
    fun refusesWhenNoGeometryPointIsAvailable() {
        val row = listOf(editor)
        val decision = ComposerSendGuard.decide(row, editor.box, hostEditorText = true, geometryPoint = null)
        assertEquals(
            "宿主说有文案但没有可点落点 → 不能凭空点，拒点",
            ComposerSendGuard.Verdict.REFUSE,
            decision.verdict,
        )
    }

    // ------------------------------ 有文案：允许发送 ------------------------------

    @Test
    fun sendsByNodeWhenSendNodeIsPresent() {
        // 有文案时元宝会给动作键"发送"语义：这时点它**自己**的中心，最可靠。
        val sendNode = node(
            className = "android.view.View",
            left = 1048, top = 2435, right = 1216, bottom = 2645,
            description = "发送",
            clickable = true,
        )
        val row = listOf(editor, sendNode)
        val decision = ComposerSendGuard.decide(row, editor.box, hostEditorText = null, geometryPoint = slotCenter)
        assertEquals(ComposerSendGuard.Verdict.SEND_BY_NODE, decision.verdict)
        assertEquals(sendNode.box.centerX, decision.x)
        assertEquals(sendNode.box.centerY, decision.y)
        assertTrue(decision.allowed)
    }

    @Test
    fun sendsByGeometryWhenHostConfirmsTextAndNoEmptySignature() {
        // 有文案、但动作键没有语义（1.1.1 时期实测就是一颗无描述 Button）：
        // 只要宿主确认有文案、且没有空签名 → 放行，按真实几何点。
        val row = listOf(editor, actionSlot, moreIcon)
        val decision = ComposerSendGuard.decide(row, editor.box, hostEditorText = true, geometryPoint = slotCenter)
        assertEquals(ComposerSendGuard.Verdict.SEND_BY_GEOMETRY, decision.verdict)
        assertEquals(slotCenter.first, decision.x)
        assertEquals(slotCenter.second, decision.y)
    }

    // ------------------------------ 边界：不能误伤别处 ------------------------------

    @Test
    fun ignoresSendMarkerOutsideComposerRow() {
        // 页面别处（例如消息气泡/按钮）出现"发送"不能当成输入栏的发送键。
        val faraway = node(
            className = "android.widget.Button",
            left = 200, top = 600, right = 600, bottom = 700,
            label = "发送",
        )
        val row = listOf(editor, actionSlot, faraway)
        assertNull(
            "不同行的“发送”不算本输入栏的发送键",
            ComposerSendGuard.findSendNode(row, editor.box),
        )
        assertEquals(
            ComposerSendGuard.Verdict.REFUSE,
            ComposerSendGuard.decide(row, editor.box, null, slotCenter).verdict,
        )
    }

    @Test
    fun ignoresOversizedContainerWhenLookingForEmptySignature() {
        // 整屏容器里含"切换语音"字样也不该命中（边长超限被过滤），避免误判为空。
        val huge = node(
            className = "android.view.ViewGroup",
            left = 0, top = 0, right = 1272, bottom = 2800,
            label = "切换语音",
        )
        assertFalse(ComposerSendGuard.showsEmptySignature(listOf(huge), editor.box))
    }

    /**
     * **跨分辨率沙箱**：整屏容器必须按"屏幕宽度"被过滤掉，而不是按写死的 1200。
     *
     * 老写法 `MAX_TEXT_SIDE = 1200` 在 1272 宽机型上刚好能筛掉 `[0,0][1272,2800]`；
     * 但换到 **1080 宽**（小米 14 Pro，2400×1080）时，整屏容器宽度就是 1080 < 1200，
     * **筛不掉** —— 一颗带"切换语音"字样的整屏容器会被当成输入栏同行文字节点，
     * "空输入框签名"判定随之失真，进而把有文案的输入框判成空（或反过来）。
     *
     * 这条同时验证三件事：
     * 1. 1080 宽下整屏容器被正确过滤；
     * 2. 显式传 0（读不到屏宽）时仍退化成旧行为，不会把功能关死；
     * 3. 真实占位符节点在 1080 宽下仍然能被识别（不能为了过滤而把正常节点也筛掉）。
     */
    @Test
    fun filtersWholeScreenContainerAtAnyResolution() {
        val widths = listOf(1080, 1272, 1440)
        widths.forEach { width ->
            val wholeScreen = node(
                className = "android.view.ViewGroup",
                left = 0, top = 0, right = width, bottom = (width * 2.2f).toInt(),
                label = "切换语音",
            )
            assertFalse(
                "宽度=$width：整屏容器必须被过滤掉",
                ComposerSendGuard.showsEmptySignature(listOf(wholeScreen), editor.box, width),
            )
        }
    }

    /** 1080 宽下，真实占位符（`发消息或按住说话...`）仍然要能被识别为空输入框。 */
    @Test
    fun stillDetectsPlaceholderAt1080Width() {
        val placeholder = node(
            className = "android.widget.TextView",
            left = 190, top = 2124, right = 606, bottom = 2190,
            label = "发消息或按住说话...",
        )
        val scaledEditor = node(
            className = "android.widget.EditText",
            left = 178, top = 2067, right = 783, bottom = 2246,
        )
        assertTrue(
            "1080 宽下占位符仍应判为'空输入框签名'",
            ComposerSendGuard.showsEmptySignature(listOf(placeholder), scaledEditor.box, 1080),
        )
    }

    @Test
    fun detectsSendNodeInRealFilledRow() {
        // 有文案时的实机形态（占位符与"切换语音"都消失，动作键挂"发送"语义）。
        val sendNode = node(
            className = "android.view.View",
            left = 1048, top = 2435, right = 1216, bottom = 2645,
            description = "发送",
            clickable = true,
        )
        val filledRow = listOf(leftSlot, cameraIcon, editor, sendNode)
        val decision = ComposerSendGuard.decide(filledRow, editor.box, hostEditorText = null, geometryPoint = null)
        assertEquals(ComposerSendGuard.Verdict.SEND_BY_NODE, decision.verdict)
        assertNotNull(decision.reason)
    }

    private fun node(
        className: String,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        label: String = "",
        description: String = "",
        resourceId: String = "",
        clickable: Boolean = false,
    ) = ScreenScanner.DumpNode(
        packageName = "com.tencent.hunyuan.app.chat",
        text = label,
        description = description,
        resourceId = resourceId,
        className = className,
        bounds = Bounds(left, top, right, bottom),
        clickable = clickable,
        scrollable = false,
    )
}
