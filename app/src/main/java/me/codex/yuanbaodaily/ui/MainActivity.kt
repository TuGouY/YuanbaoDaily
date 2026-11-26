package me.codex.yuanbaodaily.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import me.codex.yuanbaodaily.BuildConfig
import me.codex.yuanbaodaily.R
import me.codex.yuanbaodaily.automation.AccessibilityRepair
import me.codex.yuanbaodaily.automation.AutomationAccessibilityService
import me.codex.yuanbaodaily.automation.AutomationHostLink
import me.codex.yuanbaodaily.automation.DeviceVendorPolicy
import me.codex.yuanbaodaily.automation.PrivilegedAccess
import me.codex.yuanbaodaily.config.RunRequest
import me.codex.yuanbaodaily.hook.Log
import me.codex.yuanbaodaily.security.IntegrityGuard
import me.codex.yuanbaodaily.task.DEFAULT_TASKS
import me.codex.yuanbaodaily.task.DailyTask
import me.codex.yuanbaodaily.task.DailyTaskKind
import me.codex.yuanbaodaily.task.ScheduleAlarmManager
import me.codex.yuanbaodaily.task.TaskStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import rikka.shizuku.Shizuku

/** A small, dependency-free dashboard so the module can be opened directly. */
class MainActivity : Activity() {
    private val uiHandler = Handler(Looper.getMainLooper())
    private lateinit var progressLabel: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var summaryLabel: TextView
    private lateinit var accessibilityStatusLabel: TextView
    private lateinit var taskList: LinearLayout
    private lateinit var enabledSwitch: Switch
    private lateinit var store: TaskStore

    /** 免 root 版专属：特权通道（Shizuku / adb 授权）状态行。 */
    private var privilegeStatusLabel: TextView? = null

    /** 免 root 版专属：「授权 Shizuku」按钮（只在 Shizuku 在跑、还没授权时显示）。 */
    private var shizukuButton: TextView? = null

    /** 特权状态探测的节流（Shizuku binder 调用没必要每 3 秒一次）。 */
    private var privilegeCheckedAt = 0L

    /**
     * Shizuku 授权结果回调。
     *
     * 必须注册在 Activity 上并用**同一个 requestCode** —— Shizuku 是进程级绑定，
     * 回调不绑定 Activity 就会在旋转/重建后丢失，用户点了「允许」界面却没有任何变化。
     */
    private val shizukuPermissionListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == PrivilegedAccess.REQUEST_CODE) {
                Log.w("ui: Shizuku 授权结果 requestCode=$requestCode grantResult=$grantResult")
                privilegeCheckedAt = 0L
                uiHandler.post { refreshPrivilege() }
            }
        }

    private val refreshRunnable = object : Runnable {
        override fun run() {
            refresh()
            uiHandler.postDelayed(this, 3_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(247, 248, 252)
        window.navigationBarColor = Color.rgb(247, 248, 252)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        store = TaskStore(this)
        setContentView(buildScreen())
        // 界面照常打开（用户有权知道发生了什么），只有自动化被拦。
        // 这里不弹对话框：改包的包本来就不是给用户看的，提示一次即可。
        if (IntegrityGuard.verdict(this) == IntegrityGuard.Verdict.TAMPERED) {
            Toast.makeText(this, "签名校验失败：当前安装包不是官方版本，自动化已停止", Toast.LENGTH_LONG).show()
        }
        if (!BuildConfig.ROOT_MODE) {
            // root 版用不到 Shizuku（它本来就有 su），所以只在免 root 版注册。
            runCatching { Shizuku.addRequestPermissionResultListener(shizukuPermissionListener) }
                .onFailure { Log.w("ui: 注册 Shizuku 回调失败：${it.message}") }
        }
        ScheduleAlarmManager.reschedule(this)
        refresh()
        // 打开模块界面 = 用户手动把模块"叫醒"了一次，这是最自然的一次修复机会：
        // 顺手确认无障碍服务还绑着（ColorOS 重启后会把本模块从无障碍列表里摘掉）。
        // 后台线程跑，别卡住界面 —— ensureBound 最坏要几秒。
        Thread({
            val report = runCatching { AccessibilityRepair.ensureBound(this) }
                .onFailure { Log.e("ui a11y ensure failed", it) }
                .getOrDefault("failed 异常")
            Log.w("ui a11y ensure report=$report")
        }, "YuanbaoDailyA11yEnsure").apply { isDaemon = true }.start()
    }

    override fun onDestroy() {
        runCatching { Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener) }
        uiHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        // Re-establish the command link every time the UI comes up: the host
        // cannot look the module up on its own (package visibility).
        AutomationHostLink.publishBurst(this, "ui resumed")
        uiHandler.removeCallbacks(refreshRunnable)
        uiHandler.post(refreshRunnable)
    }

    override fun onPause() {
        uiHandler.removeCallbacks(refreshRunnable)
        super.onPause()
    }

    private fun buildScreen(): View {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.rgb(247, 248, 252))
            isFillViewport = true
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(22), dp(20), dp(28))
        }
        scroll.addView(content, ViewGroup.LayoutParams(-1, -2))

        val titleRow = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleBlock = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titleBlock.addView(text(getString(R.string.app_name), 27, Color.rgb(30, 32, 48), true))
        titleBlock.addView(text(todayText(), 13, Color.rgb(120, 124, 145), false).apply {
            setPadding(0, dp(4), 0, 0)
        })
        titleRow.addView(titleBlock, LinearLayout.LayoutParams(0, -2, 1f))
        enabledSwitch = Switch(this).apply {
            text = "自动执行"
            textSize = 13f
            setTextColor(Color.rgb(82, 86, 108))
            isChecked = config().getBoolean("enabled", true)
            setOnCheckedChangeListener { _: CompoundButton, checked: Boolean ->
                config().edit().putBoolean("enabled", checked).apply()
                if (checked) ScheduleAlarmManager.reschedule(this@MainActivity)
                else ScheduleAlarmManager.cancel(this@MainActivity)
                Toast.makeText(this@MainActivity, if (checked) "已开启自动执行" else "已暂停自动执行", Toast.LENGTH_SHORT).show()
            }
        }
        titleRow.addView(enabledSwitch, LinearLayout.LayoutParams(-2, -2))
        content.addView(titleRow)
        content.addView(space(18))

        val progressCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(17), dp(18), dp(17))
            background = rounded(Color.rgb(101, 88, 245), 18)
        }
        val progressTop = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val progressTitle = text("今日任务进度", 14, Color.WHITE, true)
        progressTop.addView(progressTitle, LinearLayout.LayoutParams(0, -2, 1f))
        progressLabel = text("0 / 15", 22, Color.WHITE, true)
        progressTop.addView(progressLabel)
        progressCard.addView(progressTop)
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            progressDrawable = rounded(Color.WHITE, 99)
        }
        progressCard.addView(progressBar, LinearLayout.LayoutParams(-1, dp(7)).apply { topMargin = dp(15) })
        summaryLabel = text("完成后会自动保留当天进度", 12, Color.argb(220, 255, 255, 255), false)
        progressCard.addView(summaryLabel, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        content.addView(progressCard, LinearLayout.LayoutParams(-1, -2))
        content.addView(space(22))

        val sectionRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        sectionRow.addView(text("今日任务", 18, Color.rgb(30, 32, 48), true), LinearLayout.LayoutParams(0, -2, 1f))
        sectionRow.addView(text("每项 ${DEFAULT_TASKS.first().required} 次", 12, Color.rgb(137, 140, 158), false))
        content.addView(sectionRow)
        content.addView(space(10))

        taskList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(taskList)
        content.addView(space(14))

        val permissionCard = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(15), dp(13), dp(15), dp(13))
            background = rounded(Color.WHITE, 14)
        }
        val permissionText = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        permissionText.addView(text("自动化权限", 13, Color.rgb(55, 58, 80), true))
        accessibilityStatusLabel = text("正在检查无障碍服务", 12, Color.rgb(137, 140, 158), false).apply {
            setPadding(0, dp(3), 0, 0)
        }
        permissionText.addView(accessibilityStatusLabel)
        permissionCard.addView(permissionText, LinearLayout.LayoutParams(0, -2, 1f))
        val permissionButton = TextView(this).apply {
            text = "无障碍设置"
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(101, 88, 245))
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = rounded(Color.rgb(241, 239, 255), 10)
            setOnClickListener {
                runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                    .onFailure {
                        Toast.makeText(this@MainActivity, "系统未提供无障碍设置入口", Toast.LENGTH_SHORT).show()
                    }
            }
        }
        permissionCard.addView(permissionButton)
        content.addView(permissionCard, LinearLayout.LayoutParams(-1, -2))
        content.addView(space(12))
        if (!BuildConfig.ROOT_MODE) {
            content.addView(buildLiteCard(), LinearLayout.LayoutParams(-1, -2))
            content.addView(space(12))
        }

        // 只保留「立即执行一次」整行按钮：原「执行时间」框与并排的「重置今日」都被判定为无用入口，已删除。
        val runNow = TextView(this).apply {
            text = "立即执行一次"
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = rounded(Color.rgb(101, 88, 245), 12)
            setOnClickListener {
                val previous = config().getLong("run_request_id", 0L)
                val requestId = maxOf(System.currentTimeMillis(), previous + 1L)
                val written = config().edit()
                    .putBoolean("enabled", true)
                    .putLong("run_request_id", requestId)
                    .putBoolean("run_now", true)
                    .commit()
                if (!written) {
                    Toast.makeText(this@MainActivity, "执行请求写入失败，请重试", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                ScheduleAlarmManager.reschedule(this@MainActivity)
                openYuanbao(requestId)
            }
        }
        content.addView(runNow, LinearLayout.LayoutParams(-1, dp(46)))
        content.addView(space(18))
        content.addView(buildChannelCard(), LinearLayout.LayoutParams(-1, -2))
        // 页面最底部的说明：版本约束提示，放在频道卡下方作为收尾。
        content.addView(text("模块仅在腾讯元宝主进程和指定版本中运行", 11, Color.rgb(156, 159, 174), false).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(14), 0, 0)
        })
        return scroll
    }

    /**
     * 页面最底部的频道入口。
     *
     * 只干一件事：把用户导到 Telegram 频道看更新与反馈。点击优先交给系统打开
     * （有 Telegram 就直接进 App，没有就进浏览器），两者都不可用时**退回复制链接**
     * —— 不给用户一个点了没反应的按钮。长按同样是复制，方便没装 Telegram 的人。
     *
     * 注意：这里不能用 `resolveActivity` 预判 —— Android 11 起查询 https 的 VIEW
     * 需要 `<queries>` 声明，查不到会误判成"打不开"；直接 startActivity 再兜底更可靠。
     */
    private fun buildChannelCard(): View {
        val card = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = rounded(Color.WHITE, 14)
            isClickable = true
            isFocusable = true
            setOnClickListener { openChannel() }
            setOnLongClickListener {
                copyChannelLink()
                true
            }
        }
        // 图标用 Telegram 官方纸飞机（矢量），底色取 Telegram 蓝，尺寸与上面 36dp 的任务图标对齐。
        val icon = ImageView(this).apply {
            setImageResource(R.drawable.ic_telegram)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dp(9), dp(9), dp(9), dp(9))
            background = rounded(Color.rgb(41, 161, 218), 10)
            contentDescription = "Telegram"
        }
        card.addView(icon, LinearLayout.LayoutParams(dp(36), dp(36)))
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(text("Telegram 频道", 13, Color.rgb(55, 58, 80), true))
        body.addView(text("@YuanbaoDaily · 版本更新 / 使用反馈", 12, Color.rgb(137, 140, 158), false).apply {
            setPadding(0, dp(3), 0, 0)
        })
        card.addView(body, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(12) })
        card.addView(text("›", 20, Color.rgb(170, 173, 188), false))
        return card
    }

    private fun openChannel() {
        val opened = runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(CHANNEL_URL)))
            true
        }.getOrDefault(false)
        if (!opened) copyChannelLink()
    }

    private fun copyChannelLink() {
        runCatching {
            getSystemService(ClipboardManager::class.java)
                ?.setPrimaryClip(ClipData.newPlainText("Telegram", CHANNEL_URL))
        }
        Toast.makeText(this, "已复制频道链接：$CHANNEL_URL", Toast.LENGTH_SHORT).show()
    }

    private fun refresh() {
        if (!::taskList.isInitialized) return
        val completed = DEFAULT_TASKS.sumOf { store.progress(it).completed }
        val required = DEFAULT_TASKS.sumOf { it.required }
        progressLabel.text = String.format(Locale.ROOT, "%d / %d", completed, required)
        progressBar.progress = if (required == 0) 0 else completed * 100 / required
        summaryLabel.text = if (completed >= required) "今日任务已全部完成" else "还剩 ${required - completed} 次，模块会自动继续执行"
        if (::enabledSwitch.isInitialized) enabledSwitch.isChecked = config().getBoolean("enabled", true)
        taskList.removeAllViews()
        DEFAULT_TASKS.forEachIndexed { index, task ->
            taskList.addView(taskCard(task), LinearLayout.LayoutParams(-1, -2).apply {
                if (index > 0) topMargin = dp(9)
            })
        }
        if (::accessibilityStatusLabel.isInitialized) {
            val connected = AutomationAccessibilityService.isConnected()
            val enabledInSettings = accessibilityEnabledInSettings()
            // App uids are userId * 100000 + appId, so this yields the Android
            // user the module runs in without touching hidden APIs.
            val userId = Process.myUid() / 100_000
            accessibilityStatusLabel.text = when {
                connected -> "无障碍已连接"
                // A cloned ("双开") module lives in another Android user. That
                // user's system normally never binds accessibility services, and
                // the switch may not even show there, so say what to do instead.
                userId != 0 -> if (BuildConfig.ROOT_MODE) {
                    "分身空间(user=$userId)不绑定无障碍 · 请用 Root 通道"
                } else {
                    "分身空间(user=$userId)不绑定无障碍 · 请在主空间使用"
                }
                enabledInSettings -> "无障碍开关已开启 · 等待系统连接"
                else -> "无障碍未开启 · 建议开启后执行"
            }
            accessibilityStatusLabel.setTextColor(
                when {
                    connected -> Color.rgb(42, 148, 101)
                    userId != 0 -> Color.rgb(190, 130, 57)
                    enabledInSettings -> Color.rgb(190, 130, 57)
                    else -> Color.rgb(190, 94, 57)
                },
            )
            if (!BuildConfig.ROOT_MODE) refreshPrivilege()
        }
    }

    /**
     * 界面上不再显示 root 通道状态行（用户要求删掉那一行）。
     *
     * root 是否可用仍由 `AutomationBridge.rootAvailable` 在执行链路内部自行判断，
     * 只是不再把这个技术细节摊到界面上 —— 无障碍能用的时候它本来也不参与。
     */


    private fun accessibilityEnabledInSettings(): Boolean {
        val component = ComponentName(this, AutomationAccessibilityService::class.java)
            .flattenToString()
        val enabled = runCatching {
            Settings.Secure.getString(
                contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ).orEmpty()
        }.getOrDefault("")
        return enabled.split(':').any { it.equals(component, ignoreCase = true) }
    }

    private fun openYuanbao(requestId: Long) {
        val launchIntent = packageManager.getLaunchIntentForPackage(TARGET_PACKAGE)
        if (launchIntent == null) {
            Toast.makeText(this, "未找到元宝 App，请先安装或确认包名", Toast.LENGTH_LONG).show()
            return
        }
        val runAtMinutes = config().getInt("run_at_minutes", 180)
        // 打上"这次是模块拉起的"标记：宿主里的调度器只在这个窗口内自动执行。
        // 用户自己点开元宝时没有这个标记，模块就不会抢跑。
        runCatching {
            me.codex.yuanbaodaily.config.YuanbaoSharedStore.armAutoPass(this)
        }.onFailure { android.util.Log.e("arm auto pass failed", it.toString()) }
        launchIntent.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP,
        )
        launchIntent.putExtra(RunRequest.EXTRA_REQUEST_ID, requestId)
        launchIntent.putExtra(RunRequest.EXTRA_ENABLED, true)
        launchIntent.putExtra(RunRequest.EXTRA_RUN_AT_MINUTES, runAtMinutes)
        runCatching {
            sendRunRequest(requestId, runAtMinutes, "ui_before_launch")
            startActivity(launchIntent)
            // Delivery can race host process creation. Repeat after startup and
            // once after a singleTop activity receives a new intent.
            uiHandler.postDelayed({ sendRunRequest(requestId, runAtMinutes, "ui_after_launch") }, 1_000L)
            uiHandler.postDelayed({ sendRunRequest(requestId, runAtMinutes, "ui_retry") }, 4_000L)
            Toast.makeText(this, "已发送执行请求，正在打开元宝", Toast.LENGTH_SHORT).show()
        }.onFailure {
            Toast.makeText(this, "打开元宝失败：${it.message ?: "未知错误"}", Toast.LENGTH_LONG).show()
        }
    }

    private fun sendRunRequest(requestId: Long, runAtMinutes: Int, source: String) {
        // Hand the module's command binder to the host first: package visibility
        // blocks every host-initiated lookup, so this is the channel that makes
        // the accessibility bridge reachable again.
        AutomationHostLink.publishBurst(this, "run request $source")
        sendBroadcast(
            Intent(RunRequest.ACTION_RUN_NOW)
                .setPackage(TARGET_PACKAGE)
                .putExtra(RunRequest.EXTRA_REQUEST_ID, requestId)
                .putExtra(RunRequest.EXTRA_ENABLED, true)
                .putExtra(RunRequest.EXTRA_RUN_AT_MINUTES, runAtMinutes)
                .putExtra(RunRequest.EXTRA_SOURCE, source),
        )
    }
    private fun taskCard(task: DailyTask): View {
        val progress = store.progress(task)
        val card = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(13), dp(14), dp(13))
            background = rounded(Color.WHITE, 14)
        }
        val icon = TextView(this).apply {
            text = iconFor(task.kind)
            textSize = 17f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(101, 88, 245))
            background = rounded(Color.rgb(241, 239, 255), 12)
        }
        card.addView(icon, LinearLayout.LayoutParams(dp(42), dp(42)))
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(8), 0)
        }
        body.addView(text(task.kind.title, 15, Color.rgb(45, 48, 68), true))
        body.addView(text("今日 ${progress.completed}/${task.required} 次", 12, Color.rgb(137, 140, 158), false).apply {
            setPadding(0, dp(4), 0, 0)
        })
        card.addView(body, LinearLayout.LayoutParams(0, -2, 1f))
        val status = TextView(this).apply {
            text = if (progress.done) "已完成" else "进行中"
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(if (progress.done) Color.rgb(42, 148, 101) else Color.rgb(101, 88, 245))
            setPadding(dp(9), dp(6), dp(9), dp(6))
            background = rounded(if (progress.done) Color.rgb(230, 248, 239) else Color.rgb(241, 239, 255), 9)
        }
        card.addView(status)
        return card
    }

    private fun config() = getSharedPreferences("yuanbao_daily_config", MODE_PRIVATE)

    /**
     * **免 root 版专属卡片**：机型适配 + 特权通道。
     *
     * 分两块，因为它们解决的是两件不同的事：
     * 1. **机型适配**：不同厂商后台策略不同，"自启动/关联启动/省电"的开关位置
     *    也完全不同（见 [DeviceVendorPolicy]）。这里按检测到的厂商给出**各自的路径**，
     *    而不是只写 ColorOS 那一套。
     * 2. **特权通道**：无障碍掉线后能不能自愈，取决于有没有 Shizuku 或 adb 授权。
     *    Shizuku 在跑但没授权时，这里直接给一个「授权 Shizuku」按钮。
     */
    private fun buildLiteCard(): LinearLayout {
        val vendor = DeviceVendorPolicy.detect(Build.MANUFACTURER.orEmpty(), Build.BRAND.orEmpty())
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(15), dp(13), dp(15), dp(13))
            background = rounded(Color.WHITE, 14)
        }
        card.addView(text("免 root 版 · 后台运行设置", 13, Color.rgb(55, 58, 80), true))
        card.addView(
            text(DeviceVendorPolicy.hint(vendor), 12, Color.rgb(110, 114, 136), false).apply {
                setPadding(0, dp(4), 0, dp(6))
            },
        )
        DeviceVendorPolicy.requiredToggles(vendor).forEach { line ->
            card.addView(
                text("· $line", 11, Color.rgb(120, 124, 146), false).apply {
                    setPadding(dp(2), dp(2), 0, dp(2))
                },
            )
        }

        privilegeStatusLabel = text("特权通道：检查中…", 11, Color.rgb(137, 140, 158), false).apply {
            setPadding(0, dp(9), 0, 0)
        }
        card.addView(privilegeStatusLabel)

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, 0)
        }
        shizukuButton = TextView(this).apply {
            text = "授权 Shizuku"
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = rounded(Color.rgb(101, 88, 245), 10)
            visibility = View.GONE
            setOnClickListener {
                val requested = runCatching {
                    Shizuku.requestPermission(PrivilegedAccess.REQUEST_CODE)
                    true
                }.onFailure {
                    Toast.makeText(
                        this@MainActivity,
                        "无法请求授权：" + (it.message ?: it.javaClass.simpleName),
                        Toast.LENGTH_SHORT,
                    ).show()
                }.getOrDefault(false)
                if (requested) Log.w("ui: 已请求 Shizuku 授权")
            }
        }
        row.addView(shizukuButton)

        val details = TextView(this).apply {
            text = "应用设置"
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(101, 88, 245))
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = rounded(Color.rgb(241, 239, 255), 10)
            setOnClickListener {
                // 用**系统标准**的应用详情页，不硬跳厂商的安全中心 ——
                // 厂商组件名在版本之间会变，跳错时用户以为设置好了，其实没有。
                runCatching {
                    startActivity(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            android.net.Uri.parse("package:$packageName"),
                        ),
                    )
                }.onFailure {
                    Toast.makeText(this@MainActivity, "系统未提供应用设置入口", Toast.LENGTH_SHORT).show()
                }
            }
        }
        val lp = LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) }
        row.addView(details, lp)
        card.addView(row)
        return card
    }

    /**
     * 刷新"特权通道"那一行。
     *
     * 探测要碰 Shizuku 的 binder，所以放到后台线程；UI 只读结果。
     * 节流 5 秒：这个界面的 refresh 每 3 秒跑一次，没必要每次都问一遍。
     */
    private fun refreshPrivilege() {
        if (BuildConfig.ROOT_MODE) return
        val label = privilegeStatusLabel ?: return
        val now = System.currentTimeMillis()
        if (now - privilegeCheckedAt < 5_000L) return
        privilegeCheckedAt = now
        Thread({
            val status = runCatching { PrivilegedAccess.status(applicationContext) }.getOrNull()
            uiHandler.post {
                if (isFinishing || isDestroyed) return@post
                if (status == null) {
                    label.text = "特权通道：检测失败"
                    label.setTextColor(Color.rgb(190, 94, 57))
                    return@post
                }
                label.text = "特权通道：" + status.explanation
                label.setTextColor(
                    if (status.usable) Color.rgb(42, 148, 101) else Color.rgb(190, 130, 57),
                )
                // 只有"Shizuku 在跑但本应用没授权"时才给按钮 ——
                // 没装 Shizuku 时给按钮没意义（点了只会抛异常）。
                shizukuButton?.visibility = if (status.shizukuAlive && !status.shizukuGranted) {
                    View.VISIBLE
                } else {
                    View.GONE
                }
            }
        }, "YuanbaoDailyPrivilegeProbe").apply { isDaemon = true }.start()
    }

    private fun text(value: String, size: Int, color: Int, bold: Boolean): TextView = TextView(this).apply {
        text = value
        textSize = size.toFloat()
        setTextColor(color)
        typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    }

    private fun rounded(color: Int, radius: Int): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }

    private fun space(height: Int): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(height))
    }

    private fun iconFor(kind: DailyTaskKind): String = when (kind) {
        DailyTaskKind.ASK -> "问"
        DailyTaskKind.WRITING -> "写"
        DailyTaskKind.PIC -> "图"
        DailyTaskKind.PROBLEM -> "题"
        DailyTaskKind.TEMPLATE -> "同"
    }

    private fun todayText(): String = SimpleDateFormat("yyyy年M月d日 · 每日任务", Locale.CHINA).format(Date())

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    private companion object {
        const val TARGET_PACKAGE = "com.tencent.hunyuan.app.chat"

        /** 官方 Telegram 频道：放页面最底部做更新发布与反馈入口。 */
        const val CHANNEL_URL = "https://t.me/YuanbaoDaily"
    }
}
