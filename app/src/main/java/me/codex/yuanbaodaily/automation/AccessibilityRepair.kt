package me.codex.yuanbaodaily.automation

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import me.codex.yuanbaodaily.hook.Log

/**
 * **无障碍服务自愈**。
 *
 * ## 为什么需要它
 *
 * 真机实证（`evidence/trace_w7` 与本次会话的 `dumpsys accessibility`）：
 * 这台 ColorOS 在**设备重启后**、或在内存紧张时会把模块进程连根杀掉，
 * 而系统**不会**自动重新绑定第三方无障碍服务。`dumpsys accessibility` 会显示
 * ```
 *     Bound services:{Service[label=SCENE-辅助服务, ...]}          ← 只有别人
 *     Crashed services:{{me.codex.yuanbaodaily/...}}                ← 我们被标记为崩溃
 * ```
 * 用户看到的现象就是"**AI写作定位不到输入框**"（宿主侧 `bridge transport=none`、
 * 无障碍侧 `no allowed accessibility window`）。
 *
 * 以前只能让用户手动去系统设置里把开关关掉再打开。但一个普通 App **确实可以**
 * 自己完成这件事：系统在 `enabled_accessibility_services` 字符串发生变化时
 * 会重新走 bind/unbind，所以"摘掉 → 等一拍 → 装回"等价于用户手动关开开关。
 *
 * 写入这两次改动需要特权，两条来源见 [PrivilegedPolicy]：
 * - `WRITE_SECURE_SETTINGS`（只能 adb 一次性授予）；
 * - **Shizuku**（无线调试配对一次，不需要电脑、不需要 root）。
 *
 * 本类不关心权限从哪来，只通过 [PrivilegedAccess] 执行 ——
 * 这样"能不能自愈"和"怎么自愈"分开，真机上出问题时能一眼分清是哪一层的事。
 *
 * 注意：本 App 的无障碍服务与 `DailyAlarmReceiver` **同进程**，
 * 所以闹钟把进程拉起来之后，就能在同进程内完成重新绑定并立刻观察到
 * [AccessibilityAccess.isConnected] 变 true。
 */
internal object AccessibilityRepair {
    /** 摘掉之后、装回之前要等的时间（让系统先真正 unbind 掉旧绑定）。 */
    private const val UNBIND_SETTLE_MS = 900L

    /** 装回之后，等待服务回调 `onServiceConnected` 的上限。 */
    private const val REBIND_WAIT_MS = 6_000L

    /** 轮询间隔。 */
    private const val POLL_MS = 150L

    /** 本模块的组件名，形如 `me.codex.yuanbaodaily/me.codex.yuanbaodaily.automation.AutomationAccessibilityService`。 */
    fun component(context: Context): String =
        "${context.packageName}/${AutomationAccessibilityService::class.java.name}"

    fun readCurrent(context: Context): String? = PrivilegedAccess.readSecure(
        context,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
    )

    /** 该服务是否已经在系统的"要启用"列表里（与是否真的绑定成功是两件事）。 */
    fun isListed(context: Context): Boolean =
        AccessibilityServiceList.contains(readCurrent(context), component(context))

    /**
     * 检查并按需修复。"[alreadyOk]" / "[repaired]" / "[failed]" 开头，后接诊断细节。
     *
     * @param force 为 true 时即使 [AccessibilityAccess.isConnected] 为真也强制重绑一次
     *              （用于 `mode=repair` 手动验证整条自愈链路真的能跑通）。
     * @param graceMs 动手之前先**静观其变**多久：只看"连接有没有自己好起来"，不改任何设置。
     *                开机那阵子系统自己正在绑服务，此时去改设置会跟它互相打断，
     *                所以开机路径给 8 秒宽限。掉线时这 8 秒是白等的，但换的是"不打架"。
     */
    fun ensureBound(context: Context, force: Boolean = false, graceMs: Long = 0L): String {
        val component = component(context)
        if (!force && AccessibilityAccess.isConnected()) {
            return "alreadyOk componentBound=true listed=${isListed(context)}"
        }
        if (!force && graceMs > 0 && waitForConnection(graceMs)) {
            return "alreadyOk 宽限期内自行恢复 componentBound=true listed=${isListed(context)}"
        }
        if (!force && AccessibilityAccess.isConnected()) {
            return "alreadyOk componentBound=true listed=${isListed(context)}"
        }

        val before = readCurrent(context)
        if (before == null) {
            return "failed 读不到 enabled_accessibility_services（ContentResolver 被拒？）"
        }

        // 先确认有没有可用的写入通道。
        //
        // 老实现是"拿当前值空写一次，看抛不抛 SecurityException"来探权限 ——
        // 那次空写本身就是一次真实写入，而且抛异常的栈对用户毫无意义。
        // 现在改成显式判定（见 [PrivilegedPolicy]），并且**明确告诉用户走哪条路**：
        // 有电脑就 adb 授权，没电脑就装 Shizuku 用无线调试配对。
        val status = PrivilegedAccess.status(context)
        if (!status.usable) {
            Log.w("a11y repair: ${status.summary()}")
            return "failed 无法自愈：${status.explanation}"
        }
        Log.d("a11y repair: 写入通道 ${status.summary()}")

        val wasListed = AccessibilityServiceList.contains(before, component)
        Log.w("a11y repair: 开始强制重绑 component=$component wasListed=$wasListed force=$force")

        // 第一步：摘掉（字符串变化才会让系统重新 bind/unbind）。
        if (wasListed) {
            val removed = AccessibilityServiceList.without(before, component)
            if (!write(context, removed)) return "failed 摘除步骤写入失败"
            sleep(UNBIND_SETTLE_MS)
        }

        // 第二步：装回。
        val target = AccessibilityServiceList.withComponent(readCurrent(context), component)
        if (!write(context, target)) return "failed 装回步骤写入失败"

        val deadline = SystemClock.uptimeMillis() + REBIND_WAIT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            if (AccessibilityAccess.isConnected()) {
                val report = "repaired bound=true listed=${isListed(context)} " +
                    "等待=${REBIND_WAIT_MS - (deadline - SystemClock.uptimeMillis())}ms"
                Log.w("a11y repair: 成功 $report")
                return report
            }
            sleep(POLL_MS)
        }

        val report = "failed 已写回 enabled_accessibility_services，但 ${REBIND_WAIT_MS}ms 内服务仍未回调 " +
            "onServiceConnected（listed=${isListed(context)}）"
        Log.w("a11y repair: $report")
        return report
    }

    private fun write(context: Context, value: String): Boolean =
        PrivilegedAccess.putSecure(
            context,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            value,
        )

    /** 只观察不修改：在 [timeoutMs] 内等服务自己连上。 */
    private fun waitForConnection(timeoutMs: Long): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (AccessibilityAccess.isConnected()) return true
            sleep(POLL_MS)
        }
        return AccessibilityAccess.isConnected()
    }

    private fun sleep(ms: Long) {
        runCatching { Thread.sleep(ms) }
    }
}

/**
 * [AutomationAccessibilityService.Companion.isConnected] 的转发层。
 *
 * 单独包一层是为了让 [AccessibilityRepair] 在纯 JVM 单测里也能被引用到
 * （直接引用 `AutomationAccessibilityService` 会拖进 `AccessibilityService` 这个 android 类）。
 */
internal object AccessibilityAccess {
    fun isConnected(): Boolean = runCatching {
        AutomationAccessibilityService.isConnected()
    }.getOrDefault(false)
}
