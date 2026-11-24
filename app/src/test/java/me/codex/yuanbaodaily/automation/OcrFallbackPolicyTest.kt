package me.codex.yuanbaodaily.automation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "什么时候该跑 OCR"判据的回归测试。
 *
 * 这条策略是**性能护栏**：OCR 要截图 + 跑模型（几十到几百毫秒），
 * 而 `readScreenText()` 在导航/轮询里每秒会被调用好几次。
 * 判据一旦放宽，整个模块的点击节奏会被拖垮，还会因为识别误差引入新的误判。
 *
 * 所以这里锁死三件事：
 * 1. 正常页面（有字）**绝不**触发；
 * 2. 盲区（几乎没字）才触发；
 * 3. 触发之间有冷却，不会连续刷。
 */
class OcrFallbackPolicyTest {

    private fun shouldRun(text: String, lastOcrAt: Long = 0L, now: Long = 10_000L) =
        OcrFallbackPolicy.shouldRun(text, lastOcrAt, now)

    // ------------------------------ 不该触发 ------------------------------

    @Test
    fun normalPageNeverTriggersOcr() {
        val normal = "福利中心 每日任务 问元宝问题 1/3 使用写作能力 0/3 使用P图能力 0/3"
        assertFalse("正常页面有几十个字，绝不该跑 OCR", shouldRun(normal))
    }

    @Test
    fun exactlyAtThresholdDoesNotTrigger() {
        val text = "a".repeat(OcrFallbackPolicy.BLIND_TEXT_THRESHOLD)
        assertFalse("刚好达到阈值说明不是盲区", shouldRun(text))
    }

    @Test
    fun cooldownBlocksImmediateRetry() {
        val blank = ""
        val last = 10_000L
        assertFalse(
            "刚跑过 OCR，冷却期内不该再跑",
            shouldRun(blank, lastOcrAt = last, now = last + 500L),
        )
    }

    // ------------------------------ 该触发 ------------------------------

    @Test
    fun emptyScreenTriggersOcr() {
        assertTrue("一个字都没读到 = 典型盲区", shouldRun(""))
        assertTrue(shouldRun("   \n  "))
    }

    @Test
    fun nearEmptyScreenTriggersOcr() {
        val short = "加载中"
        assertTrue("只剩几个字，说明无障碍基本没读到内容", shouldRun(short))
    }

    @Test
    fun afterCooldownTriggersAgain() {
        val last = 10_000L
        assertTrue(
            "冷却结束后，仍然盲区就应该再补一次",
            shouldRun("", lastOcrAt = last, now = last + OcrFallbackPolicy.COOLDOWN_MS),
        )
    }

    // ------------------------------ 结果类型 ------------------------------

    @Test
    fun readResultDistinguishesUnavailableFromEmptyScreen() {
        // 不可用（版本低/没权限）→ ok=false，调用方不该重试
        val unavailable = OcrReadResult(ok = false, reason = "需要 Android 11+")
        assertFalse(unavailable.ok)
        assertTrue(unavailable.items.isEmpty())
        assertTrue(unavailable.asText().isEmpty())

        // 可用但画面没字 → ok=true、items 空，是"正常结论"而不是故障
        val emptyScreen = OcrReadResult(ok = true, items = emptyList())
        assertTrue(emptyScreen.ok)
        assertTrue(emptyScreen.items.isEmpty())
    }

    @Test
    fun readResultJoinsLinesForTextJudgements() {
        val result = OcrReadResult(
            ok = true,
            items = listOf(
                OcrItem("跳过 3s", 900, 120, 1100, 200),
                OcrItem("今日任务", 60, 400, 300, 460),
            ),
        )
        assertTrue(result.asText().contains("跳过 3s"))
        assertTrue(result.asText().contains("今日任务"))
        assertTrue(result.asText().contains("\n"))
    }

    @Test
    fun ocrItemCenterIsUsableForTapping() {
        val item = OcrItem("确定", 800, 1200, 1000, 1300)
        assertTrue(item.centerX == 900)
        assertTrue(item.centerY == 1250)
        assertTrue(item.width == 200 && item.height == 100)
    }
}
