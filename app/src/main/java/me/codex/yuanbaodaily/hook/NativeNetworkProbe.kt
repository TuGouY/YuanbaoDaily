package me.codex.yuanbaodaily.hook

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.lang.reflect.Method

/**
 * 原生网络层探针（定位"任务完成上报"）。
 *
 * 背景：福利中心的任务进度由服务端下发，而"完成上报"不是网页发起的
 * `JsRuntime.invoke`（已全量抓过，没有 finish/report 类调用），所以它发生在**宿主原生侧**。
 * 这里 hook 常见网络栈（okhttp3 及其 FormBody），只打印含任务关键字的请求 URL + 请求体，
 * 用来拿到上报接口，之后模块就能在每项任务完成后按同一路径补发上报。
 *
 * 纯探测：只读不写，不改变任何请求。
 */
object NativeNetworkProbe {

    private const val TAG = "net probe"

    private val KEYWORDS = listOf(
        "task", "welfare", "award", "finish", "report", "complete", "sign", "check_in",
        "receive", "claim", "invite", "progress", "mission", "daily",
    )

    private val hooked = mutableSetOf<String>()

    fun install(classLoader: ClassLoader) {
        hookOkHttpBuilder(classLoader)
        hookOkHttpClient(classLoader)
        hookHttpUrlConnection(classLoader)
        hookWebViewLoadUrl(classLoader)
        hookShouldInterceptRequest(classLoader)
        hookUrlOpenConnection(classLoader)
        hookClassLoaderForTaskClasses(classLoader)
    }

    /**
     * **只装最安全的一个探针**：`WebViewClient.shouldInterceptRequest`。
     *
     * 只读、范围窄（只在 WebView 发请求时回调），不会像 `ClassLoader.loadClass` 那样
     * 拦截全进程类加载而卡死启动（实测 3054 卡在启动页）。用于抓"任务完成上报"。
     */
    fun installSafeOnly(classLoader: ClassLoader) {
        runCatching { hookShouldInterceptRequest(classLoader) }
            .onFailure { Log.e("$TAG safe probe install failed", it) }
    }

    /**
     * 网页侧**全部**请求（含 XHR/fetch）都会经过 `shouldInterceptRequest`，
     * 比 `loadUrl` 完整得多 —— 用来确认"完成上报"是否由网页发起。
     */
    private fun hookShouldInterceptRequest(classLoader: ClassLoader) {
        val client = runCatching { Class.forName("android.webkit.WebViewClient", false, classLoader) }
            .getOrNull() ?: return
        val modern = runCatching {
            client.getDeclaredMethod(
                "shouldInterceptRequest",
                Class.forName("android.webkit.WebView", false, classLoader),
                Class.forName("android.webkit.WebResourceRequest", false, classLoader),
            )
        }.getOrNull()
        if (modern != null) {
            XposedBridge.hookMethod(modern, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    runCatching {
                        val request = param.args.getOrNull(1) ?: return
                        val url = runCatching {
                            request.javaClass.getMethod("getUrl").invoke(request)?.toString()
                        }.getOrNull().orEmpty()
                        val method = runCatching {
                            request.javaClass.getMethod("getMethod").invoke(request)?.toString()
                        }.getOrNull() ?: "?"
                        if (!interesting(url)) return
                        Log.d("$TAG intercept $method url=${url.take(240)}")
                    }
                }
            })
            Log.d("$TAG shouldInterceptRequest hook installed")
        }
        val legacy = runCatching {
            client.getDeclaredMethod("shouldInterceptRequest", Class.forName("android.webkit.WebView", false, classLoader), String::class.java)
        }.getOrNull()
        if (legacy != null) {
            XposedBridge.hookMethod(legacy, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    runCatching {
                        val url = param.args.getOrNull(1) as? String ?: return
                        if (!interesting(url)) return
                        Log.d("$TAG interceptLegacy url=${url.take(240)}")
                    }
                }
            })
        }
    }

    private fun hookUrlOpenConnection(classLoader: ClassLoader) {
        val urlClass = runCatching { Class.forName("java.net.URL", false, classLoader) }.getOrNull() ?: return
        val open = runCatching { urlClass.getDeclaredMethod("openConnection") }.getOrNull() ?: return
        if (!hooked.add("url.openConnection")) return
        XposedBridge.hookMethod(open, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                runCatching {
                    val self = param.thisObject ?: return
                    val url = self.toString()
                    if (!interesting(url)) return
                    Log.d("$TAG urlOpenConnection url=${url.take(240)}")
                }
            }
        })
        Log.d("$TAG URL.openConnection hook installed")
    }

    /**
     * 监听 `ClassLoader.loadClass`：把"任务/进度/福利"相关的类名打出来。
     * 该 ROM 屏蔽了 BaseDexClassLoader 反射，但 loadClass 是公开入口，稳定可用。
     */
    private fun hookClassLoaderForTaskClasses(classLoader: ClassLoader) {
        val cls = runCatching { Class.forName("java.lang.ClassLoader", false, classLoader) }.getOrNull() ?: return
        val loadClass = runCatching { cls.getDeclaredMethod("loadClass", String::class.java) }.getOrNull() ?: return
        if (!hooked.add("classloader.loadClass")) return
        XposedBridge.hookMethod(loadClass, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                runCatching {
                    val name = param.args.getOrNull(0) as? String ?: return
                    val lower = name.lowercase()
                    if (KEYWORDS.none { lower.contains(it) }) return
                    Log.d("$TAG loadClass $name")
                }
            }
        })
        Log.d("$TAG ClassLoader.loadClass watcher installed")
    }

    /** 只记录腾讯域名的请求，避免刷屏；不再按"任务关键字"过滤（关键字过滤会漏掉上报）。 */
    private fun interesting(url: String): Boolean {
        val lower = url.lowercase()
        if (lower.contains("tencent.com") || lower.contains("qq.com") || lower.contains("hunyuan")) return true
        return KEYWORDS.any { lower.contains(it) }
    }

    private fun hookOkHttpClient(classLoader: ClassLoader) {
        val client = runCatching { Class.forName("okhttp3.OkHttpClient", false, classLoader) }.getOrNull() ?: return
        val newCall = runCatching { client.getDeclaredMethod("newCall", Class.forName("okhttp3.Request", false, classLoader)) }
            .getOrNull() ?: return
        XposedBridge.hookMethod(newCall, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                runCatching {
                    val request = param.args.getOrNull(0) ?: return
                    val url = request.javaClass.getMethod("url").invoke(request)?.toString().orEmpty()
                    val method = runCatching {
                        request.javaClass.getMethod("method").invoke(request)?.toString()
                    }.getOrNull() ?: "?"
                    if (method == "GET") return
                    if (!interesting(url)) return
                    val body = runCatching { request.javaClass.getMethod("body").invoke(request) }.getOrNull()
                    Log.d("$TAG NEWCALL $method url=${url.take(220)} body=${describeBody(body)}")
                }
            }
        })
        Log.d("$TAG okhttp newCall hook installed")
    }

    private fun hookHttpUrlConnection(classLoader: ClassLoader) {
        val connection = runCatching { Class.forName("java.net.HttpURLConnection", false, classLoader) }.getOrNull() ?: return
        val connect = runCatching { connection.getDeclaredMethod("connect") }.getOrNull() ?: return
        XposedBridge.hookMethod(connect, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                runCatching {
                    val self = param.thisObject ?: return
                    val url = runCatching { self.javaClass.getMethod("getURL").invoke(self)?.toString().orEmpty() }
                        .getOrNull().orEmpty()
                    val method = runCatching { self.javaClass.getMethod("getRequestMethod").invoke(self)?.toString() }
                        .getOrNull() ?: "?"
                    if (method == "GET") return
                    if (!interesting(url)) return
                    Log.d("$TAG HTTPURL $method url=${url.take(220)}")
                }
            }
        })
        Log.d("$TAG HttpURLConnection hook installed")
    }

    private fun hookOkHttpBuilder(classLoader: ClassLoader) {
        val builder = runCatching { Class.forName("okhttp3.Request\$Builder", false, classLoader) }
            .getOrNull() ?: return
        val build = runCatching { builder.getDeclaredMethod("build") }.getOrNull() ?: return
        XposedBridge.hookMethod(build, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                runCatching {
                    val request = param.result ?: return
                    val url = request.javaClass.getMethod("url").invoke(request)?.toString().orEmpty()
                    if (KEYWORDS.none { url.lowercase().contains(it) }) return
                    val method = runCatching {
                        request.javaClass.getMethod("method").invoke(request)?.toString()
                    }.getOrNull()
                    val body = runCatching {
                        request.javaClass.getMethod("body").invoke(request)
                    }.getOrNull()
                    Log.d("$TAG ${method ?: "?"} url=$url body=${describeBody(body)}")
                }
            }
        })
        Log.d("$TAG okhttp Request.Builder hook installed")
    }

    /** 网页侧请求（XHR）也顺带记录，便于对照。 */
    private fun hookWebViewLoadUrl(classLoader: ClassLoader) {
        val webView = runCatching { Class.forName("android.webkit.WebView", false, classLoader) }
            .getOrNull() ?: return
        for (name in listOf("loadUrl", "postUrl")) {
            val method = runCatching { webView.getDeclaredMethod(name, String::class.java) }.getOrNull() ?: continue
            if (!hooked.add("$name")) continue
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    runCatching {
                        val url = param.args.getOrNull(0) as? String ?: return
                        if (KEYWORDS.none { url.lowercase().contains(it) }) return
                        Log.d("$TAG webview $name url=${url.take(200)}")
                    }
                }
            })
        }
    }

    /** 尽量把请求体变成可读文本：FormBody 用 name/value，其它尝试 writeTo(Buffer)。 */
    private fun describeBody(body: Any?): String {
        if (body == null) return "null"
        val cls = body.javaClass
        // 1) FormBody：size()/name(i)/value(i)
        runCatching {
            val size = cls.getMethod("size").invoke(body) as Int
            val name = cls.getMethod("name", Int::class.javaPrimitiveType)
            val value = cls.getMethod("value", Int::class.javaPrimitiveType)
            val pairs = (0 until size).joinToString("&") { index ->
                "${name.invoke(body, index)}=${value.invoke(body, index)}"
            }
            if (pairs.isNotEmpty()) return "form[$pairs]"
        }
        // 2) 其它：writeTo(okio.Buffer) 后 readUtf8()
        return runCatching {
            val bufferClass = Class.forName("okio.Buffer")
            val buffer = bufferClass.getDeclaredConstructor().newInstance()
            val writeTo = cls.methods.firstOrNull {
                it.name == "writeTo" && it.parameterTypes.size == 1 &&
                    it.parameterTypes[0].name == "okio.BufferedSink"
            } ?: return "unknown(${cls.simpleName})"
            writeTo.invoke(body, buffer)
            val text = bufferClass.getMethod("readUtf8").invoke(buffer) as? String
            "json[${text?.take(300)}]"
        }.getOrElse { "unknown(${cls.simpleName})" }
    }

    @Suppress("unused")
    private fun unused(method: Method) = method
}
