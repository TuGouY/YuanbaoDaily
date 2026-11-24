package me.codex.yuanbaodaily.automation

import android.os.Bundle
import me.codex.yuanbaodaily.BuildConfig
import java.util.concurrent.atomic.AtomicInteger

internal object RootInputController {
    fun perform(input: Bundle): Bundle {
        // 免 root 版直接短路：这台机器上没有 su，真去 `ProcessBuilder("su", ...)`
        // 会 fork 一个永远不会被授予权限的进程，然后等满超时（实测 8 秒）才返回，
        // 表现为"任务卡住不动"。这里在**编译期**就把它关掉，一次 syscall 都不发。
        if (!BuildConfig.ROOT_MODE) {
            return result(false).apply { putString("reason", "免root版：不做 su 调用") }
        }
        val action = input.getString("action").orEmpty()
        val command = when (action) {
            "tap" -> {
                val x = input.getInt("x", -1)
                val y = input.getInt("y", -1)
                if (x !in 0..10_000 || y !in 0..10_000) return result(false)
                "input tap $x $y"
            }
            "back" -> "input keyevent 4"
            // Types into the focused editor of whatever is in front. Unlike the
            // accessibility service this also works inside a cloned ("双开")
            // host, because the shell is not tied to one Android user.
            "text" -> {
                val value = shellText(input.getString("value").orEmpty()) ?: return result(false)
                "input text '$value'"
            }
            "key" -> {
                val code = input.getInt("code", -1)
                if (code !in ALLOWED_KEYS) return result(false)
                "input keyevent $code"
            }
            // Vertical swipe for pages accessibility cannot reach.
            "swipe" -> {
                val x = input.getInt("x", -1)
                val fromY = input.getInt("from_y", -1)
                val toY = input.getInt("to_y", -1)
                val duration = input.getInt("duration", 320).coerceIn(120, 1_200)
                if (x !in 0..10_000 || fromY !in 0..20_000 || toY !in 0..20_000) return result(false)
                if (fromY == toY) return result(false)
                "input swipe $x $fromY $x $toY $duration"
            }
            // Platform UI dump. The text comes back through stdout so the caller
            // never needs to read another app's file.
            "dump" ->
                "rm -f $DUMP_PATH; uiautomator dump $DUMP_PATH >/dev/null 2>&1; " +
                    "cat $DUMP_PATH 2>/dev/null"
            // Cheap availability probe: exit code 0 means the root manager
            // granted this process, which is what the text/key channel needs.
            "probe" -> "id"
            "launch_target" -> "monkey -p com.tencent.hunyuan.app.chat -c android.intent.category.LAUNCHER 1"
            else -> return result(false)
        }
        val timeout = if (action == "dump") DUMP_TIMEOUT_MS else COMMAND_TIMEOUT_MS
        val shell = execute(command, timeout) ?: return result(false)
        return Bundle().apply {
            putBoolean("ok", shell.exitCode == 0)
            putInt("exit", shell.exitCode)
            if (shell.stdout.isNotBlank()) putString("output", shell.stdout.take(MAX_OUTPUT))
        }
    }

    private data class ShellResult(val exitCode: Int, val stdout: String)

    /**
     * Runs one `su -c` command.
     *
     * stdout is drained in its own thread: a UI dump is far larger than a pipe
     * buffer, and a child blocked on a full pipe never reaches its exit code.
     */
    private fun execute(command: String, timeoutMs: Long): ShellResult? = runCatching {
        val process = ProcessBuilder("su", "-c", command)
            .redirectErrorStream(true)
            .start()
        val exitCode = AtomicInteger(Int.MIN_VALUE)
        val buffer = StringBuilder()
        val reader = Thread({
            runCatching {
                process.inputStream.bufferedReader().use { stream ->
                    val chunk = CharArray(8_192)
                    while (true) {
                        val read = stream.read(chunk)
                        if (read <= 0) break
                        val room = MAX_OUTPUT - buffer.length
                        if (room <= 0) continue
                        buffer.append(chunk, 0, minOf(read, room))
                    }
                }
            }
        }, "YuanbaoDailyRootRead").apply {
            isDaemon = true
            start()
        }
        val waiter = Thread({
            exitCode.set(runCatching { process.waitFor() }.getOrDefault(Int.MIN_VALUE))
        }, "YuanbaoDailyRootWait").apply {
            isDaemon = true
            start()
        }
        waiter.join(timeoutMs)
        if (waiter.isAlive) {
            process.destroy()
            reader.interrupt()
            waiter.interrupt()
            null
        } else {
            reader.join(1_000L)
            ShellResult(exitCode.get(), buffer.toString())
        }
    }.getOrNull()

    /**
     * `input text` takes one shell word: spaces have to be sent as `%s` and a
     * single quote has to be closed and re-opened around an escaped quote.
     * Anything that could break out of the quoting is dropped instead.
     */
    private fun shellText(value: String): String? {
        val trimmed = value
            .replace('\n', ' ')
            .replace('\r', ' ')
            .replace("\\", "")
            .replace("\"", "")
            .trim()
        if (trimmed.isEmpty() || trimmed.length > MAX_TEXT) return null
        return trimmed
            .replace(" ", "%s")
            .replace("'", "'\\''")
    }

    private fun result(ok: Boolean): Bundle = Bundle().apply { putBoolean("ok", ok) }

    private const val MAX_TEXT = 800
    private const val MAX_OUTPUT = 900_000
    private const val COMMAND_TIMEOUT_MS = 3_000L
    private const val DUMP_TIMEOUT_MS = 8_000L
    private const val DUMP_PATH = "/data/local/tmp/yuanbao_daily_dump.xml"

    /** Only the keys the module actually needs, so the channel stays bounded. */
    private val ALLOWED_KEYS = setOf(4, 66, 67, 111)
}
