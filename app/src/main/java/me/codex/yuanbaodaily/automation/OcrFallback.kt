package me.codex.yuanbaodaily.automation

/**
 * OCR 兜底：**只在无障碍读不到字的时候**才用，正常路径一次都不跑。
 *
 * ## 为什么需要它
 *
 * 参考 APK（`汽水自动跳广告`）用的是 PaddleOCR + onnxruntime + MediaProjection。
 * 我们的模块 99% 的读屏都走无障碍树（更快更准、零权限），但存在一类真"盲区"：
 * 页面上明明有字，无障碍树里却一个字都没有 —— 此时后续所有基于文本的判定
 * （这是哪一屏、按钮在哪）全部失效，而且**表现得像"卡住了"**，很难从日志看出来。
 *
 * ## 判据为什么必须这么严
 *
 * OCR 一次要截图 + 跑模型，几十到几百毫秒。如果放进常规轮询里，
 * 会把原本流畅的点击节奏拖垮，还会因为识别误差引入新的误判。
 * 所以策略是**只有无障碍一个字都没读到**才启用，且带冷却时间。
 *
 * 注意：判据是纯函数，单独可测（[OcrFallbackPolicy]）。
 */
internal object OcrFallbackPolicy {

    /**
     * 无障碍文本低于这个长度就认为是"盲区"。
     *
     * 取 12：一个正常页面随便也有几十个字（按钮、标题、计数）。
     * 低于 12 基本只剩三种情况 —— 空页面、纯图片页、或者无障碍真的没读到。
     */
    const val BLIND_TEXT_THRESHOLD = 12

    /** 两次 OCR 之间的最小间隔。截图本身在系统侧就有 ~1 次/秒的限制。 */
    const val COOLDOWN_MS = 2_500L

    /**
     * 现在该不该跑 OCR。
     *
     * @param accessibilityText 无障碍树读到的全部文字（已去重拼接）。
     * @param lastOcrAtMs 上次 OCR 的时间戳（uptimeMillis），没跑过传 0。
     * @param nowMs 当前 uptimeMillis。
     */
    fun shouldRun(
        accessibilityText: String,
        lastOcrAtMs: Long,
        nowMs: Long,
    ): Boolean {
        if (accessibilityText.trim().length >= BLIND_TEXT_THRESHOLD) return false
        if (lastOcrAtMs <= 0L) return true
        return nowMs - lastOcrAtMs >= COOLDOWN_MS
    }
}

/** 一块识别结果：文字 + 它在屏幕上的真实矩形（像素，含状态栏坐标系）。 */
internal data class OcrItem(
    val text: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val centerX: Int get() = left + (right - left) / 2
    val centerY: Int get() = top + (bottom - top) / 2
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

/**
 * 一次 OCR 调用的结果。
 *
 * 区分 `ok=false` 和 `items=空` 很重要：
 * - `ok=false` → 能力不可用（Android 版本低 / 没给截图权限 / 安全窗口），**不要重试**；
 * - `ok=true, items=空` → 截图成功了但画面里真的没有字（比如全是图）。
 */
internal data class OcrReadResult(
    val ok: Boolean,
    val items: List<OcrItem> = emptyList(),
    val reason: String = "",
) {
    /** 给"这一屏写了什么"用的纯文本。 */
    fun asText(): String = items.joinToString("\n") { it.text }
}

/**
 * OCR 引擎抽象。
 *
 * 单独抽接口的理由很实际：现在用的是 ML Kit（模型随包内置、离线、中文够用），
 * 将来若因为体积或精度想换成 PaddleOCR + onnxruntime（参考 APK 那套），
 * 只需要再写一个实现，**调用方一行都不用改**。
 */
internal interface OcrEngine {
    /** 阻塞识别一帧。失败返回空列表，不抛异常。 */
    fun recognize(bitmap: android.graphics.Bitmap): List<OcrItem>
}
