package me.codex.yuanbaodaily.hook

import me.codex.yuanbaodaily.config.Target
import android.util.Log as AndroidLog

internal object Log {
    private const val TAG = "YuanbaoDaily"

    fun d(message: String) = write(message)
    fun e(message: String, throwable: Throwable? = null) {
        write(message)
        throwable?.let { AndroidLog.e(TAG, Target.LOG_TAG, it) }
    }

    /**
     * Channel-level warnings.
     *
     * Kept at warn level on purpose: a failing bridge used to print one exception
     * per poll and push everything else out of the logcat ring buffer, so the
     * lines that explain which input channel is in use have to stand out.
     */
    fun w(message: String) {
        val line = "${Target.LOG_TAG}: W: $message"
        LogBridge.write(
            tag = TAG,
            message = line,
            xposedSink = LogBridge.findXposedSink(Log::class.java.classLoader),
            androidSink = { tag, text -> AndroidLog.w(tag, text) },
        )
    }

    private fun write(message: String) {
        val line = "${Target.LOG_TAG}: $message"
        LogBridge.write(
            tag = TAG,
            message = line,
            xposedSink = LogBridge.findXposedSink(Log::class.java.classLoader),
            androidSink = { tag, text -> AndroidLog.d(tag, text) },
        )
    }
}
