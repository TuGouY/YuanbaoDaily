package me.codex.yuanbaodaily.hook

import dalvik.system.BaseDexClassLoader
import dalvik.system.DexFile

/**
 * 一次性类名扫描：把宿主里与"任务 / 福利 / 进度 / 签到"相关的类列出来。
 *
 * 目的（用户反馈）：自动跑完的任务不计入进度。进度模型一定在宿主原生类里，
 * 但类名随版本变化，所以先把候选类名扫出来，再针对候选类 hook 进度字段/上报方法。
 */
object ClassScanProbe {

    private const val TAG = "class scan"

    private val KEYWORDS = listOf(
        "task", "welfare", "progress", "signin", "sign_in", "checkin", "check_in",
        "award", "mission", "daily", "campaign", "benefit", "reward",
    )

    fun install(classLoader: ClassLoader) {
        runCatching {
            val loader = classLoader as? BaseDexClassLoader ?: return
            val pathList = loader.javaClass.getDeclaredField("pathList").apply { isAccessible = true }
                .get(loader) ?: return
            val elementsField = pathList.javaClass.getDeclaredField("dexElements").apply { isAccessible = true }
            val elements = elementsField.get(pathList) as? Array<*> ?: return
            val names = mutableSetOf<String>()
            for (element in elements) {
                if (element == null) continue
                val dexFile = runCatching {
                    element.javaClass.getDeclaredField("dexFile").apply { isAccessible = true }.get(element)
                }.getOrNull() as? DexFile ?: continue
                val entries = runCatching { dexFile.entries() }.getOrNull() ?: continue
                while (entries.hasMoreElements()) {
                    val name = runCatching { entries.nextElement() }.getOrNull() ?: continue
                    val lower = name.lowercase()
                    if (KEYWORDS.any { lower.contains(it) }) names += name
                }
            }
            Log.d("$TAG found ${names.size} candidate classes")
            names.sorted().take(120).forEach { Log.d("$TAG   $it") }
        }.onFailure { Log.e("$TAG scan failed", it) }
    }
}
