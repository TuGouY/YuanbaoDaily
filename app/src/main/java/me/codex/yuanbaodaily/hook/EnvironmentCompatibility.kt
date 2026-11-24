package me.codex.yuanbaodaily.hook

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers

/** Small, version-gated compatibility layer for the 2.85.1 host checks. */
internal object EnvironmentCompatibility {
    fun install(classLoader: ClassLoader) {
        var installed = 0
        installed += hookBooleanMethods(classLoader, "com.tencent.beacon.base.util.g", listOf("a", "b", "c"))
        installed += hookBooleanMethods(classLoader, "com.tencent.bugly.proguard.a1", listOf("p"))
        runCatching {
            val grapeFruit = classLoader.loadClass("com.tencent.turingfd.sdk.base.Grapefruit")
            XposedHelpers.findAndHookMethod(grapeFruit, "b", Int::class.javaPrimitiveType, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val grape = param.result ?: return
                    // Grape.e is the TracerPid value. Preserve the other fields
                    // (pid/ppid/uid/process name) and only clear this signal.
                    runCatching { XposedHelpers.setIntField(grape, "e", 0) }
                }
            })
            installed++
            Log.d("compat hook ready: Turing TracerPid check")
        }
        Log.d("environment compatibility installed: $installed hooks")
    }

    private fun hookBooleanMethods(classLoader: ClassLoader, className: String, methods: List<String>): Int {
        var count = 0
        runCatching {
            val clazz = classLoader.loadClass(className)
            for (name in methods) {
                runCatching {
                    XposedHelpers.findAndHookMethod(clazz, name, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            param.result = false
                        }
                    })
                    count++
                    Log.d("compat hook ready: $className.$name")
                }
            }
        }
        return count
    }
}
