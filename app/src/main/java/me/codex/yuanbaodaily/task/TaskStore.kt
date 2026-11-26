package me.codex.yuanbaodaily.task

import android.content.Context
import me.codex.yuanbaodaily.config.YuanbaoSharedStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal class TaskStore(context: Context) {
    private val appContext = context
    private val statePrefs = "yuanbao_daily_state"
    private val day: String
        get() = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date())

    fun progress(task: DailyTask): TaskProgress = TaskProgress(
        completed = YuanbaoSharedStore.getInt(appContext, statePrefs, key(task), 0),
        required = task.required,
    )

    fun setConfirmed(task: DailyTask, completed: Int) {
        YuanbaoSharedStore.putInt(appContext, statePrefs, key(task), completed.coerceIn(0, task.required))
    }

    /**
     * 按**指定日期**写入某一项进度。
     *
     * 给广播接收器用：宿主算完进度后把值推过来，接收器按宿主给的 day 落盘，
     * 避免两边跨零点时算到不同日期。
     */
    fun setConfirmed(day: String, kind: DailyTaskKind, completed: Int) {
        // 五项任务的 required 都是 2（2026-11 元宝改版后），这里按 2 截断。
        YuanbaoSharedStore.putInt(
            appContext,
            statePrefs,
            "$day.${kind.id}",
            completed.coerceIn(0, 2),
        )
    }

    fun resetToday() {
        DailyTaskKind.entries.forEach { kind ->
            YuanbaoSharedStore.remove(appContext, statePrefs, "$day.${kind.id}")
        }
    }

    private fun key(task: DailyTask) = "$day.${task.kind.id}"
}
