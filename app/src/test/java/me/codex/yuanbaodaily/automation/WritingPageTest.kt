package me.codex.yuanbaodaily.automation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "AI写作"页识别。
 *
 * 用例文本**全部取自真机 dump**，不是编的：
 * - 写作页：`evidence/write_probe.xml`（键盘弹起）/ `evidence/write_nokb.xml`（键盘收起）
 * - 主页：`evidence/home_probe.xml`
 *
 * 这个判定是"写作页能不能进 Compose 写入通道"的入口条件，
 * 之前用 `isChatComposerPresent()`（它故意排除写作页）导致**AI写作定位不到输入框**，
 * 所以这里把两侧都锁住：写作页必须识别、**主页必须不识别**。
 */
class WritingPageTest {

    /** 真机 `evidence/write_probe.xml` 的可见文本（键盘弹起）。 */
    private val realWritingPageKeyboardUp = listOf(
        "选择写作类型", "通用", "作文", "作文素材", "营销文案", "公文",
        "发布平台", "写作风格", "字数", "AI写作",
        "请输入你要写的主题", "切换语音", "抽屉页入口", "元宝",
    ).joinToString("\n")

    /** 真机 `evidence/write_nokb.xml`（键盘收起）—— 输入框移到底部，文案不变。 */
    private val realWritingPageKeyboardDown = listOf(
        "选择写作类型", "通用", "作文", "AI写作",
        "请输入你要写的主题", "切换语音",
    ).joinToString("\n")

    /**
     * 真机 `evidence/home_probe.xml` 的可见文本。
     *
     * **关键**：主页那一行快捷入口里**也有 `AI写作`**，所以绝不能用它当判据。
     */
    private val realHomePage = listOf(
        "Hi，晚安", "城市里哪里能找到安静的角落？", "同一处风景在四季里差别有多大？",
        "快速", "AI写作", "作业批改", "AI创作", "拍照",
        "发消息或按住说话...", "切换语音", "更多",
        "内容由AI生成，注意核实", "抽屉页入口", "元宝",
    ).joinToString("\n")

    /** 对话页（`evidence/trace_e5_ask` 场景）：有输入栏但没有写作页独有文案。 */
    private val realChatPage = listOf(
        "元宝", "关于做好当天的简短复盘,请给我三个可以马上执行的建议，按重要程度排序。",
        "发消息或按住说话...", "切换语音", "更多",
    ).joinToString("\n")

    /** P图 / 能力页：`智能P图`/`添加创作素材` 是它的特征，绝不能当写作页。 */
    private val realPicturePage = listOf(
        "智能P图", "点击添加创作素材", "图片1", "选择图片", "元宝",
    ).joinToString("\n")

    @Test
    fun recognisesRealWritingPageWithKeyboardUp() {
        assertTrue(WritingPage.looks(realWritingPageKeyboardUp))
    }

    @Test
    fun recognisesRealWritingPageWithKeyboardDown() {
        assertTrue(WritingPage.looks(realWritingPageKeyboardDown))
    }

    @Test
    fun placeholderAloneIsEnough() {
        // 页面加载中途只出现占位符（类型列表还没渲染完）也要认得出来。
        assertTrue(WritingPage.looks("请输入你要写的主题"))
    }

    /**
     * **最重要的一条**：主页上有 `AI写作` 快捷入口，但它不是写作页。
     *
     * 旧判据里有一条 `text.contains("AI写作") && text.contains("写作风格")`，
     * 虽然主页不含 `写作风格` 侥幸没触发，但把 `AI写作` 写进判据本身就是隐患 ——
     * 一旦主页新增 `写作风格` 之类的推荐词，就会把主页误判成写作页。
     */
    @Test
    fun homePageWithAiWritingShortcutIsNotAWritingPage() {
        assertFalse(WritingPage.looks(realHomePage))
        // 单独一个 `AI写作`（主页那颗入口的文字节点）也绝不能算。
        assertFalse(WritingPage.looks("AI写作"))
        assertFalse(WritingPage.looks("AI写作 快速 作业批改 AI创作"))
    }

    @Test
    fun chatPageIsNotAWritingPage() {
        assertFalse(WritingPage.looks(realChatPage))
    }

    @Test
    fun picturePageIsNotAWritingPage() {
        assertFalse(WritingPage.looks(realPicturePage))
    }

    @Test
    fun emptyTextIsNotAWritingPage() {
        assertFalse(WritingPage.looks(""))
    }

    @Test
    fun markersAreTheOnlySourceOfTruth() {
        // 判据必须是写作页独有文案；`写作`/`AI写作` 这种到处都有的词不许进列表。
        assertTrue(WritingPage.MARKERS.contains("选择写作类型"))
        assertTrue(WritingPage.MARKERS.contains("请输入你要写的主题"))
        assertFalse(WritingPage.MARKERS.any { it == "写作" })
        assertFalse(WritingPage.MARKERS.any { it == "AI写作" })
    }
}
