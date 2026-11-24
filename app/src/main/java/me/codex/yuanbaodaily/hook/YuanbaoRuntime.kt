package me.codex.yuanbaodaily.hook

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Parcelable
import android.webkit.WebView
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import me.codex.yuanbaodaily.automation.AutomationBridge
import me.codex.yuanbaodaily.automation.AutomationCommandDispatcher
import me.codex.yuanbaodaily.automation.AutomationGate
import me.codex.yuanbaodaily.automation.AutomationHostLink
import me.codex.yuanbaodaily.automation.AutomationScripts
import me.codex.yuanbaodaily.automation.TapTrace
import me.codex.yuanbaodaily.config.RunRequest
import me.codex.yuanbaodaily.config.Target
import me.codex.yuanbaodaily.task.DailyTaskRunner
import me.codex.yuanbaodaily.task.YuanbaoTaskAdapter
import java.lang.reflect.Modifier

internal object YuanbaoRuntime {
    @Volatile
    private var started = false

    fun start(application: Application, classLoader: ClassLoader) {
        if (started) return
        // 完整性闸门：这个 APK 被别人重签过、或者 release 包上挂着调试器时，
        // 整个运行时都不启动 —— 注入本身不造成破坏，但绝不再提供"替人做任务"的能力。
        if (me.codex.yuanbaodaily.security.IntegrityGuard.blocksAutomation(application)) {
            started = true
            return
        }
        val versionCode = runCatching {
            Target.installedVersion(application)
        }.getOrNull()
        if (versionCode != Target.VERSION_CODE) {
            Log.d("host version $versionCode differs from ${Target.VERSION_CODE}; continuing in compatibility mode")
        }
        started = true
        runCatching {
            WelfareEntryBridge.install(application, classLoader)
            val adapter = YuanbaoTaskAdapter(
                application,
                WelfareEntryBridge::open,
                WelfareEntryBridge::openCached,
            )
            val runner = DailyTaskRunner(application, adapter)
            application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
                override fun onActivityResumed(activity: Activity) {
                    adapter.setResumedActivity(activity)
                    runner.onActivityResumed(activity)
                }

                override fun onActivityPaused(activity: Activity) {
                    adapter.clearResumedActivity(activity)
                    runner.onActivityPaused(activity)
                }

                override fun onActivityCreated(activity: Activity, state: Bundle?) {
                    runner.acceptIntent(activity.intent, "activity_created")
                }
                override fun onActivityStarted(activity: Activity) = Unit
                override fun onActivityStopped(activity: Activity) = Unit
                override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
                override fun onActivityDestroyed(activity: Activity) = Unit
            })
            registerRunReceiver(application, runner)
            registerModuleLinkReceiver(application)
            registerSelfTestReceiver(application, runner)
            hookWebViewSniffer()
            hookActivityRequests(runner)
            if (Target.ENABLE_MODEL_HOOKS) {
                hookKnownTaskViewModels(classLoader)
            } else {
                Log.d("model hooks disabled for startup-safe mode")
            }
            runner.start()
            Log.d("runtime started for host version ${versionCode ?: "unknown"} (target ${Target.VERSION_CODE})")
        }.onFailure { Log.e("runtime initialization failed; host left untouched", it) }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun registerRunReceiver(application: Application, runner: DailyTaskRunner) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                runner.acceptIntent(intent, "broadcast")
            }
        }
        val filter = IntentFilter(RunRequest.ACTION_RUN_NOW)
        runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                application.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                application.registerReceiver(receiver, filter)
            }
            Log.d("run request receiver registered")
        }.onFailure { Log.e("run request receiver registration failed", it) }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun registerModuleLinkReceiver(application: Application) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val payload = intent.getBundleExtra(AutomationHostLink.EXTRA_PAYLOAD)
                val binder = payload
                    ?.let { runCatching { it.getBinder(AutomationHostLink.KEY_BINDER) }.getOrNull() }
                    ?: readBinderExtra(intent)
                if (binder == null) {
                    Log.w("module link broadcast without a binder from=${intent.`package`}")
                    return
                }
                // The module package cannot be looked up from here (package
                // visibility), so verify the sender by uid where the platform
                // reports it.
                if (Build.VERSION.SDK_INT >= 34) {
                    val sender = runCatching { sentFromUid }.getOrDefault(-1)
                    if (sender > 0 && !AutomationCommandDispatcher.isAllowedCaller(context, sender)) {
                        Log.w("module link rejected senderUid=$sender")
                        return
                    }
                }
                // Repeat broadcasts are expected (the keeper pump); only a new
                // binder is worth a line.
                AutomationBridge.acceptModuleLink(binder)
            }
        }
        val filter = IntentFilter(AutomationHostLink.ACTION_MODULE_ONLINE)
        runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                application.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                application.registerReceiver(receiver, filter)
            }
            Log.d("module link receiver registered")
        }.onFailure { Log.e("module link receiver registration failed", it) }
    }

    private fun readBinderExtra(intent: Intent): IBinder? = runCatching {
        val parcelable: Parcelable? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(AutomationHostLink.KEY_BINDER, Parcelable::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra<Parcelable>(AutomationHostLink.KEY_BINDER)
        }
        parcelable as? IBinder
    }.getOrNull()

    /**
     * Diagnostic channel for verifying the input path without a task round.
     *
     * `adb shell am broadcast -a me.codex.yuanbaodaily.action.SELF_TEST \
     *   -p com.tencent.hunyuan.app.chat --es me.codex.yuanbaodaily.extra.SELF_TEST_TEXT "自检"`
     */
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun registerSelfTestReceiver(application: Application, runner: DailyTaskRunner) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val text = intent.getStringExtra(RunRequest.EXTRA_SELF_TEST_TEXT)
                    ?.takeIf(String::isNotBlank)
                    ?: DEFAULT_SELF_TEST_TEXT
                val mode = intent.getStringExtra(RunRequest.EXTRA_SELF_TEST_MODE)
                    ?.takeIf(String::isNotBlank)
                    ?: "typing"
                // 调试节拍：让每一次点击之间停 6~8 秒，外部工具据此逐次截屏核对。
                // 必须**同步给模块进程**的无障碍服务，否则无障碍层点击不受节拍、也不留追踪。
                val pace = intent.getIntExtra(RunRequest.EXTRA_TAP_PACE_MS, 0).toLong()
                val appContext = context.applicationContext
                Thread({
                    // 节拍同步必须在后台线程阻塞做完**再**开跑：主线程上 binder 传输
                    // 被显式禁用，而自检 flow 一开始就要点击，慢半拍就漏掉前几下。
                    if (pace > 0L) {
                        TapTrace.configure(pace)
                        AutomationBridge.syncTapPace(appContext, pace)
                    }
                    // **自检整轮独占**（见 [AutomationGate]）：自检线程与每日任务线程
                    // 都会写元宝那颗 Compose 输入框，并行时会把两段提示词拼成一条发送、
                    // 并让其中一条白跑（`evidence/trace_d7_ask` 实证）。
                    AutomationGate.exclusive("self-test:$mode") {
                        if (mode != "typing") {
                            val summary = runner.selfTest(mode)
                            Log.w("self test mode=$mode summary=$summary")
                            return@exclusive
                        }
                        val screen = AutomationBridge.readText(appContext)
                        Log.w(
                            "self test read chars=${screen.length} " +
                                "sample=${screen.replace('\n', '|').take(160)}",
                        )
                        val typed = AutomationBridge.setText(appContext, text)
                        Log.w("self test set_text=$typed text=${text.take(40)}")
                        val sent = AutomationBridge.clickIds(appContext, SEND_IDS)
                        Log.w("self test send_by_id=$sent")
                        if (!sent) {
                            val enter = AutomationBridge.rootEnter(appContext)
                            Log.w("self test root_enter=$enter")
                        }
                    }
                }, "YuanbaoDailySelfTest").apply { isDaemon = true }.start()
            }
        }
        val filter = IntentFilter(RunRequest.ACTION_SELF_TEST)
        runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                application.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                application.registerReceiver(receiver, filter)
            }
            Log.d("self test receiver registered")
        }.onFailure { Log.e("self test receiver registration failed", it) }
    }

    /**
     * 在每个 WebView 页面开始加载时注入接口抓取钩子。
     *
     * 福利中心是客户端渲染（`__NEXT_DATA__` 是空壳），任务计数由页面随后 XHR 拉取；
     * 注入必须发生在页面脚本之前，否则钩子会被新的 JS 上下文丢掉。这里 hook 宿主自己
     * 设置的 `WebViewClient`（而不是基类），这样即使它重写了 `onPageStarted` 也能命中。
     */
    private fun hookWebViewSniffer() {
        runCatching {
            XposedBridge.hookAllMethods(
                WebView::class.java,
                "setWebViewClient",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val client = param.args.firstOrNull() ?: return
                        runCatching {
                            XposedBridge.hookAllMethods(
                                client.javaClass,
                                "onPageStarted",
                                object : XC_MethodHook() {
                                    override fun afterHookedMethod(inner: MethodHookParam) {
                                        val view = inner.args.firstOrNull() as? WebView ?: return
                                        runCatching {
                                            view.evaluateJavascript(AutomationScripts.SNIFFER_INSTALL, null)
                                        }
                                    }
                                },
                            )
                        }.onFailure { Log.d("webview client ${client.javaClass.simpleName} not hookable") }
                        Log.d("webview sniffer attached to ${client.javaClass.simpleName}")
                    }
                },
            )
            Log.d("webview sniffer hook installed")
        }.onFailure { Log.e("webview sniffer hook failed", it) }
    }

    private fun hookActivityRequests(runner: DailyTaskRunner) {
        runCatching {
            XposedHelpers.findAndHookMethod(
                Activity::class.java,
                "onCreate",
                Bundle::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val activity = param.thisObject as? Activity ?: return
                        runner.acceptIntent(activity.intent, "activity_on_create")
                    }
                },
            )
            XposedHelpers.findAndHookMethod(
                Activity::class.java,
                "onNewIntent",
                Intent::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val intent = param.args.firstOrNull() as? Intent ?: return
                        runner.acceptIntent(intent, "activity_new_intent")
                    }
                },
            )
            Log.d("activity request hooks installed")
        }.onFailure { Log.e("activity request hooks failed", it) }
    }

    private fun hookKnownTaskViewModels(classLoader: ClassLoader) {
        // The generated DTMP classes use short method names (f/g/h/i), so filtering
        // by a name containing "Task" misses the actual task list refresh and
        // execution callbacks. Observe every public, non-synthetic method instead.
        for (name in Target.TASK_MODEL_CLASSES) {
            runCatching {
                val clazz = classLoader.loadClass(name)
                for (method in clazz.declaredMethods) {
                    if (!Modifier.isPublic(method.modifiers) || method.isSynthetic || method.name == "toString") continue
                    XposedHelpers.findAndHookMethod(clazz, method.name, *method.parameterTypes, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            YuanbaoTaskObserver.record(param.thisObject, "${clazz.simpleName}.${method.name}")
                            YuanbaoTaskObserver.record(param.result, "${clazz.simpleName}.${method.name} result")
                            param.args.forEach { YuanbaoTaskObserver.record(it, "${clazz.simpleName}.${method.name} arg") }
                            Log.d("observed ${clazz.simpleName}.${method.name}")
                        }
                    })
                }
                for (constructor in clazz.declaredConstructors) {
                    if (!Modifier.isPublic(constructor.modifiers) || constructor.isSynthetic) continue
                    XposedHelpers.findAndHookConstructor(clazz, *constructor.parameterTypes, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            YuanbaoTaskObserver.record(param.thisObject, "${clazz.simpleName}.<init>")
                        }
                    })
                }
            }.onFailure { Log.d("task model not hookable: $name (${it.javaClass.simpleName})") }
        }
    }

    private const val DEFAULT_SELF_TEST_TEXT = "自检：你好 123"
    private val SEND_IDS = listOf(
        "ivConversationInputSend",
        "flSend",
        "fl_send_stop",
        "iv_send",
        "btn_send",
    )
}
