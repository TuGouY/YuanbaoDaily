package me.codex.yuanbaodaily.automation

import android.content.Context
import android.os.Bundle
import android.os.Process
import me.codex.yuanbaodaily.BuildConfig
import me.codex.yuanbaodaily.security.IntegrityGuard

/**
 * Shared command dispatch and caller check.
 *
 * Both transports (the exported ContentProvider and the bound
 * [AutomationCommandService]) run exactly these commands, so the module keeps a
 * single, bounded command grammar no matter how the host reaches it.
 */
internal object AutomationCommandDispatcher {
    const val METHOD_AUTOMATION = "automation"
    const val METHOD_ROOT_INPUT = "root_input"

    /**
     * UI dump must run in the **accessibility service process**, not the caller.
     *
     * 真机踩坑（1.1.3 自检）：KernelSU 只授权了模块自己的 uid
     * （`me.codex.yuanbaodaily`），没有授权元宝（`com.tencent.hunyuan.app.chat`）。
     * 宿主进程里的 `su -c` 因此**没有 UI 弹窗、静默挂起**，8 秒超时后
     * `RootInputController` 返回 `exit=Int.MIN_VALUE / chars=0`，
     * 表现为 `ui dump failed` 而 `uiNodes()` 永远为空 —— 侧栏判定全部失效，
     * 循环把刚打开的侧栏又点回去。
     *
     * 无障碍服务进程的 uid 是被授权的，所以 dump 一律走这条专用方法，
     * 由服务进程执行 su。宿主进程只负责收发 Bundle。
     */
    const val METHOD_UI_DUMP = "ui_dump"

    /**
     * **无障碍服务自愈**：强制重绑本模块的无障碍服务。
     *
     * 特意**不走 `METHOD_AUTOMATION`** —— 那条路由要先拿到
     * `AutomationAccessibilityService.instance` 才肯干活（见 `perform`），
     * 而服务掉线时 `instance` 恒为 null，永远到不了 `execute`，
     * 等于"用坏掉的东西去修它自己"。这里直接由模块进程静态执行
     * [AccessibilityRepair.ensureBound]，服务死没死都能跑。
     */
    const val METHOD_A11Y_REPAIR = "a11y_repair"

    fun dispatch(context: Context?, method: String, input: Bundle): Bundle? {
        // 完整性闸门：所有自动化动作（binder / provider / 同进程直调）都汇合到这里。
        //
        // 为什么校验放在这一侧：root 版的动作由**宿主进程**发起，那里受
        // Android 11 包可见性限制，查不到本模块的签名（NameNotFoundException），
        // 所以只能由模块进程自己判断。签名被改过就一律拒绝 —— 连读屏都不提供。
        if (context != null && IntegrityGuard.blocksAutomation(context)) {
            return Bundle().apply {
                putBoolean(KEY_OK, false)
                putString(KEY_ERROR, "integrity: APK 签名不一致，已停止自动化")
            }
        }
        return when (method) {
        METHOD_AUTOMATION -> if (input.getString(KEY_ACTION) == ACTION_STATUS) {
            Bundle().apply { putBoolean(KEY_OK, AutomationAccessibilityService.isConnected()) }
        } else {
            AutomationAccessibilityService.perform(input)
        }
        METHOD_ROOT_INPUT -> RootInputController.perform(input)
        // 与 METHOD_AUTOMATION 同路由：服务进程内执行（见 METHOD_UI_DUMP 注释）。
        METHOD_UI_DUMP -> AutomationAccessibilityService.perform(input)
        METHOD_A11Y_REPAIR -> {
            val report = if (context == null) {
                "failed 模块进程拿不到 Context"
            } else {
                AccessibilityRepair.ensureBound(context, force = true)
            }
            Bundle().apply {
                putBoolean(KEY_OK, report.startsWith("alreadyOk") || report.startsWith("repaired"))
                putString(KEY_REPAIR_REPORT, report)
            }
        }
        else -> null
        }
    }

    /**
     * True when [uid] belongs to the module itself or to the injected host.
     *
     * The bound service reads the caller uid inside the binder transaction, so
     * this check is as strong as the ContentProvider's.
     */
    fun isAllowedCaller(context: Context, uid: Int): Boolean {
        if (uid == Process.myUid()) return true
        val packages = runCatching {
            context.packageManager.getPackagesForUid(uid).orEmpty()
        }.getOrDefault(emptyArray())
        return packages.any {
            it == BuildConfig.APPLICATION_ID ||
                it == BASE_MODULE_PACKAGE ||
                it == TARGET_PACKAGE
        }
    }

    const val KEY_ACTION = "action"
    const val KEY_OK = "ok"

    /** 被完整性校验拦下时的原因（写入返回 Bundle，调用方负责记日志）。 */
    const val KEY_ERROR = "error"

    /** [METHOD_A11Y_REPAIR] 的返回：人可读的自愈报告。 */
    const val KEY_REPAIR_REPORT = "repair_report"

    private const val ACTION_STATUS = "status"
    private const val BASE_MODULE_PACKAGE = "me.codex.yuanbaodaily"
    private const val TARGET_PACKAGE = "com.tencent.hunyuan.app.chat"
}
