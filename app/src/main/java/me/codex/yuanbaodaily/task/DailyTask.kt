package me.codex.yuanbaodaily.task

enum class DailyTaskKind(val id: String, val title: String, val aliases: List<String>) {
    ASK("ask", "问元宝问题", listOf("问元宝", "提问", "问答", "聊天")),
    WRITING("writing", "使用写作能力", listOf("写作", "写作能力", "文案", "文章")),
    PIC("pic", "使用P图能力", listOf("P图", "修图", "图片", "生图", "图片编辑")),
    PROBLEM("problem", "使用拍题能力", listOf("拍题", "题目", "搜题", "解题")),
    TEMPLATE("template", "使用推荐模板做同款", listOf("推荐模板", "做同款", "同款", "模板"));

    fun matches(raw: String): Boolean {
        val normalized = raw.lowercase()
        return aliases.any { normalized.contains(it.lowercase()) } ||
            normalized.contains(title.lowercase())
    }
}

data class DailyTask(
    val kind: DailyTaskKind,
    val required: Int = 2,
    val retryCount: Int = 2,
    val delayMs: Long = 2_500L,
    /** Number of capability attempts in one pass; ASK gets one warm-up round. */
    val executionRounds: Int = if (kind == DailyTaskKind.ASK) 4 else required,
)

val DEFAULT_TASKS = DailyTaskKind.entries.map { DailyTask(it) }

data class TaskProgress(
    val completed: Int,
    val required: Int,
) {
    val done: Boolean get() = completed >= required
    val remaining: Int get() = (required - completed).coerceAtLeast(0)
}
