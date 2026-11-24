package me.codex.yuanbaodaily.hook

import android.webkit.WebView
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * 福利中心（WebView）JS 桥探针。
 *
 * 用户反馈：自动跑完的 5 项任务**不计入任务进度**（例如写作跑完，进度仍 0/3）。
 * 进度一定是由宿主通过 `addJavascriptInterface` 暴露的桥（或反过来由网页回调宿主）上报的，
 * 所以这里把所有被注册的桥对象、以及它们的方法名打印出来，用于定位"任务完成上报"的入口。
 *
 * 纯探测：不改变任何行为，只在日志里输出 `welfare bridge addJavascriptInterface name=… methods=…`。
 */
object WelfareBridgeProbe {

    private const val TAG = "welfare bridge"

    fun install(classLoader: ClassLoader) {
        val webViewClass = runCatching { Class.forName("android.webkit.WebView", false, classLoader) }
            .getOrNull() ?: WebView::class.java
        val method = runCatching {
            webViewClass.getDeclaredMethod("addJavascriptInterface", Any::class.java, String::class.java)
        }.getOrNull() ?: return
        XposedBridge.hookMethod(method, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                runCatching {
                    val target = param.args.getOrNull(0)
                    val name = param.args.getOrNull(1) as? String ?: "?"
                    Log.d("$TAG addJavascriptInterface name=$name impl=${target?.javaClass?.name}")
                    if (target != null) {
                        target.javaClass.methods
                            .filter { Modifier.isPublic(it.modifiers) && !isObjectMethod(it) }
                            .forEach { m ->
                                Log.d("$TAG   method ${m.name}(${m.parameterTypes.joinToString(",") { it.simpleName }})")
                            }
                        hookInvoke(target.javaClass)
                    }
                }.onFailure { Log.e("$TAG probe failed", it) }
            }
        })
        Log.d("$TAG probe installed")
    }

    /**
     * 网页 → 宿主 的 RPC 入口（`JsRuntime.invoke(String,String,String)`）。
     * 打印调用参数，用来定位"任务完成/进度上报"用的方法名与参数结构。
     */
    private fun hookInvoke(implClass: Class<*>) {
        if (!hookedClasses.add(implClass.name)) return
        val invoke = runCatching {
            implClass.getMethod("invoke", String::class.java, String::class.java, String::class.java)
        }.getOrNull() ?: return
        XposedBridge.hookMethod(invoke, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                runCatching {
                    val first = (param.args.getOrNull(0) as? String).orEmpty()
                    val second = (param.args.getOrNull(1) as? String).orEmpty()
                    val third = (param.args.getOrNull(2) as? String).orEmpty()
                    Log.d(
                        "$TAG invoke a=${first.take(120)} b=${second.take(120)} c=${third.take(160)}",
                    )
                }
            }
        })
        Log.d("$TAG invoke hook installed on ${implClass.name}")
    }

    private val hookedClasses = mutableSetOf<String>()

    private fun isObjectMethod(method: Method): Boolean =
        method.name in setOf("equals", "hashCode", "toString", "getClass", "notify", "notifyAll", "wait")
}
