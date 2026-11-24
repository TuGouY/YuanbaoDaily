package me.codex.yuanbaodaily.automation

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import me.codex.yuanbaodaily.hook.Log
import rikka.shizuku.Shizuku

/**
 * **改系统设置的执行层**：直接写，或经 Shizuku 以 shell 身份写。
 *
 * 通道怎么选由 [PrivilegedPolicy] 决定（纯函数、有单测）；这里只负责执行。
 * 把"决策"和"执行"分开，是因为前者能单测、后者只能真机验，
 * 混在一起就永远说不清"到底是判断错了还是执行失败"。
 */
internal object PrivilegedAccess {

    /** 请求 Shizuku 授权的 requestCode（Activity 回调要用同一个值）。 */
    const val REQUEST_CODE = 4901

    /** 执行 `settings put` 的超时。正常几十毫秒，超时说明 Shizuku 那边卡住了。 */
    private const val SHELL_TIMEOUT_MS = 5_000L

    /** Shizuku 的包名。用它判断"装了没有"——binder 不在时不能直接问 Shizuku。 */
    private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"

    /** 一次通道探测的完整快照，供日志与界面共用。 */
    data class Status(
        val hasDirectPermission: Boolean,
        val shizukuInstalled: Boolean,
        val shizukuAlive: Boolean,
        val shizukuGranted: Boolean,
        val channel: PrivilegedPolicy.Channel,
        val explanation: String,
    ) {
        val usable: Boolean get() = channel != PrivilegedPolicy.Channel.NONE
        /** 一句话摘要，日志里只打这个。 */
        fun summary(): String =
            "channel=$channel direct=$hasDirectPermission shizuku(installed=$shizukuInstalled " +
                "alive=$shizukuAlive granted=$shizukuGranted)"
    }

    fun status(context: Context): Status {
        val hasDirect = hasDirectPermission(context)
        val installed = isShizukuInstalled(context)
        val alive = installed && runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        val granted = alive && runCatching {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        val channel = PrivilegedPolicy.decide(
            hasDirectPermission = hasDirect,
            shizukuInstalled = installed,
            shizukuAlive = alive,
            shizukuGranted = granted,
        )
        val explanation = PrivilegedPolicy.explain(
            channel = channel,
            shizukuInstalled = installed,
            shizukuAlive = alive,
            shizukuGranted = granted,
            packageName = context.packageName,
        )
        return Status(hasDirect, installed, alive, granted, channel, explanation)
    }

    fun hasDirectPermission(context: Context): Boolean = runCatching {
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    fun isShizukuInstalled(context: Context): Boolean = runCatching {
        context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
        true
    }.getOrDefault(false)

    /** Shizuku 在跑、但本应用还没授权时返回 true —— 界面据此显示"授权 Shizuku"按钮。 */
    fun canRequestShizuku(context: Context): Boolean {
        val status = status(context)
        return status.shizukuAlive && !status.shizukuGranted
    }

    /**
     * 写一个 `Settings.Secure` 项。
     *
     * @return true 表示写入命令确实成功了（Shizuku 通道会核对退出码）。
     */
    fun putSecure(context: Context, key: String, value: String): Boolean {
        val status = status(context)
        return when (status.channel) {
            PrivilegedPolicy.Channel.DIRECT -> putSecureDirect(context, key, value)
            PrivilegedPolicy.Channel.SHIZUKU -> putSecureViaShizuku(key, value)
            PrivilegedPolicy.Channel.NONE -> {
                Log.w("privileged: 没有可用通道 → 拒绝写入 $key｜${status.explanation}")
                false
            }
        }
    }

    /** 读一个 `Settings.Secure` 项（读不需要特权，但统一走这里便于日志）。 */
    fun readSecure(context: Context, key: String): String? = runCatching {
        Settings.Secure.getString(context.contentResolver, key)
    }.getOrNull()

    private fun putSecureDirect(context: Context, key: String, value: String): Boolean = runCatching {
        Settings.Secure.putString(context.contentResolver, key, value)
    }.onFailure {
        Log.e("privileged: 直接写 $key 失败（权限可能在探测之后被收回）", it)
    }.getOrDefault(false)

    /**
     * `Shizuku.newProcess(String[], String[], String)` 的反射句柄。
     *
     * ## 为什么用反射
     *
     * Shizuku 13.x 把这个方法标成了 `private static`（早期版本是 public，官方示例至今
     * 还在用 `Shizuku.newProcess(...)`）。但它**运行时一直存在**，签名也没变：
     * `(String[] cmd, String[] env, String dir) -> ShizukuRemoteProcess`（继承 `Process`）。
     *
     * 为什么不改用 `ShizukuBinderWrapper` + `ISettings` AIDL：
     * 那需要自己手写 `android.app.ISettings` 的 AIDL 并保证 transaction code 与
     * 目标系统版本一致 —— 各家 ROM 的 AIDL 顺序并不保证相同，写错了是**静默写失败**，
     * 比反射脆弱得多。而 `settings put secure` 是 shell 命令，行为在所有 ROM 上一致。
     *
     * `minifyEnabled false`（见 app/build.gradle），所以不存在方法名被混淆的风险。
     */
    private val newProcessMethod: java.lang.reflect.Method? by lazy {
        runCatching {
            Shizuku::class.java
                .getDeclaredMethod(
                    "newProcess",
                    Array<String>::class.java,
                    Array<String>::class.java,
                    String::class.java,
                )
                .apply { isAccessible = true }
        }.onFailure {
            Log.e("privileged: 反射不到 Shizuku.newProcess（Shizuku 版本变了？）", it)
        }.getOrNull()
    }

    /**
     * 通过 Shizuku 执行 `settings put secure <key> <value>`。
     *
     * 走 shell 命令而不是 binder AIDL：`settings` 命令本身会处理各 ROM 的差异，
     * 我们只要拿到"以 shell 身份运行"的能力。
     */
    private fun putSecureViaShizuku(key: String, value: String): Boolean {
        val method = newProcessMethod
        if (method == null) {
            Log.e("privileged: 没有可用的 Shizuku 进程通道，无法写入 $key")
            return false
        }
        val command = "settings put secure $key ${PrivilegedPolicy.shellQuote(value)}"
        return runCatching {
            val process = method.invoke(null, arrayOf("sh", "-c", command), null, null) as? Process
            if (process == null) {
                Log.e("privileged: Shizuku 没有返回进程对象（授权可能刚被撤销）")
                return false
            }
            // 先读干净两个流再 waitFor：否则子进程在缓冲区写满时会阻塞，双方互等成死锁。
            val stdout = process.inputStream.bufferedReader().use { it.readText() }
            val stderr = process.errorStream.bufferedReader().use { it.readText() }
            val exit = process.waitFor()
            if (exit == 0) {
                Log.w("privileged: Shizuku 写入成功 key=$key")
                true
            } else {
                Log.e(
                    "privileged: Shizuku 写入失败 key=$key exit=$exit " +
                        "stdout=${stdout.take(160)} stderr=${stderr.take(160)}",
                )
                false
            }
        }.onFailure {
            Log.e("privileged: Shizuku 执行异常 key=$key", it)
        }.getOrDefault(false)
    }
}
