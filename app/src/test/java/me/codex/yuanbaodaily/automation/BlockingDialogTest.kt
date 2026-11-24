package me.codex.yuanbaodaily.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "有没有弹窗"判定的回归测试。
 *
 * 全部用例都来自真机实证（`evidence/<mode>/taps.log` 的误报行 + `evidence/write_filled.xml`
 * 的实际节点），因为这条判据错一次的代价是**点错屏幕上的东西**。
 */
class BlockingDialogTest {

    private fun node(className: String, text: String, clickable: Boolean = false) =
        BlockingDialog.Node(className, text, clickable)

    // ---------- 真机上误报过的两种污染源 ----------

    /**
     * 污染源 1：模块自己写进输入框的写作提示词。
     *
     * `TaskPromptFactory` 每条写作提示词都以「不要使用夸张口号。」结尾，
     * 所以旧实现**每次写作任务**都会误判成"检测到弹窗，点'使用'"（真机 3 次）。
     * 提示词住在 EditText 里，而 EditText 永远不可能是弹窗确认键。
     */
    @Test
    fun ownWritingPromptInComposerIsNotADialog() {
        val prompt = "请围绕“总结今天完成的工作”写简短通知，语气自然温暖，约80字，不要使用夸张口号。"
        val nodes = listOf(
            node("android.widget.EditText", prompt, clickable = true),
            node("android.widget.TextView", "请输入你要写的主题"),
            node("android.widget.ImageView", "切换语音"),
        )
        assertNull(BlockingDialog.pickKey(nodes))
    }

    /**
     * 污染源 2：AI 的回复正文。真机日志：
     * `检测到弹窗，尝试精确点“好的”，当前文字=好的，下面是当天简短复盘的三个马上可执行建议…`
     */
    @Test
    fun assistantReplyStartingWithHaoDeIsNotADialog() {
        val reply = "好的，下面是当天简短复盘的三个马上可执行建议，按重要程度排序："
        val nodes = listOf(
            node("android.view.View", reply),
            node("android.widget.TextView", reply),
        )
        assertNull(BlockingDialog.pickKey(nodes))
    }

    /** 真机日志：`检测到弹窗，尝试精确点“好的”，当前文字=新建对话` */
    @Test
    fun newConversationScreenIsNotADialog() {
        val nodes = listOf(
            node("android.widget.TextView", "新建对话"),
            node("android.widget.EditText", "好的，我帮你整理一下今天的待办", clickable = true),
        )
        assertNull(BlockingDialog.pickKey(nodes))
    }

    // ---------- 真正的弹窗必须仍然能识别 ----------

    /** P图 页的 AlertDialog 确认键 —— 这个函数存在的**唯一理由**，绝不能被改坏。 */
    @Test
    fun realAlertDialogButtonIsStillDetected() {
        val nodes = listOf(
            node("androidx.appcompat.app.AlertDialog", "确认使用这张图片？"),
            node("android.widget.Button", "使用", clickable = true),
        )
        assertEquals("使用", BlockingDialog.pickKey(nodes))
    }

    @Test
    fun clickableComposeConfirmNodeIsDetected() {
        val nodes = listOf(
            node("android.view.View", "确定", clickable = true),
        )
        assertEquals("确定", BlockingDialog.pickKey(nodes))
    }

    @Test
    fun buttonClassCountsEvenWhenClickableFlagIsFalse() {
        // Compose/自定义控件有时把 clickable 挂到祖先上，自身 class 仍是 Button。
        val nodes = listOf(node("android.widget.Button", "同意", clickable = false))
        assertEquals("同意", BlockingDialog.pickKey(nodes))
    }

    @Test
    fun everyPositiveKeyIsStillReachable() {
        BlockingDialog.POSITIVE_KEYS.forEach { key ->
            val nodes = listOf(node("android.widget.Button", key, clickable = true))
            assertEquals(key, BlockingDialog.pickKey(nodes))
        }
    }

    // ---------- 逐条排除规则 ----------

    @Test
    fun exactMatchRejectsTextThatMerelyContainsTheWord() {
        val nodes = listOf(
            node("android.widget.Button", "取消使用", clickable = true),
            node("android.widget.Button", "使用模板", clickable = true),
            node("android.widget.Button", "请好的", clickable = true),
        )
        assertNull(BlockingDialog.pickKey(nodes))
    }

    @Test
    fun whitespaceAroundTheKeyIsTolerated() {
        val nodes = listOf(node("android.widget.Button", "  确定  ", clickable = true))
        assertEquals("确定", BlockingDialog.pickKey(nodes))
    }

    @Test
    fun nonClickableViewWithKeyTextIsNotADialog() {
        val nodes = listOf(node("android.widget.TextView", "保存", clickable = false))
        assertNull(BlockingDialog.pickKey(nodes))
    }

    @Test
    fun editorClassIsNeverAConfirmKey() {
        assertTrue(BlockingDialog.isEditor("android.widget.EditText"))
        assertTrue(BlockingDialog.isEditor("androidx.appcompat.widget.AppCompatEditText"))
        assertTrue(BlockingDialog.isEditor("android.widget.AutoCompleteTextView"))
        assertFalse(BlockingDialog.isEditor("android.widget.Button"))
        // 即便输入框可点且文字恰好是确认词，也不能当弹窗按钮。
        val nodes = listOf(node("android.widget.EditText", "确定", clickable = true))
        assertNull(BlockingDialog.pickKey(nodes))
    }

    @Test
    fun emptyNodeListYieldsNothing() {
        assertNull(BlockingDialog.pickKey(emptyList()))
    }

    /** 第一个命中的即可返回，顺序稳定。 */
    @Test
    fun firstMatchWins() {
        val nodes = listOf(
            node("android.widget.TextView", "标题"),
            node("android.widget.Button", "继续", clickable = true),
            node("android.widget.Button", "好的", clickable = true),
        )
        assertEquals("继续", BlockingDialog.pickKey(nodes))
    }
}
