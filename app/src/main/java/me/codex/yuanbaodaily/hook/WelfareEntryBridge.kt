package me.codex.yuanbaodaily.hook

import android.app.Application
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import java.lang.ref.WeakReference
import java.lang.reflect.Modifier
import java.net.URI
import java.util.concurrent.atomic.AtomicReference

/** Calls the host's own福利入口 handler so it supplies the current campaign URL. */
internal object WelfareEntryBridge {
    private const val CLASS_NAME =
        "com.tencent.hunyuan.mpcommon.generated.biz.mine.viewmodel.items.MineItemWelfareViewModel"
    private const val CAMPAIGN_DATA_CLASS =
        "com.tencent.hunyuan.mpcommon.generated.biz.hometab.viewmodel.items.OpenCampaignWebData"
    private const val ROUTER_CLASS = "J7.w3"

    @Volatile
    private var instance = WeakReference<Any>(null)
    @Volatile
    private var application: Application? = null
    private val campaignUrl = AtomicReference<String?>(null)

    fun install(app: Application, classLoader: ClassLoader) {
        application = app
        runCatching {
            val clazz = classLoader.loadClass(CLASS_NAME)
            XposedHelpers.findAndHookConstructor(clazz, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    instance = WeakReference(param.thisObject)
                }
            })
            hookCampaignData(classLoader)
            hookRouter(classLoader)
            Log.d("welfare entry bridge ready")
        }.onFailure { Log.d("welfare entry bridge unavailable (${it.javaClass.simpleName})") }
    }

    fun open(): Boolean {
        if (openCached()) return true
        val target = instance.get() ?: return false
        return runCatching {
            XposedHelpers.callMethod(target, "f")
            Log.d("official welfare entry click dispatched")
            true
        }.onFailure { Log.e("official welfare entry click failed", it) }.getOrDefault(false)
    }

    /** Launches the host router after a campaign URL has been observed. */
    fun openCached(): Boolean {
        val url = campaignUrl.get()?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
            ?: return false
        val app = application ?: return false
        return runCatching {
            val router = app.classLoader.loadClass(ROUTER_CLASS)
            XposedHelpers.callStaticMethod(router, "q", app, url)
            Log.d("cached welfare route dispatched: ${describeUrl(url)}")
            true
        }.onFailure { Log.e("cached welfare route failed", it) }.getOrDefault(false)
    }

    private fun hookCampaignData(classLoader: ClassLoader) {
        val clazz = classLoader.loadClass(CAMPAIGN_DATA_CLASS)
        for (constructor in clazz.declaredConstructors) {
            XposedHelpers.findAndHookConstructor(clazz, *constructor.parameterTypes, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    captureStringUrl(param.thisObject)
                }
            })
        }
    }

    private fun hookRouter(classLoader: ClassLoader) {
        val clazz = classLoader.loadClass(ROUTER_CLASS)
        for (method in clazz.declaredMethods) {
            if (method.name != "q" || !Modifier.isStatic(method.modifiers)) continue
            XposedHelpers.findAndHookMethod(clazz, method.name, *method.parameterTypes, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    param.args.filterIsInstance<String>()
                        .firstOrNull { it.startsWith("http://") || it.startsWith("https://") }
                        ?.let { campaignUrl.set(it) }
                }
            })
        }
    }

    private fun captureStringUrl(target: Any) {
        val url = target.javaClass.declaredMethods
            .filter { it.parameterTypes.isEmpty() && it.returnType == String::class.java }
            .asSequence()
            .mapNotNull { method -> runCatching {
                method.isAccessible = true
                method.invoke(target) as? String
            }.getOrNull() }
            .firstOrNull { it.startsWith("http://") || it.startsWith("https://") }
        if (url != null) {
            campaignUrl.set(url)
            Log.d("campaign welfare URL captured: ${describeUrl(url)}")
        }
    }

    private fun describeUrl(url: String): String = runCatching {
        val parsed = URI(url)
        "${parsed.host ?: "unknown"}${parsed.path ?: ""}".take(180)
    }.getOrDefault("url")
}
