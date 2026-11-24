package me.codex.yuanbaodaily.task

import android.content.Context
import me.codex.yuanbaodaily.BuildConfig
import me.codex.yuanbaodaily.hook.Log

/**
 * **每次模块进程起来就把闹钟重新武装一次**。
 *
 * ## 为什么必须有这个
 *
 * 原来只有一个注册点：`MainActivity.onCreate` 和 `BOOT_COMPLETED`。
 * 真机实测（`dumpsys alarm | grep yuanbaodaily`）结果是**一条都没有** ——
 * ColorOS 把本应用的自启动拦了（BOOT_COMPLETED 收不到），用户也很少去点模块图标，
 * 于是"周期体检闹钟"和"每日任务闹钟"**从来没有被注册过**。
 *
 * 这是在排查"AI写作定位不到输入框"时顺带查出来的：当时模块进程已经死了、
 * 无障碍服务处于 `Crashed`，而本来应该负责把它救回来的体检闹钟压根不存在。
 *
 * 修法很直接：这个进程只要活过一次（被宿主 bind、被无障碍系统 bind、
 * provider 被创建、或者推过一次宿主链接），就顺手 `reschedule()`。
 * 闹钟的 PendingIntent 是**系统级**的，注册一次之后即使进程再死，
 * 到点系统也会把它拉起来。
 *
 * ## 安全约束
 *
 * 只在**模块自己的进程**里动手。注入到宿主的模块代码也会走到一些公共方法，
 * 而用宿主的 Context 去注册 `DailyAlarmReceiver` 会指向宿主包里并不存在的组件，
 * 闹钟永远不会响。所以这里用包名把宿主挡掉。
 */
internal object AlarmBootstrap {
    /**
     * 模块自己的包名。
     *
     * 必须用 `BuildConfig.APPLICATION_ID` 而不是字面量：免 root 版的包名是
     * `me.codex.yuanbaodaily.lite`，写死字面量会让它在守卫这里被判成"宿主进程"
     * 而**永远不注册闹钟** —— 那种 bug 在真机上表现为"开机后什么都不发生"，
     * 极难和"系统拦了自启动"区分开。
     */
    private val MODULE_PACKAGE = BuildConfig.APPLICATION_ID

    private const val CONFIG_PREFS = "yuanbao_daily_config"
    private const val ENABLED = "enabled"

    @Volatile
    private var armed = false

    /** 幂等：同一进程内只真正跑一次。[source] 只用于日志。 */
    fun arm(context: Context, source: String) {
        val appContext = context.applicationContext
        if (appContext.packageName != MODULE_PACKAGE) {
            Log.d("alarm bootstrap skipped in host process source=$source pkg=${appContext.packageName}")
            return
        }
        if (armed) return
        armed = true
        if (!appContext.getSharedPreferences(CONFIG_PREFS, Context.MODE_PRIVATE)
                .getBoolean(ENABLED, true)
        ) {
            Log.d("alarm bootstrap skipped: module disabled source=$source")
            return
        }
        runCatching { ScheduleAlarmManager.reschedule(appContext) }
            .onSuccess { Log.d("alarm bootstrap armed source=$source") }
            .onFailure { Log.e("alarm bootstrap failed source=$source", it) }
    }

    /** 供设置页切换开关后强制重算（[armed] 守卫要放行）。 */
    fun rearm(context: Context, source: String) {
        armed = false
        arm(context, source)
    }
}
