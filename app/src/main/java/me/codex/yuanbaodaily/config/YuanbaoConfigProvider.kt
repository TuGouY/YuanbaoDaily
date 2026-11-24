package me.codex.yuanbaodaily.config

import android.annotation.SuppressLint
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process
import android.util.Log as AndroidLog
import me.codex.yuanbaodaily.BuildConfig
import me.codex.yuanbaodaily.automation.AutomationAccessibilityService
import me.codex.yuanbaodaily.automation.AutomationCommandDispatcher
import me.codex.yuanbaodaily.automation.RootInputController
import me.codex.yuanbaodaily.task.AlarmBootstrap

/** Exported only as a narrow key/value bridge for the injected host process. */
class YuanbaoConfigProvider : ContentProvider() {
    private lateinit var moduleContext: Context

    companion object {
        // Keep the authority in lockstep with the build variant.
        //
        // 以前这里是两个硬编码字符串（`.debug.config` / `.config`），加了 `lite` 风味之后
        // 就会和 root 版**撞 authority** —— 两个包同时装在一台机器上时，
        // 后装的那个会直接安装失败（provider authority 冲突）。
        // 直接把 applicationId 拼进去，四个变体（xposed/lite × debug/release）自动互不冲突：
        //   me.codex.yuanbaodaily.config
        //   me.codex.yuanbaodaily.debug.config
        //   me.codex.yuanbaodaily.lite.config
        //   me.codex.yuanbaodaily.lite.debug.config
        private val AUTHORITY = "${BuildConfig.APPLICATION_ID}.config"
        val URI: Uri = Uri.parse("content://$AUTHORITY/state")
        private const val GET = "get"
        private const val PUT = "put"
        private const val REMOVE = "remove"
        private const val AUTOMATION = "automation"
        private const val ROOT_INPUT = "root_input"

        /** 见 AutomationCommandDispatcher.METHOD_UI_DUMP：dump 由服务进程代跑 su。 */
        private const val UI_DUMP = "ui_dump"

        /** 见 AutomationCommandDispatcher.METHOD_A11Y_REPAIR：强制重绑无障碍服务（自愈）。 */
        private const val A11Y_REPAIR = "a11y_repair"
        private const val BASE_MODULE_PACKAGE = "me.codex.yuanbaodaily"
        private const val TARGET_PACKAGE = "com.tencent.hunyuan.app.chat"
        private const val PREFS = "prefs"
        private const val KEY = "key"
        private const val VALUE = "value"
        private const val TYPE = "type"
        private const val INT = "int"
        private const val BOOLEAN = "boolean"
        private const val DEFAULT = "default"
        private const val ACTION = "action"
        private const val OK = "ok"
        private const val TAG = "YuanbaoDaily"
    }

    override fun onCreate(): Boolean {
        val host = context ?: run {
            AndroidLog.e(TAG, "provider onCreate without context")
            return false
        }
        moduleContext = runCatching {
            host.createPackageContext(
                "me.codex.yuanbaodaily",
                Context.CONTEXT_IGNORE_SECURITY,
            )
        }.recoverCatching {
            host.createPackageContext(BuildConfig.APPLICATION_ID, Context.CONTEXT_IGNORE_SECURITY)
        }.getOrElse { host }
        AndroidLog.d(
            TAG,
            "provider onCreate package=${host.packageName} uid=${Process.myUid()} " +
                "moduleContext=${moduleContext.packageName}",
        )
        // provider 跑在模块自己的进程里（只是被宿主调用），所以这里也是
        // "模块进程起来了"的一个可信信号。见 AlarmBootstrap。
        runCatching { AlarmBootstrap.arm(moduleContext, "provider.onCreate") }
        return true
    }

    @SuppressLint("ApplySharedPref")
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val callerUid = Binder.getCallingUid()
        val callerPackages = packagesForUid(callerUid)
        AndroidLog.d(
            TAG,
            "provider call method=$method callerUid=$callerUid " +
                "packages=${callerPackages.joinToString(",")}",
        )
        if (!isAllowedCaller(callerUid, callerPackages)) {
            AndroidLog.w(TAG, "provider denied callerUid=$callerUid")
            return result(false)
        }
        val input = extras ?: Bundle()
        if (method == AUTOMATION || method == ROOT_INPUT || method == UI_DUMP || method == A11Y_REPAIR) {
            val output = AutomationCommandDispatcher.dispatch(moduleContext, method, input)
                ?: result(false)
            AndroidLog.d(
                TAG,
                "provider $method action=${input.getString(ACTION)} " +
                    "ok=${output.getBoolean(OK, false)}",
            )
            return output
        }

        val prefsName = input.getString(PREFS).orEmpty()
        val key = input.getString(KEY).orEmpty()
        // The provider may be called from Yuanbao's injected process. Always
        // resolve preferences through the module package context so both
        // processes read the same file instead of falling back to Yuanbao's
        // private sandbox.
        val prefs = moduleContext.getSharedPreferences(prefsName, 0)
        return when (method) {
            GET -> Bundle().apply {
                if (input.getString(TYPE) == BOOLEAN) {
                    putBoolean(VALUE, prefs.getBoolean(key, input.getBoolean(DEFAULT, false)))
                } else {
                    putInt(VALUE, prefs.getInt(key, input.getInt(DEFAULT, 0)))
                }
            }
            PUT -> {
                val editor = prefs.edit()
                if (input.getString(TYPE) == BOOLEAN) editor.putBoolean(key, input.getBoolean(VALUE))
                else editor.putInt(key, input.getInt(VALUE))
                // The host scheduler polls immediately after process start;
                // make the cross-process write visible before returning.
                editor.commit()
                Bundle()
            }
            REMOVE -> {
                prefs.edit().remove(key).apply()
                Bundle()
            }
            else -> Bundle()
        }
    }

    private fun isAllowedCaller(uid: Int, packages: Array<out String>): Boolean {
        if (uid == Process.myUid()) return true
        return packages.any {
            it == BuildConfig.APPLICATION_ID || it == BASE_MODULE_PACKAGE || it == TARGET_PACKAGE
        }
    }

    private fun packagesForUid(uid: Int): Array<out String> = runCatching {
        val owner = if (::moduleContext.isInitialized) moduleContext else context
        owner?.packageManager?.getPackagesForUid(uid).orEmpty()
    }.getOrDefault(emptyArray())

    private fun result(ok: Boolean): Bundle = Bundle().apply { putBoolean(OK, ok) }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
