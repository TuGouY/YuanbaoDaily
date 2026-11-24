package me.codex.yuanbaodaily.task

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import me.codex.yuanbaodaily.BuildConfig
import me.codex.yuanbaodaily.R
import me.codex.yuanbaodaily.hook.Log
import me.codex.yuanbaodaily.ui.MainActivity

/**
 * **保活前台服务**（只有免 root 版用）。
 *
 * ## 为什么需要它
 *
 * root 版的调度器跑在元宝进程里 —— 用户一打开元宝，进程天然就是前台。
 * 免 root 版的调度器跑在自己的进程里，只有当无障碍服务绑着的时候才享受
 * "perceptible" 级的优先级；一旦用户很久不打开元宝、或者系统开始收紧内存，
 * 这个进程就可能被回收，而**回收之后没有人会把它拉起来**
 * （免 root 版没有 `WRITE_SECURE_SETTINGS`，自愈写不了系统设置）。
 *
 * 前台服务把进程钉在"用户可见的正在运行"这一档，是普通 App 能拿到的最高优先级。
 *
 * ## 为什么是 specialUse
 *
 * Android 14（targetSdk 34）要求每个前台服务声明 `foregroundServiceType`，
 * 且 `specialUse` 必须配一条 `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` 说明（见 lite 清单）。
 * 这个服务不属于 media / dataSync / location / camera 任何一类 —— 它只是保活，
 * 硬套别的类型会在上架审核或系统策略里被判为类型滥用。
 *
 * ## 一条常驻通知是**代价**，不是副作用
 *
 * 系统不允许"静默保活"。既然占了前台服务这个位置，就必须让用户看得见、能关掉，
 * 所以通知常驻、可点击回到界面。这同时也是"无障碍掉线"最好的提醒载体：
 * 用户看到通知还在，就知道服务还在跑。
 */
class KeepAliveService : Service() {

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForegroundCompat(buildNotification(getString(R.string.keepalive_idle)))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 允许外部只更新文案（例如"下次执行 03:00"）。
        intent?.getStringExtra(EXTRA_TEXT)?.takeIf(String::isNotBlank)?.let { text ->
            notify(buildNotification(text))
        }
        // START_STICKY：被系统杀掉后由系统重建（不带 intent），至少能把通知恢复出来。
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundCompat(notification: Notification) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        }.onFailure {
            // 到这一步失败基本只有一种原因：系统拒绝（后台启动限制 / 类型不被允许）。
            // 不能崩 —— 崩了用户看到的是"应用反复停止运行"，比没有保活更糟。
            Log.e("keepalive: startForeground 失败，退化为普通后台进程", it)
            stopSelf()
        }
    }

    private fun notify(notification: Notification) {
        runCatching {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(NOTIFICATION_ID, notification)
        }.onFailure { Log.e("keepalive: 更新通知失败", it) }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.keepalive_channel_name),
            // LOW：不出声、不弹横幅。它是状态指示，不是打扰。
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.keepalive_channel_description)
            setShowBadge(false)
            enableVibration(false)
            enableLights(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentIntent(open)
            .setOngoing(true)
            .setShowWhen(false)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "yuanbao_daily_keepalive"
        const val NOTIFICATION_ID = 0x0DA1
        const val EXTRA_TEXT = "me.codex.yuanbaodaily.extra.KEEPALIVE_TEXT"

        /**
         * 确保前台服务在跑。
         *
         * **只在免 root 版生效**：root 版的运行时在元宝进程里，不需要也不应该
         * 为它常驻一条通知。
         *
         * 所有失败都吞掉并记日志：Android 12+ 在后台调用 `startForegroundService`
         * 会抛 `ForegroundServiceStartNotAllowedException`，
         * 这在"闹钟刚从冻结状态唤醒进程"时完全可能发生 ——
         * 那种情况下放弃保活是对的，但绝不能因此让任务流程崩掉。
         */
        fun ensureStarted(context: Context, text: String? = null): Boolean {
            if (!BuildConfig.STANDALONE) return false
            val app = context.applicationContext
            return runCatching {
                val intent = Intent(app, KeepAliveService::class.java).apply {
                    text?.let { putExtra(EXTRA_TEXT, it) }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    app.startForegroundService(intent)
                } else {
                    app.startService(intent)
                }
                true
            }.onFailure {
                Log.w(
                    "keepalive: 启动前台服务被拒（${it.javaClass.simpleName}: ${it.message}）" +
                        " → 退化为普通后台进程",
                )
            }.getOrDefault(false)
        }

        /** 只更新通知文案，不重启服务。 */
        fun update(context: Context, text: String) {
            if (!BuildConfig.STANDALONE) return
            runCatching {
                val app = context.applicationContext
                app.startService(
                    Intent(app, KeepAliveService::class.java).putExtra(EXTRA_TEXT, text),
                )
            }.onFailure { Log.d("keepalive: 更新文案失败（服务可能没在跑）：${it.message}") }
        }
    }
}
