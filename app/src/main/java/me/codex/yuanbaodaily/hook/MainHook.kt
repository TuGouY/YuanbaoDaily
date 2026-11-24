package me.codex.yuanbaodaily.hook

import android.app.Application
import android.content.Context
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import me.codex.yuanbaodaily.config.Target

/** LSPosed entry point. The host is filtered before any hook is installed. */
class MainHook : IXposedHookLoadPackage {
    override fun handleLoadPackage(lpparam: LoadPackageParam) {
        if (lpparam.packageName != Target.PACKAGE_NAME) return
        if (lpparam.processName != lpparam.packageName) return
        Log.d("matched ${Target.PACKAGE_NAME}, process=${lpparam.processName}")

        runCatching {
            XposedHelpers.findAndHookMethod(
                Application::class.java,
                "attach",
                Context::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val context = param.args.firstOrNull() as? Context ?: return
                        val version = runCatching {
                            Target.installedVersion(context)
                        }.getOrNull()
                        Log.d("host version at attach=${version ?: "unknown"}, target=${Target.VERSION_CODE}")
                        // Keep stable compatibility hooks active across host updates. Each
                        // hook is guarded independently, while the actual host version is
                        // recorded for diagnosis instead of silently disabling the module.
                        runCatching { EnvironmentCompatibility.install(lpparam.classLoader) }
                            .onFailure { Log.e("compatibility install failed", it) }
                        // 探针全部关闭：进度上报经行为验证确认是正常的（用户实测进度会涨），
                        // 之前的"不涨"是模块复核读取失败造成的误判，已通过"重开福利页重读"修复。
                        // 这里保持零探针，避免任何启动/性能风险。
                        Log.d("probes disabled; progress re-read fix is active")
                    }

                    override fun afterHookedMethod(param: MethodHookParam) {
                        val app = param.thisObject as? Application ?: return
                        runCatching {
                            YuanbaoRuntime.start(app, lpparam.classLoader)
                        }.onFailure { Log.e("runtime startup guard caught an exception", it) }
                    }
                },
            )
        }.onFailure { Log.e("failed to hook Application.attach", it) }
    }
}
