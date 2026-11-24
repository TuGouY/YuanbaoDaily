package me.codex.yuanbaodaily.automation

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import me.codex.yuanbaodaily.hook.Log
import me.codex.yuanbaodaily.task.AlarmBootstrap

/**
 * Hands the module's command Binder to the injected host process.
 *
 * Package visibility only restricts host → module lookups: the host has no
 * `<queries>` entry for the module, so its `ContentResolver.call` answers
 * "Unknown authority" and its `bindService` returns false. The module, however,
 * can see the host (its manifest declares `com.tencent.hunyuan.app.chat`), and a
 * Binder reference carried in an Intent needs no package lookup at all. So this
 * direction works even though every host-initiated transport fails.
 */
internal object AutomationHostLink {
    const val ACTION_MODULE_ONLINE = "me.codex.yuanbaodaily.action.MODULE_ONLINE"
    const val EXTRA_PAYLOAD = "me.codex.yuanbaodaily.extra.MODULE_LINK"
    const val KEY_BINDER = "binder"

    private const val TARGET_PACKAGE = "com.tencent.hunyuan.app.chat"
    private const val REPEAT_INTERVAL_MS = 4_000L
    private const val REPEAT_WINDOW_MS = 240_000L

    @Volatile
    private var publishing = false

    fun publishOnce(context: Context, reason: String) {
        val sent = runCatching {
            val appContext = context.applicationContext
            AutomationCommandBinder.instance.attach(appContext)
            // 模块进程活着 → 顺手把闹钟武装上。见 AlarmBootstrap：真机上曾经
            // 因为只靠 MainActivity/BOOT_COMPLETED 注册，导致 `dumpsys alarm`
            // 里一条模块闹钟都没有。
            AlarmBootstrap.arm(appContext, "hostLink:$reason")
            // The binder travels inside a Bundle (`putBinder`); a Binder is not
            // Parcelable on its own, so it cannot be a plain Intent extra.
            val payload = Bundle().apply {
                putBinder(KEY_BINDER, AutomationCommandBinder.instance)
            }
            appContext.sendBroadcast(
                Intent(ACTION_MODULE_ONLINE)
                    .setPackage(TARGET_PACKAGE)
                    .putExtra(EXTRA_PAYLOAD, payload),
            )
            true
        }.getOrDefault(false)
        Log.d("module link reason=$reason sent=$sent")
    }

    /**
     * Repeats the handshake for a few minutes.
     *
     * The host process is normally started by the same user action that asks for
     * a run, so the first broadcast usually arrives before anything is listening.
     */
    fun publishBurst(context: Context, reason: String) {
        publishOnce(context, reason)
        if (publishing) return
        publishing = true
        val appContext = context.applicationContext
        Thread({
            val deadline = SystemClock.uptimeMillis() + REPEAT_WINDOW_MS
            while (SystemClock.uptimeMillis() < deadline) {
                SystemClock.sleep(REPEAT_INTERVAL_MS)
                publishOnce(appContext, "repeat")
            }
            publishing = false
        }, "YuanbaoDailyHostLink").apply { isDaemon = true }.start()
    }
}
