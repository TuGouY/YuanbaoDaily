package me.codex.yuanbaodaily.automation

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log as AndroidLog
import me.codex.yuanbaodaily.task.AlarmBootstrap

/**
 * Bound-service form of the module's command endpoint.
 *
 * Kept as a fallback transport: the ContentProvider and this service both need
 * the host to be able to see the module package, which is exactly what Android
 * 11+ package visibility denies when the host has no `<queries>` entry. The
 * primary transport is the Binder handed over by [AutomationHostLink].
 */
class AutomationCommandService : Service() {
    override fun onCreate() {
        super.onCreate()
        AutomationCommandBinder.instance.attach(this)
        // 模块进程起来了就顺手把闹钟武装上（只在本包进程内生效，见 AlarmBootstrap）。
        AlarmBootstrap.arm(this, "commandService.onCreate")
    }

    override fun onBind(intent: Intent?): IBinder {
        AutomationCommandBinder.instance.attach(this)
        AlarmBootstrap.arm(this, "commandService.onBind")
        AndroidLog.d(AutomationCommandBinder.TAG, "command service bound by ${intent?.action ?: "unknown"}")
        return AutomationCommandBinder.instance
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    companion object {
        fun attachContext(context: Context) = AutomationCommandBinder.instance.attach(context)
    }
}
