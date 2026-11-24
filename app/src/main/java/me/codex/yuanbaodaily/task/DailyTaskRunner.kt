package me.codex.yuanbaodaily.task

import me.codex.yuanbaodaily.BuildConfig
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import me.codex.yuanbaodaily.automation.AutomationGate
import me.codex.yuanbaodaily.config.RunRequest
import me.codex.yuanbaodaily.config.YuanbaoSharedStore
import me.codex.yuanbaodaily.hook.Log
import java.util.Calendar
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Serial scheduler for the host process.
 *
 * There are deliberately several ways to wake this runner up.  The module UI
 * sends an explicit request id through an Intent and a broadcast, while the
 * old SharedPreferences/Provider flag remains as a compatibility fallback.
 * Entering 福利中心 manually is also treated as a wake-up signal.  All of
 * those signals end up on this one executor, so a task pass never overlaps
 * another pass.
 */
internal class DailyTaskRunner(
    private val context: Context,
    private val adapter: YuanbaoTaskAdapter,
) {
    private companion object {
        const val CONFIG_PREFS = "yuanbao_daily_config"
        const val STATE_LAST_REQUEST_ID = "last_consumed_request_id"
        const val STATE_SUCCESSFUL_DAY = "last_run_day"
        const val STATE_ATTEMPT_DAY = "schedule_attempt_day"
        const val STATE_ATTEMPT_COUNT = "schedule_attempt_count"
        const val STATE_NEXT_RETRY_SECONDS = "schedule_next_retry_epoch_seconds"
        const val POLL_INITIAL_DELAY_SECONDS = 6L
        const val POLL_PERIOD_SECONDS = 10L
        const val MANUAL_CHECK_DELAY_MS = 650L
        /** 收到模块运行请求后，允许多久内自动执行（够跑完一轮 + 复核）。 */
        const val AUTO_ARM_WINDOW_MS = 10 * 60 * 1000L
        const val WELFARE_COOLDOWN_MS = 90_000L
        const val MAX_SCHEDULE_ATTEMPTS_PER_DAY = 3
        const val SCHEDULE_RETRY_DELAY_MS = 30L * 60L * 1000L

        /** 每做完一项后等待多久，再回“我们→福利中心”复核（用户要求约 10 秒）。 */
        const val VERIFY_DELAY_MS = 10_000L
        const val ASK_MAX_ATTEMPTS = 4
    }

    private val store = TaskStore(context)
    private val schedulePolicy = DailySchedulePolicy(
        maxAttemptsPerDay = MAX_SCHEDULE_ATTEMPTS_PER_DAY,
        retryDelayMs = SCHEDULE_RETRY_DELAY_MS,
    )
    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "YuanbaoDaily").apply { isDaemon = true }
    }

    private val lock = Any()
    @Volatile
    private var started = false
    @Volatile
    private var passRunning = false
    private var passQueued = false
    private var pendingForcedPass = false
    private var pendingSource = "unknown"
    private var pendingScheduledDay: Int? = null
    private var lastRequestId = 0L
    private var lastWelfareTriggerAt = 0L
    @Volatile
    private var welfareSuppressedUntil = 0L
    /**
     * 模块自己拉起元宝的窗口（进程内）。
     *
     * 只用 prefs 跨进程不可靠（UI 进程写的 key 宿主有时读不到），
     * 而"模块启动元宝"这件事一定会以 RUN 请求的形式到达宿主，
     * 所以这里记在内存里最稳：收到请求就开窗，10 分钟内允许自动执行。
     */
    @Volatile
    private var armedUntilUptime = 0L

    fun start() {
        synchronized(lock) {
            if (started) return
            started = true
        }
        // A launch Intent/broadcast can arrive before this method is called;
        // the explicit request path queues its pass independently.
        executor.schedule({ poll("startup") }, POLL_INITIAL_DELAY_SECONDS, TimeUnit.SECONDS)
        executor.scheduleWithFixedDelay(
            { poll("interval") },
            POLL_PERIOD_SECONDS,
            POLL_PERIOD_SECONDS,
            TimeUnit.SECONDS,
        )
        Log.d("scheduler started enabled=${isEnabled()}")
    }

    fun onActivityResumed(activity: Activity) {
        if (!isEnabled()) return
        // Let Compose/WebView finish its first layout before inspecting the
        // semantic tree. The check itself runs off the main thread and uses
        // the adapter's main-thread bridge for UI reads.
        executor.schedule({
            if (!isEnabled()) return@schedule
            val visible = runCatching { adapter.isWelfareCenterVisible() }.getOrDefault(false)
            if (!visible) return@schedule
            syncVisibleProgress("activity_resumed")
            // 用户自己打开福利中心：读进度可以，执行不行。
            if (!autoRunAllowed()) {
                Log.d("welfare: 非模块启动 → 只读进度，不执行")
                return@schedule
            }
            if (passRunning || SystemClock.uptimeMillis() < welfareSuppressedUntil) return@schedule
            val now = SystemClock.uptimeMillis()
            synchronized(lock) {
                if (now - lastWelfareTriggerAt < WELFARE_COOLDOWN_MS || passQueued || passRunning) return@synchronized
                lastWelfareTriggerAt = now
            }
            Log.d("welfare page detected after resume activity=${activity.javaClass.name}")
            requestPass(force = true, source = "welfare_resumed")
        }, MANUAL_CHECK_DELAY_MS, TimeUnit.MILLISECONDS)
    }

    fun onActivityPaused(activity: Activity) {
        // Kept as a lifecycle seam for future host versions. No state is
        // cleared here because a WebView transition can pause/resume quickly.
    }

    /** 自检转发（typing/media/problem/template），供 adb 诊断使用。 */
    fun selfTest(mode: String): String = runCatching { adapter.selfTest(mode) }
        .onFailure { Log.e("self test $mode failed", it) }
        .getOrDefault("selfTest exception")

    /** Accept a module launch Intent or the explicit RUN_NOW broadcast. */
    fun acceptIntent(intent: Intent?, source: String) {
        if (intent == null) return
        val requestId = intent.getLongExtra(RunRequest.EXTRA_REQUEST_ID, 0L)
        val isRunAction = intent.action == RunRequest.ACTION_RUN_NOW
        if (requestId <= 0L && !isRunAction) return

        val enabled = intent.getBooleanExtra(RunRequest.EXTRA_ENABLED, true)
        if (enabled) YuanbaoSharedStore.putBoolean(context, CONFIG_PREFS, "enabled", true)
        if (intent.hasExtra(RunRequest.EXTRA_RUN_AT_MINUTES)) {
            YuanbaoSharedStore.putInt(
                context,
                CONFIG_PREFS,
                "run_at_minutes",
                intent.getIntExtra(RunRequest.EXTRA_RUN_AT_MINUTES, 180).coerceIn(0, 1439),
            )
        }
        val taggedSource = intent.getStringExtra(RunRequest.EXTRA_SOURCE)?.takeIf { it.isNotBlank() }
            ?: source
        // 收到模块的运行请求 = 这次是"模块启动的元宝"，开一个 10 分钟窗口。
        armedUntilUptime = SystemClock.uptimeMillis() + AUTO_ARM_WINDOW_MS
        Log.d("run request received id=$requestId source=$taggedSource action=${intent.action}")

        if (requestId > 0L) {
            synchronized(lock) {
                if (requestId <= lastRequestId) {
                    Log.d("run request duplicate ignored id=$requestId last=$lastRequestId")
                    return
                }
                lastRequestId = requestId
            }
            // The Provider currently stores primitive values for compatibility;
            // keep a bounded integer marker in addition to the in-memory guard.
            YuanbaoSharedStore.putInt(
                context,
                CONFIG_PREFS,
                STATE_LAST_REQUEST_ID,
                (requestId xor (requestId ushr 32)).toInt(),
            )
        }
        welfareSuppressedUntil = SystemClock.uptimeMillis() + WELFARE_COOLDOWN_MS
        requestPass(force = true, source = taggedSource)
    }

    /** 本轮是否允许"自动执行"：只认模块自己拉起元宝的那次（10 分钟窗口）。 */
    private fun autoRunAllowed(): Boolean =
        SystemClock.uptimeMillis() < armedUntilUptime ||
            YuanbaoSharedStore.isAutoPassArmed(context)

    private fun poll(source: String) {
        val enabled = isEnabled()
        val armed = autoRunAllowed()
        val runNow = YuanbaoSharedStore.getBoolean(context, CONFIG_PREFS, "run_now", false)
        val runImmediately = YuanbaoSharedStore.getBoolean(context, CONFIG_PREFS, "run_immediately", false)
        val runAtMinutes = YuanbaoSharedStore.getInt(context, CONFIG_PREFS, "run_at_minutes", 180)
        val now = Calendar.getInstance()
        val minuteOfDay = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        val day = now.get(Calendar.YEAR) * 10000 +
            (now.get(Calendar.MONTH) + 1) * 100 + now.get(Calendar.DAY_OF_MONTH)
        val nowMillis = System.currentTimeMillis()
        Log.d("scheduler poll source=$source run_now=$runNow enabled=$enabled")

        if (!enabled) return

        if (runNow) YuanbaoSharedStore.putBoolean(context, CONFIG_PREFS, "run_now", false)
        if (runImmediately) YuanbaoSharedStore.putBoolean(context, CONFIG_PREFS, "run_immediately", false)
        if (runNow || runImmediately) {
            welfareSuppressedUntil = SystemClock.uptimeMillis() + WELFARE_COOLDOWN_MS
            requestPass(force = true, source = if (runNow) "provider_run_now" else "run_immediately")
            return
        }
        val state = loadScheduleState()
        val decision = schedulePolicy.decide(
            enabled = enabled,
            minuteOfDay = minuteOfDay,
            runAtMinutes = runAtMinutes,
            day = day,
            nowMillis = nowMillis,
            state = state,
        )
        Log.d(
            "scheduler decision=$decision day=$day attempts=${state.attemptCount} " +
                "attemptDay=${state.attemptDay} successDay=${state.successfulDay} " +
                "retryAt=${state.nextRetryAtMillis}",
        )
        if (decision != DailyScheduleDecision.RUN) return
        // **只有"模块启动的元宝"才自动执行**（2026-11-14 用户要求）。
        // 到点了但用户是自己打开元宝的 —— 只同步一次进度，不抢着跑任务，
        // 也不消耗今天的重试次数（免得真正的自动轮次还没开始就被扣光）。
        if (!armed) {
            Log.d("scheduler: 非模块启动（无 arming）→ 本轮只读进度，不执行")
            runCatching { syncVisibleProgress("passive_read") }
            return
        }
        // 免 root 版：调度器跑在自己的进程里，元宝在不在前台是**不确定**的。
        // 如果这会儿元宝不在前台，就不要把这一次算进"今天已尝试 3 次"的配额 ——
        // 否则一天里前面三次恰好都在后台空转，之后用户真的打开元宝时反而
        // 因为配额用尽而不再执行（最坏的体验：用户以为这软件根本没在工作）。
        // 这里只**看**不动手：真正需要拉起元宝时由 perform() 里的守卫负责。
        if (BuildConfig.STANDALONE && !adapter.isHostForeground()) {
            Log.d("scheduler: 元宝不在前台 → 本轮不计入重试次数，等用户打开元宝后再跑")
            return
        }
        saveScheduleState(schedulePolicy.recordStarted(state, day, nowMillis))
        welfareSuppressedUntil = SystemClock.uptimeMillis() + WELFARE_COOLDOWN_MS
        requestPass(force = true, source = "daily_schedule", scheduledDay = day)
    }

    private fun requestPass(force: Boolean, source: String, scheduledDay: Int? = null) {
        synchronized(lock) {
            if (force) pendingForcedPass = true
            pendingSource = source
            if (scheduledDay != null) pendingScheduledDay = scheduledDay
            if (passQueued || passRunning) return
            passQueued = true
        }
        executor.execute { executeQueuedPass() }
    }

    private fun executeQueuedPass() {
        val source: String
        val scheduledDay: Int?
        synchronized(lock) {
            passQueued = false
            passRunning = true
            source = pendingSource
            scheduledDay = pendingScheduledDay
            pendingSource = "unknown"
            pendingScheduledDay = null
            pendingForcedPass = false
        }
        var passSucceeded = false
        try {
            Log.d("starting daily pass source=$source force=true")
            // **整轮任务独占**：自检线程（`YuanbaoDailySelfTest`）与任务线程（`YuanbaoDaily`）
            // 都会写元宝那颗 Compose 输入框。没有这道闸门时实测两路会重叠约 1.5s，
            // `ACTION_SET_TEXT`（替换）与 `ACTION_PASTE`（插入）交错 →
            // 两段提示词被拼成一条、另一条白跑（`evidence/trace_d7_ask` 实证）。
            passSucceeded = AutomationGate.exclusive("daily-pass") { runAll() }
        } catch (t: Throwable) {
            Log.e("daily pass failed", t)
        } finally {
            if (scheduledDay != null) {
                val finished = schedulePolicy.recordFinished(
                    state = loadScheduleState(),
                    day = scheduledDay,
                    success = passSucceeded,
                )
                saveScheduleState(finished)
                Log.d(
                    "scheduled pass finished day=$scheduledDay success=$passSucceeded " +
                        "attempts=${finished.attemptCount} retryAt=${finished.nextRetryAtMillis}",
                )
            }
            val rerun: Boolean
            synchronized(lock) {
                passRunning = false
                rerun = pendingForcedPass && !passQueued
                if (rerun) passQueued = true
            }
            if (rerun) executor.execute { executeQueuedPass() }
        }
    }

    private fun isEnabled(): Boolean =
        YuanbaoSharedStore.getBoolean(context, CONFIG_PREFS, "enabled", true)

    private fun runAll(): Boolean {
        try {
            // 第二道闸：即使某个入口漏了判断，这里也绝不真的去操作元宝。
            // 放在 try 里面，是为了让"校验本身出错"也走既有异常处理，不至于把调度器打死。
            if (me.codex.yuanbaodaily.security.IntegrityGuard.blocksAutomation(context)) {
                return false
            }
            // 新一轮开始：模板任务回到"怎么简单怎么来"的默认策略。
            runCatching { adapter.resetTemplatePolicy() }
            // 1) 先看今天的完成度：我们 → 福利中心
            openWelfareAndSync("pass_start")
            logPlan()
            val pending = DEFAULT_TASKS.filter { !store.progress(it).done }
            if (pending.isEmpty()) {
                Log.d("daily pass: 今日五项均已完成，无需执行")
                return true
            }
            // 2) 逐项执行：执行一项 → 等 10s → 回到“我们→福利中心”复核 → 未完成就补做
            for (task in pending) runTaskUntilDone(task)
            // 3) 收尾复核
            openWelfareAndSync("pass_finished")
            // **「没读到」不等于「没做完」。**
            //
            // 任务卡片滚出可视区时，WebView 不会把它的虚拟节点放进无障碍树，
            // 进度自然读不到。若把读不到的项一律算作未完成，整轮就永远判失败，
            // 调度器按 WAIT_RETRY 再来一整轮 —— 真机实测 2026-10-21 因此在一轮里
            // 把 P图/拍题/模板 各重复提交 3 次，服务端只认 1 次。
            //
            // 判据改成「读到的项都完成」；一项都没读到（页面没打开/没渲染）才判失败，
            // 那种情况确实需要重试。
            val observed = adapter.observedKinds()
            val judged = DEFAULT_TASKS.filter { it.kind in observed }
            val missing = DEFAULT_TASKS.map { it.kind }.filter { it !in observed }.map { it.id }
            val successful = judged.isNotEmpty() && judged.all { store.progress(it).done }
            Log.d(
                "daily pass finished successful=$successful " +
                    "judged=${judged.map { "${it.kind.id}=${store.progress(it).completed}/${it.required}" }} " +
                    "未读到=$missing",
            )
            return successful
        } finally {
            val removed = runCatching { adapter.cleanupExpiredMedia() }
                .onFailure { Log.e("generated media cleanup failed", it) }
                .getOrDefault(0)
            Log.d("daily pass media cleanup removed=$removed")
        }
    }

    /** 打印五项当前完成度，便于在日志里一眼看到"今日任务完成度"。 */
    private fun logPlan() {
        val summary = DEFAULT_TASKS.joinToString(" ") { task ->
            val progress = store.progress(task)
            "${task.kind.title}=${progress.completed}/${progress.required}"
        }
        Log.d("daily plan $summary")
    }

    /**
     * 走一次"我们 → 福利中心"并同步服务端计数。
     *
     * force = true 保证是真的重新进入页面（WebView 重新拉数据），复核才有意义。
     */
    private fun openWelfareAndSync(source: String): Int {
        val opened = runCatching { adapter.openWelfareFromMineTab(force = true) }
            .onFailure { Log.e("welfare route failed source=$source", it) }
            .getOrDefault(false)
        Log.d("welfare route source=$source opened=$opened")
        return syncVisibleProgress(source)
    }

    /**
     * 单个任务：执行 → 等待 → 复核 → 未完成就继续补做，直到 3/3 或次数用尽。
     */
    private fun runTaskUntilDone(task: DailyTask) {
        val maxAttempts = if (task.kind == DailyTaskKind.ASK) ASK_MAX_ATTEMPTS else task.executionRounds
        for (attempt in 1..maxAttempts) {
            val before = store.progress(task).completed
            Log.d("${task.kind.title}: 待完成 ${before}/${task.required}，第 $attempt/$maxAttempts 次执行")
            val result = runCatching { adapter.perform(task.kind, attempt) }
                .onFailure { Log.e("${task.kind.title}: adapter exception", it) }
                .getOrDefault(TaskActionResult(false, null))
            Log.d(
                "${task.kind.title}: actionStarted=${result.actionStarted} " +
                    "等待 ${VERIFY_DELAY_MS / 1_000}s 后回到“我们→福利中心”复核",
            )
            SystemClock.sleep(VERIFY_DELAY_MS)
            openWelfareAndSync("verify:${task.kind.id}")
            val after = store.progress(task)
            Log.d("${task.kind.title}: 复核 ${after.completed}/${after.required}")
            if (after.done) {
                Log.d("${task.kind.title}: 已确认完成 ${after.completed}/${after.required}")
                return
            }
            if (after.completed > before) {
                Log.d("${task.kind.title}: 计数从 $before 涨到 ${after.completed}，继续补做")
            } else {
                Log.d("${task.kind.title}: 计数未变化（仍 ${after.completed}/${after.required}），重试")
                // 做同款：如果"重复用同一个模板"不涨进度，下一轮自动改成换模板。
                if (task.kind == DailyTaskKind.TEMPLATE) {
                    adapter.requireTemplateVariety(
                        "复核未涨进度 ${after.completed}/${after.required}",
                    )
                }
            }
            if (attempt < maxAttempts) SystemClock.sleep(task.delayMs)
        }
        Log.d("${task.kind.title}: 本次未达到 ${task.required}/${task.required}，保留给下一次重试")
    }

    private fun loadScheduleState(): DailyScheduleState = DailyScheduleState(
        successfulDay = YuanbaoSharedStore.getInt(context, CONFIG_PREFS, STATE_SUCCESSFUL_DAY, 0),
        attemptDay = YuanbaoSharedStore.getInt(context, CONFIG_PREFS, STATE_ATTEMPT_DAY, 0),
        attemptCount = YuanbaoSharedStore.getInt(context, CONFIG_PREFS, STATE_ATTEMPT_COUNT, 0),
        nextRetryAtMillis = YuanbaoSharedStore.getInt(
            context,
            CONFIG_PREFS,
            STATE_NEXT_RETRY_SECONDS,
            0,
        ).toLong() * 1_000L,
    )

    private fun saveScheduleState(state: DailyScheduleState) {
        YuanbaoSharedStore.putInt(context, CONFIG_PREFS, STATE_SUCCESSFUL_DAY, state.successfulDay)
        YuanbaoSharedStore.putInt(context, CONFIG_PREFS, STATE_ATTEMPT_DAY, state.attemptDay)
        YuanbaoSharedStore.putInt(context, CONFIG_PREFS, STATE_ATTEMPT_COUNT, state.attemptCount)
        YuanbaoSharedStore.putInt(
            context,
            CONFIG_PREFS,
            STATE_NEXT_RETRY_SECONDS,
            (state.nextRetryAtMillis / 1_000L).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt(),
        )
    }

    private fun syncVisibleProgress(source: String): Int {
        val visible = runCatching { adapter.readVisibleProgress() }
            .onFailure { Log.e("progress sync failed source=$source", it) }
            .getOrDefault(emptyMap())
        if (visible.isEmpty()) return 0
        var updated = 0
        for (task in DEFAULT_TASKS) {
            val completed = visible[task.kind] ?: continue
            store.setConfirmed(task, completed)
            updated++
            Log.d("progress synced source=$source ${task.kind.title}: $completed/${task.required}")
        }
        return updated
    }
}
