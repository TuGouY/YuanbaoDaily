package me.codex.yuanbaodaily.task

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import me.codex.yuanbaodaily.BuildConfig
import me.codex.yuanbaodaily.automation.AccessibilityAccess
import me.codex.yuanbaodaily.automation.AccessibilityRepair
import me.codex.yuanbaodaily.automation.AutomationHostLink
import me.codex.yuanbaodaily.automation.RootInputController
import me.codex.yuanbaodaily.config.RunRequest
import me.codex.yuanbaodaily.hook.Log

/** Wakes the module, requests a pass, and starts Yuanbao when its process is cold. */
class DailyAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val appContext = context.applicationContext
        val preferences = appContext.getSharedPreferences(CONFIG_PREFS, Context.MODE_PRIVATE)
        val action = intent?.action.orEmpty()
        if (action == ScheduleAlarmManager.ACTION_DAILY_ALARM &&
            !preferences.getBoolean(ENABLED, true)
        ) {
            ScheduleAlarmManager.cancel(appContext)
            Log.d("daily alarm ignored: module disabled")
            return
        }

        when (action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            ScheduleAlarmManager.ACTION_DAILY_ALARM,
            -> handleWake(appContext, action, goAsync())
            ScheduleAlarmManager.ACTION_HEALTH_ALARM -> handleHealthCheck(appContext, goAsync())
            else -> Log.d("daily alarm ignored action=$action")
        }
    }

    /**
     * 周期性体检：无障碍服务掉了就自己重新绑上。
     *
     * 这是"重启后定位不到输入框"的自愈入口 —— 闹钟是系统级的，
     * 模块进程被 ColorOS 冻结/杀掉也照样会被拉起来干活。
     */
    private fun handleHealthCheck(context: Context, pending: PendingResult) {
        // 必须离开主线程：`ensureBound` 最坏要 900ms 沉降 + 6s 等回调，
        // 而 `onReceive` 在主线程上只有 ~10 秒，叠上开机那阵子的卡顿很容易 ANR。
        background(pending, "health alarm") {
            val connected = AccessibilityAccess.isConnected()
            val result = when {
                !connected -> AccessibilityRepair.ensureBound(context)
                !AccessibilityRepair.isListed(context) -> AccessibilityRepair.ensureBound(context, force = true)
                else -> null
            }
            if (result == null) {
                Log.d("health alarm: 服务正常")
            } else {
                Log.w("health alarm: 自愈结果=$result")
            }
            ScheduleAlarmManager.scheduleHealth(context)
        }
    }

    private fun handleWake(context: Context, source: String, pending: PendingResult) {
        val preferences = context.getSharedPreferences(CONFIG_PREFS, Context.MODE_PRIVATE)
        if (!preferences.getBoolean(ENABLED, true)) {
            ScheduleAlarmManager.cancel(context)
            pending.finish()
            return
        }
        background(pending, "wake:$source") {
            // 开工前先确保无障碍服务在线：它掉线的话后面每一步都会"定位不到输入框"。
            //
            // `graceMs`：设备刚开机时系统自己也在绑服务，别跟它抢 —— 先给它 8 秒，
            // 只是慢一步而已；真正掉线时这 8 秒白等，但避免了"系统正在绑、我们
            // 又把设置改掉"的互相打断。
            val repair = AccessibilityRepair.ensureBound(context, graceMs = A11Y_GRACE_MS)
            Log.d("alarm a11y preflight: $repair")

            if (BuildConfig.STANDALONE) {
                // 免 root：没有"注入进元宝的运行时"可以发广播，调度器就在本进程里跑。
                // 先把元宝拉到前台，再让它自己从首页开始导航。
                startTarget(context)
                SystemClock.sleep(4_000L)
                StandaloneRuntime.requestRun(context, source)
                ScheduleAlarmManager.reschedule(context)
                Log.d("daily alarm handled source=$source mode=standalone")
                return@background
            }

            val previous = preferences.getLong(RunRequest.KEY_REQUEST_ID, 0L)
            val requestId = maxOf(System.currentTimeMillis(), previous + 1L)
            val runAtMinutes = preferences.getInt(RUN_AT_MINUTES, 180).coerceIn(0, 1_439)
            preferences.edit()
                .putLong(RunRequest.KEY_REQUEST_ID, requestId)
                .putBoolean(RunRequest.KEY_RUN_NOW, true)
                .apply()

            val request = Intent(RunRequest.ACTION_RUN_NOW)
                .setPackage(TARGET_PACKAGE)
                .putExtra(RunRequest.EXTRA_REQUEST_ID, requestId)
                .putExtra(RunRequest.EXTRA_ENABLED, true)
                .putExtra(RunRequest.EXTRA_RUN_AT_MINUTES, runAtMinutes)
                .putExtra(RunRequest.EXTRA_SOURCE, "alarm:$source")
            // Offer the command binder before asking for the run: the host cannot
            // look the module package up on its own.
            AutomationHostLink.publishBurst(context, "alarm:$source")
            runCatching { context.sendBroadcast(request) }
                .onFailure { Log.e("alarm run request broadcast failed", it) }

            if (!startTarget(context)) {
                if (!BuildConfig.ROOT_MODE) {
                    // 免 root 版没有 su 兜底：普通启动失败就只能如实记录，
                    // 绝不能去 fork `su`（那会静默挂满超时，任务整体卡死）。
                    Log.w("alarm Yuanbao launch failed and 免root版 has no root fallback")
                } else {
                    Log.d("alarm normal Yuanbao launch failed; trying bounded root launch")
                    val result = runCatching {
                        RootInputController.perform(Bundle().apply { putString("action", "launch_target") })
                    }.getOrNull()
                    Log.d("alarm root Yuanbao launch dispatched=${result?.getBoolean("ok", false) == true}")
                }
            }
            ScheduleAlarmManager.reschedule(context)
            Log.d("daily alarm handled source=$source requestId=$requestId")
        }
    }

    /**
     * 把一段可能耗秒的工作挪到后台线程，并用 [PendingResult] 保住进程不被回收。
     *
     * 为什么必须这样：`onReceive` 在主线程运行，超过 ~10 秒就 ANR；
     * 而自愈最坏要 ~7 秒、再叠上开机宽限就更危险。
     * 这么做等于告诉系统"我还在干活，别把我算成没响应"。
     */
    private fun background(pending: PendingResult, label: String, block: () -> Unit) {
        Thread {
            try {
                block()
            } catch (error: Throwable) {
                Log.e("alarm background $label failed", error)
            } finally {
                runCatching { pending.finish() }
            }
        }.apply { name = "yuanbao-alarm-$label" }.start()
    }

    private fun startTarget(context: Context): Boolean = runCatching {
        val launch = context.packageManager.getLaunchIntentForPackage(TARGET_PACKAGE)
            ?: return@runCatching false
        // 定时任务拉起元宝，同样要打 arming，否则宿主里的调度器会认为是"用户自己打开的"而拒绝执行。
        runCatching { me.codex.yuanbaodaily.config.YuanbaoSharedStore.armAutoPass(context) }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        context.startActivity(launch)
        true
    }.onFailure { Log.e("alarm normal Yuanbao launch failed", it) }.getOrDefault(false)

    private companion object {
        const val CONFIG_PREFS = "yuanbao_daily_config"
        const val ENABLED = "enabled"
        const val RUN_AT_MINUTES = "run_at_minutes"
        const val TARGET_PACKAGE = "com.tencent.hunyuan.app.chat"

        /** 开机/定时唤醒时，先让系统自己去绑服务多久，再考虑动手重绑。 */
        const val A11Y_GRACE_MS = 8_000L
    }
}
