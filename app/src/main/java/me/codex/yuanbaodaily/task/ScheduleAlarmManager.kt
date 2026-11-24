package me.codex.yuanbaodaily.task

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import me.codex.yuanbaodaily.hook.Log
import java.util.TimeZone

/** Keeps one daily wake-up registered outside the Yuanbao process. */
internal object ScheduleAlarmManager {
    const val ACTION_DAILY_ALARM = "me.codex.yuanbaodaily.action.DAILY_ALARM"

    /**
     * 周期性"体检闹钟"：只做一件事 —— 确认无障碍服务还绑着，掉了就自己重新绑上。
     *
     * 存在的理由见 `AccessibilityRepair` 的类注释：ColorOS 会杀掉模块进程且不重绑服务，
     * 而闹钟的 PendingIntent 是**系统级**的，进程被杀也不影响它按时把进程拉起来。
     */
    const val ACTION_HEALTH_ALARM = "me.codex.yuanbaodaily.action.HEALTH_ALARM"

    private const val CONFIG_PREFS = "yuanbao_daily_config"
    private const val RUN_AT_MINUTES = "run_at_minutes"
    private const val ENABLED = "enabled"
    private const val REQUEST_CODE = 2871
    private const val REQUEST_CODE_HEALTH = 2872

    /** 体检间隔。 */
    private const val HEALTH_INTERVAL_MS = 15 * 60 * 1000L

    /**
     * 首次体检的延迟。
     *
     * 别设太长：真机上 ColorOS 会在开机后**把本模块从无障碍列表里摘掉**
     * （`settings get secure enabled_accessibility_services` 里我们的条目消失），
     * 而那之后模块唯一的自救机会就是这个闹钟。90 秒足够让开机那阵子忙完。
     */
    private const val HEALTH_FIRST_DELAY_MS = 90 * 1000L

    fun reschedule(context: Context) {
        val appContext = context.applicationContext
        val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val pendingIntent = pendingIntent(appContext)
        val preferences = appContext.getSharedPreferences(CONFIG_PREFS, Context.MODE_PRIVATE)
        if (!preferences.getBoolean(ENABLED, true)) {
            alarmManager.cancel(pendingIntent)
            Log.d("daily alarm cancelled: module disabled")
            return
        }

        val runAtMinutes = preferences.getInt(RUN_AT_MINUTES, 180).coerceIn(0, 1_439)
        val triggerAt = NextRunCalculator.nextTriggerAt(
            nowMillis = System.currentTimeMillis(),
            runAtMinutes = runAtMinutes,
            timeZone = TimeZone.getDefault(),
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
        } else {
            @Suppress("DEPRECATION")
            alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
        }
        Log.d("daily alarm scheduled at=$triggerAt runAtMinutes=$runAtMinutes")

        scheduleHealth(appContext, HEALTH_FIRST_DELAY_MS)
    }

    /**
     * 注册/续订体检闹钟（幂等：同 requestCode 的 PendingIntent 会被覆盖）。
     *
     * 用 `setAndAllowWhileIdle` **自己链自己**，而不是 `setInexactRepeating`：
     * 后者虽然省电，但系统给的投放窗口很宽（实测 `windowLength` 达 45 分钟），
     * 而这条闹钟是"服务掉线后唯一的自救入口"，晚到 45 分钟意味着
     * 这段时间内**所有任务都会以'定位不到输入框'失败**。
     * `setAndAllowWhileIdle` 在 doze 里的下限是 9 分钟/次，15 分钟间隔安全。
     *
     * 每次触发后由 [DailyAlarmReceiver.handleHealthCheck] 再调一次本方法续订。
     */
    fun scheduleHealth(context: Context, delayMs: Long = HEALTH_INTERVAL_MS) {
        val appContext = context.applicationContext
        val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val pendingIntent = healthPendingIntent(appContext)
        val preferences = appContext.getSharedPreferences(CONFIG_PREFS, Context.MODE_PRIVATE)
        if (!preferences.getBoolean(ENABLED, true)) {
            alarmManager.cancel(pendingIntent)
            return
        }
        val triggerAt = System.currentTimeMillis() + delayMs
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAt,
                    pendingIntent,
                )
            } else {
                @Suppress("DEPRECATION")
                alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
            }
        }.onSuccess {
            Log.d("health alarm scheduled at=$triggerAt delayMs=$delayMs")
        }.onFailure { Log.e("health alarm schedule failed", it) }
    }

    fun cancel(context: Context) {
        val appContext = context.applicationContext
        val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        alarmManager.cancel(pendingIntent(appContext))
        alarmManager.cancel(healthPendingIntent(appContext))
        Log.d("daily alarm cancelled")
    }

    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, DailyAlarmReceiver::class.java).apply {
            action = ACTION_DAILY_ALARM
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        return PendingIntent.getBroadcast(context, REQUEST_CODE, intent, flags)
    }

    private fun healthPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, DailyAlarmReceiver::class.java).apply {
            action = ACTION_HEALTH_ALARM
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        return PendingIntent.getBroadcast(context, REQUEST_CODE_HEALTH, intent, flags)
    }
}
