package me.codex.yuanbaodaily.automation

import android.util.Log as AndroidLog

/**
 * 进程级"同一时刻只允许一路自动化在跑"的闸门。
 *
 * ## 为什么必须有它（真机 `evidence/trace_d7_ask` 实证）
 *
 * 宿主进程里同时存在两条独立线程：
 * - `YuanbaoDailySelfTest`（[me.codex.yuanbaodaily.hook.YuanbaoRuntime.registerSelfTestReceiver] 起）
 * - `YuanbaoDaily`（[me.codex.yuanbaodaily.task.DailyTaskRunner] 起）
 *
 * 两者都会调用 `sendPrompt`，而 `sendPrompt` 会**写同一个 Compose 输入框再点发送**。
 * 没有互斥时实测出现过：
 *
 * ```text
 * 22:32:46.9  selfTest 线程进入 sendPrompt(prompt1)
 * 22:32:47.5  任务线程进入 sendPrompt(prompt2)   ← 与上面重叠 ~1.5s
 * ```
 *
 * 两条线程的 `ACTION_SET_TEXT`（整段替换）与 `ACTION_PASTE`（插入）交错，结果：
 * 1. 屏幕上的消息是**两段提示词拼在一起**的
 *    （`关于做好当天的简短复盘,请给我三个…排序。关于做好当天的简短复盘,请用步骤清单…`）；
 * 2. 其中一条线程的 `fillText` 撞上另一条刚写好的内容，被"幂等/已替换"吃掉，
 *    于是出现 `filled=true` 但输入框其实是空的、`submitted=false` —— **那一次任务白跑**。
 *
 * 这些都是"两路自动化抢一个输入框"造成的，**不是**坐标/几何的问题，
 * 所以再怎么写选择器也修不掉，必须在入口串行化。
 *
 * ## 用法
 *
 * 只在**顶层入口**加锁（自检整轮、任务单轮），不要给每个小函数都套一层 ——
 * 粒度太细的话两路仍会在"写文案"和"点发送"之间被切开。
 * 锁是**可重入**的，所以入口加锁后内部函数即使再加也不会自锁。
 */
internal object AutomationGate {

    private val lock = java.util.concurrent.locks.ReentrantLock()

    /** 上一次持锁者/等待情况，便于日志里说清"是谁把谁挡住了"。 */
    @Volatile
    private var holder: String? = null

    /**
     * 独占执行 [block]；拿不到锁就**等**（默认最多 [waitMs]）。
     *
     * 等待超时不会抛异常，而是仍然串行进入 —— 自动化是"宁可慢，不可并行"，
     * 超时放弃会让两路又并行起来，比多等几秒危险得多。
     */
    fun <T> exclusive(what: String, waitMs: Long = DEFAULT_WAIT_MS, block: () -> T): T {
        val start = System.nanoTime()
        if (!lock.tryLock(waitMs, java.util.concurrent.TimeUnit.MILLISECONDS)) {
            AndroidLog.w(
                TAG,
                "自动化闸门：$what 等 ${waitMs}ms 仍未拿到锁（持锁者=$holder）→ 继续排队等待",
            )
            lock.lock()
        }
        val waitedMs = (System.nanoTime() - start) / 1_000_000L
        if (waitedMs >= WAIT_LOG_THRESHOLD_MS) {
            AndroidLog.w(TAG, "自动化闸门：$what 排队 ${waitedMs}ms 后才拿到锁（前一个=$holder）")
        }
        val previous = holder
        holder = what
        try {
            return block()
        } finally {
            holder = previous
            lock.unlock()
        }
    }

    /** 当前是否已有自动化在跑（诊断用）。 */
    fun isBusy(): Boolean = lock.isLocked

    private const val TAG = "YuanbaoDaily"
    private const val DEFAULT_WAIT_MS = 10 * 60 * 1000L
    private const val WAIT_LOG_THRESHOLD_MS = 1_000L
}
