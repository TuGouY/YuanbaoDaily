package me.codex.yuanbaodaily.task

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import me.codex.yuanbaodaily.automation.AutomationBridge
import me.codex.yuanbaodaily.automation.AutomationGate
import me.codex.yuanbaodaily.automation.TapTrace
import me.codex.yuanbaodaily.config.RunRequest
import me.codex.yuanbaodaily.hook.Log

/**
 * **免 root 版的运行时**：把调度器直接跑在模块自己的进程里。
 *
 * ## 为什么要另起一套
 *
 * root 版是 LSPosed 模块，代码被注入到元宝进程（见
 * [me.codex.yuanbaodaily.hook.YuanbaoRuntime]），所以它能直接拿到宿主 Activity、
 * View 树、WebView 的 innerText。而 **LSPosed 本身需要 root** ——
 * 没有 root 的手机上这条路根本不存在。
 *
 * 免 root 版因此换了个形态：独立无障碍 App。
 * - 调度器（[DailyTaskRunner]）跑在自己进程；
 * - 所有界面操作走 [AutomationBridge] → AccessibilityService（读屏 + 手势）；
 * - 启动元宝用普通 `startActivity`（清单里对元宝声明了 `<queries>`，所以包可见）。
 *
 * 代价明确：拿不到宿主 View 树和 WebView 内文，`resumedActivity` 恒为 null，
 * 进度只能从无障碍文本里推断（`readProgress` 的正则路径）。
 *
 * ## 和 root 版的关系
 *
 * 两者**不会同时生效**：本类在 `BuildConfig.STANDALONE` 为 false（root 版）时
 * 所有入口直接返回 null，root 版的行为一个字都没变。
 */
internal object StandaloneRuntime {
    @Volatile
    private var runner: DailyTaskRunner? = null

    @Volatile
    private var receiversRegistered = false

    /**
     * 确保运行时已启动，返回调度器；非免 root 版返回 null。
     *
     * 幂等，可以从无障碍服务、闹钟、界面三处随便调。
     */
    fun ensureStarted(context: Context): DailyTaskRunner? {
        if (!me.codex.yuanbaodaily.BuildConfig.STANDALONE) return null
        runner?.let { return it }
        // 完整性闸门（免 root 版的启动点）：重签过的包不给调度器，也不起保活服务。
        // 返回 null 让调用方按"运行时不可用"处理，避免在半信任的进程里继续跑自动化。
        if (me.codex.yuanbaodaily.security.IntegrityGuard.blocksAutomation(context.applicationContext)) {
            return null
        }
        synchronized(this) {
            runner?.let { return it }
            val app = context.applicationContext
            val adapter = YuanbaoTaskAdapter(app)
            val created = DailyTaskRunner(app, adapter)
            registerReceivers(app, created)
            created.start()
            runner = created
            // 前台服务保活。放在**运行时启动点**而不是各个入口分别调用：
            // 无障碍服务连接、闹钟、打开界面三处最终都会走到这里，接一次就全覆盖。
            // root 版由 KeepAliveService.ensureStarted 内部的 STANDALONE 判断挡掉。
            val keepAlive = KeepAliveService.ensureStarted(app)
            Log.w(
                "standalone runtime started（免root版：独立无障碍 App，不注入任何进程）" +
                    " keepAlive=$keepAlive",
            )
            return created
        }
    }

    /**
     * 请求跑一轮任务。
     *
     * 复用和 root 版完全相同的入口：构造一个 `ACTION_RUN_NOW` Intent 交给
     * [DailyTaskRunner.acceptIntent]。这样两条运行形态走的是**同一段调度与执行代码**，
     * 不会出现"免 root 版少了某个分支"这种分叉 bug。
     */
    fun requestRun(context: Context, source: String): Boolean {
        val active = ensureStarted(context) ?: return false
        val requestId = System.currentTimeMillis()
        val intent = Intent(RunRequest.ACTION_RUN_NOW)
            .putExtra(RunRequest.EXTRA_REQUEST_ID, requestId)
            .putExtra(RunRequest.EXTRA_ENABLED, true)
            .putExtra(RunRequest.EXTRA_SOURCE, "standalone:$source")
        return runCatching {
            active.acceptIntent(intent, "standalone:$source")
            Log.d("standalone run requested source=$source requestId=$requestId")
            true
        }.onFailure { Log.e("standalone run request failed source=$source", it) }
            .getOrDefault(false)
    }

    /** 当前活动调度器（诊断用）。 */
    fun activeRunner(): DailyTaskRunner? = runner

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun registerReceivers(app: Context, active: DailyTaskRunner) {
        if (receiversRegistered) return
        receiversRegistered = true
        register(app, IntentFilter(RunRequest.ACTION_RUN_NOW)) { intent ->
            active.acceptIntent(intent, "broadcast")
        }
        register(app, IntentFilter(RunRequest.ACTION_SELF_TEST)) { intent ->
            handleSelfTest(app, active, intent)
        }
        Log.d("standalone receivers registered")
    }

    private fun register(app: Context, filter: IntentFilter, handler: (Intent) -> Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = handler(intent)
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                app.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                app.registerReceiver(receiver, filter)
            }
        }.onFailure { Log.e("standalone receiver registration failed filter=${filter}", it) }
    }

    /**
     * 自检入口，和 root 版保持同一套 action / extra 约定，
     * 这样 `tools/tapwatch.py` 与 `tools/regress.sh` 不用改就能对免 root 版使用
     * （只是要把广播发给本应用自身的包名）。
     */
    private fun handleSelfTest(app: Context, active: DailyTaskRunner, intent: Intent) {
        val mode = intent.getStringExtra(RunRequest.EXTRA_SELF_TEST_MODE)
            ?.takeIf(String::isNotBlank)
            ?: "health"
        val pace = intent.getIntExtra(RunRequest.EXTRA_TAP_PACE_MS, 0).toLong()
        Thread({
            if (pace > 0L) {
                TapTrace.configure(pace)
                AutomationBridge.syncTapPace(app, pace)
            }
            AutomationGate.exclusive("standalone-self-test:$mode") {
                val summary = active.selfTest(mode)
                Log.w("standalone self test mode=$mode summary=$summary")
            }
        }, "YuanbaoDailyStandaloneSelfTest").apply { isDaemon = true }.start()
    }
}
