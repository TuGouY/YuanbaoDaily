package me.codex.yuanbaodaily.automation

/**
 * **特权通道选择**：要改 `Settings.Secure`（无障碍自愈的唯一手段），
 * 有哪条路可走、该走哪条。
 *
 * ## 为什么需要这一层
 *
 * 写 `enabled_accessibility_services` 需要 `WRITE_SECURE_SETTINGS`，
 * 它是 `signature|privileged` 权限，**普通 App 永远申请不到**，只能靠
 * `adb shell pm grant` 一次性授予。而免 root 版的用户（没有 root、也没有电脑）
 * 恰恰给不了这个 —— 于是无障碍一掉线就彻底停摆。
 *
 * Shizuku 是目前唯一"不需要 root、也不需要电脑"的替代：
 * 用户用无线调试配对一次，Shizuku 就能以 shell 身份代跑 `settings put secure`，
 * 效果等价于那条 adb 命令。
 *
 * ## 为什么单独抽成纯函数
 *
 * 决策逻辑（有没有权限 / Shizuku 三态）与 Android API 完全解耦，
 * 可以在 JVM 单测里把所有组合跑一遍 —— 这层判断错了的后果是
 * "明明能用却报不可用"或"该用 Shizuku 时去碰一次 SecurityException"，
 * 两种都很难从真机日志里看出来。
 */
internal object PrivilegedPolicy {

    /** 写系统设置的通道。 */
    enum class Channel {
        /** 直接 `ContentResolver.putString`（需要有 WRITE_SECURE_SETTINGS）。 */
        DIRECT,

        /** 通过 Shizuku 以 shell 身份执行 `settings put secure`。 */
        SHIZUKU,

        /** 两条路都不通。 */
        NONE,
    }

    /**
     * 选通道。
     *
     * 顺序上 **DIRECT 优先**：它是一次 binder 调用，没有任何进程启动开销；
     * Shizuku 每次都要 fork 一个 `sh`，慢一个量级。所以只在没有直接权限时才看 Shizuku。
     *
     * Shizuku 必须**同时**满足三条才算可用：
     * - `installed`：装了 Shizuku（`pingBinder` 之前要先确认包在）；
     * - `alive`：Shizuku 服务在跑（用户可能装了但没启动）；
     * - `granted`：用户对本应用点了"允许"。
     *
     * 少任何一条，调用 `Shizuku.newProcess` 都会抛异常，所以必须提前判掉。
     */
    fun decide(
        hasDirectPermission: Boolean,
        shizukuInstalled: Boolean,
        shizukuAlive: Boolean,
        shizukuGranted: Boolean,
    ): Channel = when {
        hasDirectPermission -> Channel.DIRECT
        shizukuInstalled && shizukuAlive && shizukuGranted -> Channel.SHIZUKU
        else -> Channel.NONE
    }

    /**
     * 通道不通时，给出一句**可执行**的说明。
     *
     * 这条文案会直接出现在日志和界面上，所以必须告诉人"下一步做什么"，
     * 而不是只报"失败"。
     */
    fun explain(
        channel: Channel,
        shizukuInstalled: Boolean,
        shizukuAlive: Boolean,
        shizukuGranted: Boolean,
        packageName: String,
    ): String = when (channel) {
        Channel.DIRECT -> "可用（WRITE_SECURE_SETTINGS 已授予）"
        Channel.SHIZUKU -> "可用（Shizuku 已授权，以 shell 身份执行）"
        Channel.NONE -> when {
            !shizukuInstalled ->
                "不可用。两条路都没有：没有 WRITE_SECURE_SETTINGS，也没装 Shizuku。\n" +
                    "· 有电脑：adb shell pm grant $packageName android.permission.WRITE_SECURE_SETTINGS\n" +
                    "· 没电脑：装 Shizuku 并用「无线调试」配对（开发者选项 → 无线调试），" +
                    "然后回到本应用点「授权 Shizuku」"
            !shizukuAlive ->
                "不可用。Shizuku 已安装但服务没在运行 —— 请先打开 Shizuku 应用，" +
                    "按提示用无线调试启动它，再回到本应用"
            !shizukuGranted ->
                "不可用。Shizuku 在运行，但本应用还没拿到授权 —— " +
                    "请在本应用里点「授权 Shizuku」并在弹窗中选「允许」"
            else -> "不可用（原因未知）"
        }
    }

    /**
     * 把任意字符串包成 shell 单引号字面量。
     *
     * `enabled_accessibility_services` 的值形如
     * `com.a/.B:com.c/.D`，本身不需要转义；但只要**有一次**拼接没转义，
     * 就可能变成命令注入或写入空值（后者更常见：`;` 或空格把值截断，
     * 于是无障碍列表被写成半截，系统直接解绑服务 —— 比报错更难查）。
     *
     * 单引号内除了 `'` 本身没有特殊字符，所以把 `'` 换成 `'\''` 就够了。
     */
    fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
