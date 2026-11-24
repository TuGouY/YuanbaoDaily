package me.codex.yuanbaodaily.hook

/** Keeps logging usable when the module UI is launched outside an injected host. */
internal object LogBridge {
    fun write(
        tag: String,
        message: String,
        xposedSink: ((String) -> Unit)?,
        androidSink: (String, String) -> Unit,
    ) {
        if (xposedSink != null) {
            runCatching { xposedSink(message) }
                .onFailure { androidSink(tag, message) }
        } else {
            androidSink(tag, message)
        }
    }

    fun findXposedSink(loader: ClassLoader?): ((String) -> Unit)? = runCatching {
        val bridge = Class.forName("de.robv.android.xposed.XposedBridge", false, loader)
        val method = bridge.getMethod("log", String::class.java)
        val sink: (String) -> Unit = { message -> method.invoke(null, message) }
        sink
    }.getOrNull()
}
