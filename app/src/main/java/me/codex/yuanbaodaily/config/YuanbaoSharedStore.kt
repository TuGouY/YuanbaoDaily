package me.codex.yuanbaodaily.config

import android.content.Context
import android.os.Bundle
import me.codex.yuanbaodaily.BuildConfig

/**
 * Shared state bridge between the module UI process and the injected Yuanbao
 * process. Android gives those packages different data sandboxes, so a plain
 * SharedPreferences lookup in the host would otherwise never see UI changes.
 */
internal object YuanbaoSharedStore {
    /** 模块配置所在的 prefs 文件名（各调用方原本各自写了一份常量）。 */
    const val CONFIG_PREFS = "yuanbao_daily_config"

    /**
     * 「允许自动执行」的截止时间（epoch 秒）。
     *
     * 2026-11-14 用户要求：**只有从模块启动元宝时才自动跑任务**，
     * 自己打开元宝不该被模块打断。所以调度器不再"到点就干"，
     * 而是要求这里有一个近期的时间戳；没有就只读进度、不动手。
     */
    const val KEY_AUTO_ARMED_UNTIL = "auto_armed_until"

    /** 视为"由模块启动"的有效窗口，10 分钟够跑完一轮。 */
    private const val ARM_WINDOW_SECONDS = 10 * 60

    fun armAutoPass(context: Context) {
        val until = (System.currentTimeMillis() / 1000L + ARM_WINDOW_SECONDS).toInt()
        putInt(context, CONFIG_PREFS, KEY_AUTO_ARMED_UNTIL, until)
    }

    fun isAutoPassArmed(context: Context): Boolean {
        val until = getInt(context, CONFIG_PREFS, KEY_AUTO_ARMED_UNTIL, 0)
        return System.currentTimeMillis() / 1000L < until
    }

    private const val GET = "get"
    private const val PUT = "put"
    private const val REMOVE = "remove"
    private const val PREFS = "prefs"
    private const val KEY = "key"
    private const val VALUE = "value"
    private const val TYPE = "type"
    private const val INT = "int"
    private const val BOOLEAN = "boolean"
    private const val DEFAULT = "default"

    fun getInt(context: Context, prefs: String, key: String, default: Int): Int {
        val local = context.getSharedPreferences(prefs, Context.MODE_PRIVATE)
        if (context.packageName == BuildConfig.APPLICATION_ID) return local.getInt(key, default)
        val result = call(context, GET, prefs, key, Bundle().apply {
            putString(TYPE, INT)
            putInt(DEFAULT, default)
        }) ?: return local.getInt(key, default)
        return result.getInt(VALUE, default)
    }

    fun putInt(context: Context, prefs: String, key: String, value: Int) {
        if (context.packageName == BuildConfig.APPLICATION_ID) {
            // 用 commit 而不是 apply：广播接收器所在的进程随时会被回收，
            // apply 的异步落盘来不及执行，实测表现为"收到了但没存下来"。
            context.getSharedPreferences(prefs, Context.MODE_PRIVATE).edit().putInt(key, value).commit()
            return
        }
        call(context, PUT, prefs, key, Bundle().apply {
            putString(TYPE, INT)
            putInt(VALUE, value)
        }) ?: context.getSharedPreferences(prefs, Context.MODE_PRIVATE).edit().putInt(key, value).commit()
    }

    fun getBoolean(context: Context, prefs: String, key: String, default: Boolean): Boolean {
        val local = context.getSharedPreferences(prefs, Context.MODE_PRIVATE)
        if (context.packageName == BuildConfig.APPLICATION_ID) return local.getBoolean(key, default)
        val result = call(context, GET, prefs, key, Bundle().apply {
            putString(TYPE, BOOLEAN)
            putBoolean(DEFAULT, default)
        }) ?: return local.getBoolean(key, default)
        return result.getBoolean(VALUE, default)
    }

    fun putBoolean(context: Context, prefs: String, key: String, value: Boolean) {
        if (context.packageName == BuildConfig.APPLICATION_ID) {
            context.getSharedPreferences(prefs, Context.MODE_PRIVATE).edit().putBoolean(key, value).apply()
            return
        }
        call(context, PUT, prefs, key, Bundle().apply {
            putString(TYPE, BOOLEAN)
            putBoolean(VALUE, value)
        }) ?: context.getSharedPreferences(prefs, Context.MODE_PRIVATE).edit().putBoolean(key, value).apply()
    }

    fun remove(context: Context, prefs: String, key: String) {
        if (context.packageName == BuildConfig.APPLICATION_ID) {
            context.getSharedPreferences(prefs, Context.MODE_PRIVATE).edit().remove(key).apply()
            return
        }
        call(context, REMOVE, prefs, key, null)
            ?: context.getSharedPreferences(prefs, Context.MODE_PRIVATE).edit().remove(key).apply()
    }

    private fun call(context: Context, method: String, prefs: String, key: String, extras: Bundle?): Bundle? =
        runCatching {
            val input = (extras ?: Bundle()).apply {
                putString(PREFS, prefs)
                putString(KEY, key)
            }
            val result = context.contentResolver.call(
                YuanbaoConfigProvider.URI,
                method,
                null,
                input,
            )
            if (method == GET && result != null) {
                me.codex.yuanbaodaily.hook.Log.d(
                    "config bridge get $prefs/$key ok"
                )
            }
            result
        }.getOrNull()
}
