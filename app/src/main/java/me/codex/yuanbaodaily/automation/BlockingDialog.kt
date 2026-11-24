package me.codex.yuanbaodaily.automation

/**
 * 判断"屏幕上到底有没有一个挡路的对话框"，以及该按哪个键。
 *
 * ## 为什么必须收紧
 *
 * 旧实现是**扫全屏文案**找关键词：
 * ```kotlin
 *     val text = readScreenText()
 *     val hit = listOf("确定", "知道了", "允许", "同意", "继续", "好的", "使用", "保存")
 *         .firstOrNull { text.contains(it) } ?: return false
 * ```
 * 这在真机上是**系统性误报**，`evidence/<mode>/taps.log` 里的实测命中：
 * ```
 *     3× 检测到弹窗，尝试精确点"使用"，当前文字=临时对话模式开关-关
 *     2× 检测到弹窗，尝试精确点"好的"，当前文字=好的，下面是当天简短复盘的三个马上可执行建议…
 *     1× 检测到弹窗，尝试精确点"好的"，当前文字=新建对话
 * ```
 * 两条污染源都不是弹窗：
 *
 * 1. **模块自己刚写进输入框的提示词** —— `TaskPromptFactory` 的写作提示词
 *    每条都以「不要使用夸张口号。」结尾，于是**每一次写作任务**都会命中"使用"。
 * 2. **AI 的回复正文** —— "好的，下面是…"是中文回答里最常见的开头之一。
 *
 * ## 收紧后的判据
 *
 * 弹窗确认键必须是一个**独立的、可点的、文字恰好等于确认词**的节点：
 * - 文字必须**精确相等**（不是包含）—— "好的，下面是…" 这类整段文案直接被排除；
 * - 节点必须**可点**（或类名像 Button）—— 正文文字不可点，直接被排除；
 * - 节点**不能是输入框** —— 我们自己的提示词就住在 EditText 里。
 *
 * 改成结构化判据后，"屏幕文案里出现过某个词"不再等于"有弹窗"。
 */
internal object BlockingDialog {
    /** 确认键候选。宁可少，也不能把正文里的词当按钮。 */
    val POSITIVE_KEYS = listOf("确定", "知道了", "允许", "同意", "继续", "好的", "使用", "保存")

    /** 只保留判定真正需要的三件事：类名、文字、可不可点。 */
    data class Node(
        val className: String,
        val text: String,
        val clickable: Boolean,
    )

    fun isEditor(className: String): Boolean {
        val lower = className.lowercase()
        return lower.contains("edittext") || lower.contains("autocompletetextview")
    }

    fun looksLikeButton(className: String): Boolean = className.contains("button", ignoreCase = true)

    /** 挑出确认键文字；没有真实对话框证据时返回 null。 */
    fun pickKey(nodes: List<Node>): String? = nodes.firstOrNull(::isConfirmKey)?.text?.trim()

    private fun isConfirmKey(node: Node): Boolean {
        if (isEditor(node.className)) return false
        if (!node.clickable && !looksLikeButton(node.className)) return false
        return node.text.trim() in POSITIVE_KEYS
    }
}
