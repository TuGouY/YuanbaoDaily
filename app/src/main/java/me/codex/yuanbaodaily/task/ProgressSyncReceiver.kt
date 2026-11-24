package me.codex.yuanbaodaily.task

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import me.codex.yuanbaodaily.hook.Log

/**
 * 接收宿主（元宝进程）推过来的任务进度。
 *
 * 为什么不用 SharedPreferences 的 Provider 通道：实测模块自己的 shared_prefs
 * 从 2026-10-25 起一直是空的 —— 跨进程写入没有真正落到模块侧，
 * 界面于是永远读到 0/3。广播是系统级通道，只要 action 对、包名对，一定会到。
 */
class ProgressSyncReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_PROGRESS_SYNC) return
        val day = intent.getStringExtra(EXTRA_DAY).orEmpty()
        if (day.isBlank()) return
        val store = TaskStore(context)
        var updated = 0
        DailyTaskKind.entries.forEach { kind ->
            if (intent.hasExtra(kind.id)) {
                store.setConfirmed(day, kind, intent.getIntExtra(kind.id, 0))
                updated++
            }
        }
        Log.d("progress sync received day=$day items=$updated")
        // 界面若正在前台，让它立刻重画一遍（不在前台也无妨：下次 onResume 会读）。
        context.sendBroadcast(Intent(ACTION_PROGRESS_UPDATED).setPackage(context.packageName))
    }

    companion object {
        /** 宿主 → 模块：进度快照。 */
        const val ACTION_PROGRESS_SYNC = "me.codex.yuanbaodaily.action.PROGRESS_SYNC"
        /** 模块内部：通知界面刷新。 */
        const val ACTION_PROGRESS_UPDATED = "me.codex.yuanbaodaily.action.PROGRESS_UPDATED"
        const val EXTRA_DAY = "day"
    }
}
