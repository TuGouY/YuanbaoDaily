package me.codex.yuanbaodaily.security

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import android.os.Debug
import me.codex.yuanbaodaily.BuildConfig
import me.codex.yuanbaodaily.hook.Log
import java.security.MessageDigest

/**
 * 完整性校验：确认"跑起来的这份代码，是不是我们签出去的那一个 APK"。
 *
 * ## 为什么需要
 *
 * 反编译之后最常见的下一步是**改字节码再重新签名**（去弹窗、去校验、塞广告/后门）。
 * 重签名一定会换证书，所以在运行期比对自己 APK 的签名证书指纹，
 * 就能把这类改包挡在自动化之外 —— 它的成本从"改一行 smali"变成
 * "还要把校验点找出来并 patch 掉"，再叠上 R8 改名，定位成本明显上升。
 *
 * ## 期望值从哪来
 *
 * 不是写死在源码里，而是 **Gradle 构建时从签名用的 keystore 现算**
 * （`SIGNING_CERT_SHA256`，见 `app/build.gradle`）。
 * 这样换台机器构建也不会莫名其妙自我否定；读不到 keystore 时该字段为空，
 * 校验退化为"不判断"（[Verdict.UNVERIFIABLE]），不会把正常构建搞挂。
 *
 * ## 能挡住什么、挡不住什么
 *
 * - 挡得住：用别人证书重签的改包（[Verdict.TAMPERED]）、release 包被调试器附着
 *   （[Verdict.DEBUGGER]）。
 * - 挡不住：拿到源码自己构建、直接 patch 掉本类、或者有 root 权限做内存 dump。
 *   客户端校验本质是"提高成本"，不是"不可破解"——这一点写清楚，免得造成错觉。
 *
 * ## 调用点
 *
 * 只拦**自动化执行**，不拦界面本身：界面还要能打开，用户才能看到"签名不一致"的提示。
 * 见 [me.codex.yuanbaodaily.hook.YuanbaoRuntime]、
 * [me.codex.yuanbaodaily.task.StandaloneRuntime] 与
 * [me.codex.yuanbaodaily.task.DailyTaskRunner]。
 */
internal object IntegrityGuard {

    enum class Verdict {
        /** 签名与构建时记录的一致，且没有调试器附着。 */
        OK,

        /** APK 被重新签名过（改包）。 */
        TAMPERED,

        /** release 包上挂着调试器，通常是动态分析的现场。 */
        DEBUGGER,

        /**
         * 代码注入在宿主进程里运行（root 版）。
         *
         * 那个进程的 UID 是元宝的，受 Android 11 包可见性限制，
         * `getPackageInfo(模块包名)` 会抛 `NameNotFoundException` —— 不是"被改包"，
         * 而是"根本看不到"。这种情况交给模块进程（无障碍服务）判断：
         * 见 [me.codex.yuanbaodaily.automation.AutomationCommandDispatcher.dispatch]。
         */
        IN_HOST,

        /** 构建时没拿到证书指纹（换机器构建等），无从判断，按放行处理。 */
        UNVERIFIABLE,
    }

    /**
     * 结果缓存。
     *
     * 这项检查会被每个调度轮次问到，但签名在进程生命周期内不会变；
     * 反复调 `PackageManager` 只是白花时间。用 `@Volatile` 是为了让
     * 无障碍服务线程与主线程看到同一个结果。
     */
    @Volatile
    private var cached: Verdict? = null

    /** 只判断、不打印，给界面用。 */
    fun verdict(context: Context): Verdict {
        cached?.let { return it }
        val result = compute(context)
        cached = result
        return result
    }

    /**
     * 自动化入口用这个：命中风险时返回 true，并且**每次**都留一条日志。
     *
     * 不复用缓存里的日志是因为"被拦住"这件事必须在 logcat 里看得到，
     * 否则用户只会觉得"今天又没跑"，排查时无从下手。
     */
    fun blocksAutomation(context: Context): Boolean = when (val v = verdict(context)) {
        Verdict.TAMPERED -> {
            Log.e("integrity: 签名校验失败，拒绝执行自动化（APK 被重新签名过？）")
            true
        }
        Verdict.DEBUGGER -> {
            Log.e("integrity: 检测到调试器附着，拒绝执行自动化")
            true
        }
        Verdict.OK, Verdict.UNVERIFIABLE, Verdict.IN_HOST -> false
    }

    private fun compute(context: Context): Verdict {
        // debug 包本来就是拿来调试的，不能自己把自己拦死。
        if (!BuildConfig.DEBUG &&
            (Debug.isDebuggerConnected() || Debug.waitingForDebugger())
        ) {
            return Verdict.DEBUGGER
        }
        // root 版是注入到元宝进程里跑的：那里 `context.packageName` 是元宝，
        // 查自己模块的签名会被包可见性挡成 NameNotFoundException。
        // 这一侧不做判断（也避免每次启动刷一条误导性的异常栈），
        // 真正的校验由模块进程在命令入口处完成 —— 任何动作都要经过那里。
        if (context.packageName != BuildConfig.APPLICATION_ID) {
            return Verdict.IN_HOST
        }
        val expected = BuildConfig.SIGNING_CERT_SHA256
        if (expected.isBlank()) {
            Log.w("integrity: 构建时未记录签名指纹，跳过校验（换机器构建会出现这种情况）")
            return Verdict.UNVERIFIABLE
        }
        val actual = runCatching { currentSignerDigests(context) }
            .onFailure { Log.e("integrity: 读取签名信息失败", it) }
            .getOrNull()
            ?: return Verdict.UNVERIFIABLE
        return if (actual.any { it.equals(expected, ignoreCase = true) }) {
            Verdict.OK
        } else {
            Verdict.TAMPERED
        }
    }

    /**
     * 当前安装包的签名证书 SHA-256（小写十六进制，不带冒号）。
     *
     * 用 `BuildConfig.APPLICATION_ID` 而不是 `context.packageName`：
     * root 版里这段代码是**注入到元宝进程**里跑的，那里的 `context.packageName`
     * 是 `com.tencent.hunyuan.app.chat`，直接拿来查签名会查到宿主身上。
     */
    private fun currentSignerDigests(context: Context): List<String> {
        val pm = context.packageManager
        val pkg = BuildConfig.APPLICATION_ID
        val signatures: Array<Signature> = if (Build.VERSION.SDK_INT >= 28) {
            val info = pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
            // 只认"当前签名者"，不认 signingCertificateHistory ——
            // 历史证书是签在 APK 里的，攻击者重签时一并伪造，用它判断等于放水。
            info.signingInfo?.apkContentsSigners ?: return emptyList()
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES).signatures ?: return emptyList()
        }
        val digest = MessageDigest.getInstance("SHA-256")
        return signatures.map { signature ->
            digest.reset()
            digest.digest(signature.toByteArray()).joinToString("") { byte ->
                "%02x".format(byte)
            }
        }
    }
}
