package me.codex.yuanbaodaily.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * [AutomationGate] 的回归测试。
 *
 * 对应真机缺陷（`evidence/trace_d7_ask`）：自检线程 `YuanbaoDailySelfTest` 与
 * 每日任务线程 `YuanbaoDaily` 同时调用 `sendPrompt`，两路都往同一颗 Compose
 * 输入框里写，`ACTION_SET_TEXT`（整段替换）与 `ACTION_PASTE`（插入）交错，
 * 结果是一条消息里被拼进两段提示词、另一条任务静默白跑。
 *
 * 这类缺陷在真机上表现为"偶发"，靠截图很难稳定复现，所以在这里用
 * **并发计数**把它锁死：只要有两路同时进入闸门内部，`maxConcurrent` 就会 > 1。
 */
class AutomationGateTest {

    @Test
    fun exclusiveRunsTheBlockAndReturnsItsValue() {
        val value = AutomationGate.exclusive("test") { 42 }
        assertEquals(42, value)
        assertFalse("闸门应在块结束后释放", AutomationGate.isBusy())
    }

    @Test
    fun exclusiveIsReentrantOnTheSameThread() {
        // 顶层入口加锁后，内部函数再加一次锁不能自锁死。
        // 真机对应：`self-test:ask` 持锁 → 内部的 `sendPrompt` 也会请求闸门。
        var inner = false
        val outer = AutomationGate.exclusive("outer") {
            inner = AutomationGate.exclusive("inner") { true }
            "done"
        }
        assertEquals("done", outer)
        assertTrue("同一线程重复进入应当直接放行", inner)
        assertFalse(AutomationGate.isBusy())
    }

    @Test
    fun exclusiveIsReleasedEvenWhenTheBlockThrows() {
        runCatching { AutomationGate.exclusive<String>("boom") { error("boom") } }
        assertFalse("抛出异常也必须解锁，否则后续自动化会永久卡住", AutomationGate.isBusy())
        // 能再次拿到锁，说明锁确实被释放了。
        assertEquals("ok", AutomationGate.exclusive("after") { "ok" })
    }

    @Test
    fun twoThreadsNeverRunInsideTheGateAtTheSameTime() {
        val threads = 8
        val rounds = 6
        val inside = AtomicInteger(0)
        val maxConcurrent = AtomicInteger(0)
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)

        repeat(threads) { index ->
            Thread({
                start.await()
                repeat(rounds) {
                    AutomationGate.exclusive("worker-$index") {
                        val now = inside.incrementAndGet()
                        maxConcurrent.updateAndGet { maxOf(it, now) }
                        // 在闸门内部停一下：没有互斥时这段重叠必然发生。
                        Thread.sleep(2L)
                        inside.decrementAndGet()
                    }
                }
                done.countDown()
            }, "gate-worker-$index").apply { isDaemon = true }.start()
        }

        start.countDown()
        assertTrue("并发压测未在 10s 内跑完", done.await(10, TimeUnit.SECONDS))
        assertEquals("闸门内部同时只允许一路自动化，实测最大并发", 1, maxConcurrent.get())
        assertFalse(AutomationGate.isBusy())
    }

    @Test
    fun outerSectionKeepsTheGateClosedAfterANestedSectionExits() {
        // 内层退出**不能**顺手把外层的锁放掉（真机对应：自检整轮持锁期间，
        // 内部 `sendPrompt` 进出一次后，任务线程仍不该挤进来）。
        val probeEntered = java.util.concurrent.atomic.AtomicBoolean(false)
        val probeStarted = CountDownLatch(1)

        AutomationGate.exclusive("outer") {
            AutomationGate.exclusive("inner") { Unit }
            Thread({
                probeStarted.countDown()
                AutomationGate.exclusive("probe") { probeEntered.set(true) }
            }, "gate-probe").apply { isDaemon = true }.start()

            assertTrue("探针线程应已启动", probeStarted.await(2, TimeUnit.SECONDS))
            // 给探针线程足够时间去尝试（拿不到锁就会被挡住）。
            Thread.sleep(120L)
            assertFalse("外层还在跑，探针不该进入闸门", probeEntered.get())
        }
        // 外层退出后探针应当马上能进来。
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (!probeEntered.get() && System.nanoTime() < deadline) Thread.sleep(10L)
        assertTrue("外层退出后应释放闸门", probeEntered.get())
    }
}
