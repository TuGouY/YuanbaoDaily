package me.codex.yuanbaodaily.automation

import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log as AndroidLog
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * ML Kit 中文文本识别实现。
 *
 * 选它而不是参考 APK 的 PaddleOCR + onnxruntime，理由：
 * - 模型随 APK 内置（`assets/mlkit-google-ocr-models`，2.4MB），**离线可用、不依赖 GMS 下载**；
 * - 不需要自己把 Paddle 的 det/rec 转 ONNX、也不用引入 17MB 的推理库；
 * - 这条链路只是"盲区兜底"，用不上 Paddle 更强（也更重）的能力。
 *
 * 实测体积账（只留 arm64 + armv7）：native 库 17MB + 模型 2.4MB。
 * 若将来要换引擎，实现 [OcrEngine] 即可，[AutomationAccessibilityService] 不用动。
 */
internal class MlKitOcrEngine : OcrEngine {

    private val recognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }

    override fun recognize(bitmap: Bitmap): List<OcrItem> {
        val latch = CountDownLatch(1)
        var items: List<OcrItem> = emptyList()
        var failure: String? = null
        runCatching {
            recognizer.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { text ->
                    items = text.textBlocks.flatMap { block ->
                        block.lines.mapNotNull { line ->
                            val box = line.boundingBox ?: return@mapNotNull null
                            val label = line.text.trim()
                            if (label.isEmpty()) return@mapNotNull null
                            OcrItem(
                                text = label,
                                left = box.left,
                                top = box.top,
                                right = box.right,
                                bottom = box.bottom,
                            )
                        }
                    }
                    latch.countDown()
                }
                .addOnFailureListener { error ->
                    failure = error.message
                    latch.countDown()
                }
        }.onFailure {
            failure = it.message
            latch.countDown()
        }
        if (!latch.await(RECOGNIZE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            AndroidLog.w(TAG, "OCR 超时（${RECOGNIZE_TIMEOUT_MS}ms）")
            return emptyList()
        }
        if (failure != null) {
            AndroidLog.w(TAG, "OCR 失败：$failure")
            return emptyList()
        }
        return items
    }

    /** 识别结果拼成一行（给"这一屏到底写了什么"这类判定用）。 */
    fun toText(items: List<OcrItem>): String = items.joinToString("\n") { it.text }

    /** 按文字找一块识别结果（包含匹配，取面积最小的那块，避免命中整屏容器）。 */
    fun findText(items: List<OcrItem>, keyword: String): OcrItem? {
        if (keyword.isBlank()) return null
        return items.asSequence()
            .filter { it.text.contains(keyword, ignoreCase = true) }
            .minByOrNull { it.width.toLong() * it.height.toLong() }
    }

    private companion object {
        const val TAG = "YuanbaoDailyOcr"
        const val RECOGNIZE_TIMEOUT_MS = 4_000L
    }
}

/** 空实现：给"没装引擎/引擎不可用"的构建用，保证调用方不用判空。 */
internal object NoopOcrEngine : OcrEngine {
    override fun recognize(bitmap: Bitmap): List<OcrItem> = emptyList()
}

/** 工具：把识别框裁到屏幕内（OCR 偶尔会给出越界矩形）。 */
internal fun OcrItem.clampedTo(screenWidth: Int, screenHeight: Int): OcrItem {
    if (screenWidth <= 0 || screenHeight <= 0) return this
    val r = Rect(
        left.coerceIn(0, screenWidth),
        top.coerceIn(0, screenHeight),
        right.coerceIn(0, screenWidth),
        bottom.coerceIn(0, screenHeight),
    )
    return OcrItem(text, r.left, r.top, r.right, r.bottom)
}
