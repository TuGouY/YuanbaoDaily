package me.codex.yuanbaodaily.hook

import me.codex.yuanbaodaily.task.DailyTaskKind
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Runtime-only evidence collected from 元宝's generated task models.
 *
 * The generated DTMP models do not expose stable field names. Their toString()
 * output is the stable boundary we can observe across an APK update, so the
 * runner uses it as a confirmation signal instead of treating a UI click as a
 * completed server-side task.
 */
internal object YuanbaoTaskObserver {
    data class Evidence(
        val sequence: Long,
        val snapshots: Map<String, String>,
    )

    private val sequence = AtomicLong(0L)
    private val snapshots = ConcurrentHashMap<String, String>()

    fun record(value: Any?, source: String) {
        val objectValue = value ?: return
        val className = objectValue.javaClass.name
        val isTaskInfo = className.endsWith("AITaskInfo") || className.endsWith("BizAITaskInfo")
        val isButton = className.endsWith("TaskButtonView")
        val isCampaign = className.contains("Campaign") || className.contains("Welfare") || className.endsWith("OpenWebPage")
        if (!isTaskInfo && !isButton && !isCampaign) return
        val raw = runCatching { objectValue.toString() }.getOrElse { "<toString failed: ${it.javaClass.simpleName}>" }
        val seq = sequence.incrementAndGet()
        Log.d("$source ${objectValue.javaClass.simpleName}#$seq $raw")
        if (isTaskInfo) {
            val id = firstValue(raw, "taskId", "itemId", "projectId")
                ?.takeIf { it.isNotBlank() }
                ?: "${objectValue.javaClass.name}:${raw.hashCode()}"
            snapshots[id] = raw
        }
    }

    fun capture(kind: DailyTaskKind): Evidence = Evidence(
        sequence = sequence.get(),
        snapshots = snapshots.filterValues { kind.matches(it) },
    )

    /**
     * A confirmation requires a changed task model and an explicit completed
     * state/progress marker. This prevents a successful local click from being
     * incorrectly shown as 1/3 when the host rejected the action.
     */
    fun confirmed(kind: DailyTaskKind, before: Evidence): Boolean {
        if (sequence.get() <= before.sequence) return false
        val after = snapshots.filterValues { kind.matches(it) }
        if (after.isEmpty()) return false
        val changed = after.any { (id, raw) -> before.snapshots[id] != raw }
        if (!changed) return false
        return after.values.any(::looksCompleted)
    }

    fun diagnosticSnapshot(): String = snapshots.entries.joinToString(" | ") { "${it.key}=${it.value}" }

    private fun looksCompleted(value: String): Boolean {
        val text = value.lowercase(Locale.ROOT)
        return listOf(
            "completed", "complete", "success", "finished", "done", "已完成", "完成", "成功", "已达成",
        ).any(text::contains) || Regex("(?:progress|count|times|次数|进度)\\s*[=:]\\s*[1-9]").containsMatchIn(text)
    }

    private fun firstValue(raw: String, vararg names: String): String? {
        for (name in names) {
            val match = Regex("(?:^|[,\\s])$name=([^,)]*)").find(raw) ?: continue
            return match.groupValues[1].trim()
        }
        return null
    }
}
