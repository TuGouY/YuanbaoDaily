package me.codex.yuanbaodaily.automation

import android.os.SystemClock
import android.util.Log as AndroidLog
import java.util.concurrent.atomic.AtomicInteger

/**
 * 点击追踪 + **慢速节拍**（调试用）。
 *
 * ## 为什么需要
 *
 * 元宝的误触问题几乎都是"某一下点错了"造成的，但默认节拍下（几百毫秒一下）
 * 人眼/截屏根本跟不上，只能在事后从日志里猜是哪一步错了。
 *
 * 打开节拍后，每一次真实派发的点击都会：
 * 1. 打一条 `TAPTRACE #n action=... xy=x,y detail=...` 日志；
 * 2. 睡 [paceMs] 毫秒再继续，让外部工具（`tools/tapwatch.py`）能在两次点击之间
 *    从容截屏，从而**逐帧核对"这一下点得对不对"**。
 *
 * ## 怎么用
 *
 * ```bash
 * adb shell am broadcast -a me.codex.yuanbaodaily.action.SELF_TEST \
 *   -p com.tencent.hunyuan.app.chat \
 *   --es me.codex.yuanbaodaily.extra.SELF_TEST_MODE ask \
 *   --ei me.codex.yuanbaodaily.extra.TAP_PACE_MS 7000
 * ```
 *
 * [paceMs] 默认 **0**（生产节拍），只在显式传入时变慢 —— 这样调试能力不会拖慢日常运行。
 */
internal object TapTrace {

    private const val TAG = "YuanbaoDaily"

    /** 两次点击之间的等待毫秒数；0 = 关闭（生产默认）。 */
    @Volatile
    var paceMs: Long = 0L

    /**
     * 是否把每次点击都记进日志。**默认开启**。
     *
     * 为什么默认开：误触类问题几乎只能靠"事发当时点在哪"来定位，而一行
     * `TAPTRACE` 日志的开销可以忽略；一旦默认关闭，出问题时又得改代码重装
     * 才能复现（实测吃过这个亏：底栏那次把流程带崩的点击完全没留痕）。
     * 节拍（[paceMs]）才是有代价的部分，保持默认关闭。
     */
    @Volatile
    var enabled: Boolean = true

    private val counter = AtomicInteger(0)

    /** 记录一次点击，返回它的序号（从 1 开始）。 */
    fun record(action: String, x: Int, y: Int, detail: String = ""): Int {
        if (!enabled && paceMs <= 0L) return 0
        val index = counter.incrementAndGet()
        AndroidLog.w(TAG, "TAPTRACE #$index action=$action xy=$x,$y ${detail.trim()}".trim())
        return index
    }

    /** 记录一次**非坐标**点击（performClick / ACTION_CLICK / 回车发送）。 */
    fun recordAction(action: String, target: String): Int {
        if (!enabled && paceMs <= 0L) return 0
        val index = counter.incrementAndGet()
        AndroidLog.w(TAG, "TAPTRACE #$index action=$action target=$target")
        return index
    }

    /** 按当前节拍等待，让外部截屏跟上。 */
    fun pace() {
        val ms = paceMs
        if (ms > 0L) SystemClock.sleep(ms)
    }

    /** 重置（每轮调试开始时调用，让序号从 1 开始）。 */
    fun reset() {
        counter.set(0)
    }

    /**
     * 从自检广播的 extras 里读取节拍设置。
     *
     * @param requestedMs 调用方传入的值；<=0 表示不改变当前设置。
     */
    fun configure(requestedMs: Long) {
        if (requestedMs <= 0L) return
        paceMs = requestedMs.coerceIn(0L, 60_000L)
        enabled = true
        reset()
        AndroidLog.w(TAG, "TAPTRACE enabled pace=${paceMs}ms")
    }}
