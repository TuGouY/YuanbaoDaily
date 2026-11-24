package me.codex.yuanbaodaily.task

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Rect
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.ValueCallback
import android.webkit.WebView
import android.widget.EditText
import android.widget.Button
import android.widget.TextView
import me.codex.yuanbaodaily.BuildConfig
import me.codex.yuanbaodaily.automation.AccessibilityPackagePolicy
import me.codex.yuanbaodaily.automation.AutomationBridge
import me.codex.yuanbaodaily.automation.AutomationGate
import me.codex.yuanbaodaily.automation.BlockingDialog
import me.codex.yuanbaodaily.automation.ComposerState
import me.codex.yuanbaodaily.automation.MediaSelectionVerifier
import me.codex.yuanbaodaily.automation.OcrFallbackPolicy
import me.codex.yuanbaodaily.automation.isMeaningfulText
import me.codex.yuanbaodaily.automation.SurfaceSnapshot
import me.codex.yuanbaodaily.automation.SystemGuardPolicy
import me.codex.yuanbaodaily.automation.WindowStackPolicy
import me.codex.yuanbaodaily.automation.SurfaceText
import me.codex.yuanbaodaily.automation.TapTrace
import me.codex.yuanbaodaily.automation.WelfareNav
import me.codex.yuanbaodaily.automation.WritingPage
import me.codex.yuanbaodaily.automation.toShortString
import me.codex.yuanbaodaily.config.Target
import me.codex.yuanbaodaily.hook.Log
import java.lang.reflect.Proxy
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal data class TaskActionResult(
    val actionStarted: Boolean,
    val confirmedProgress: Int?,
)

/** Drives the host's real task pages and trusts only server-rendered progress. */
internal class YuanbaoTaskAdapter(
    private val context: Context,
    private val openOfficialWelfareEntry: () -> Boolean = { false },
    private val openCachedWelfareEntry: () -> Boolean = { false },
) {
    @Volatile
    private var resumedActivity: Activity? = null
    /** 默认 false = 允许重复使用同一模板（最省事）；只有复核没涨进度才切 true。 */
    @Volatile
    private var templateVarietyRequired = false
    /** 最近一次进度快照里真正读到的任务项（见 [observedKinds]）。 */
    @Volatile
    private var observedKinds: Set<DailyTaskKind> = emptySet()
    private val main = Handler(Looper.getMainLooper())
    private val prompts = TaskPromptFactory()
    private val media = TaskMediaFactory(context)
    /** Keys returned by the accessibility layer for templates already used in this pass. */
    private val usedTemplateKeys = linkedSetOf<String>()
    /**
     * 本次运行内已经点过的「趣问元宝得积分」卡片（按卡片标题去重）。
     *
     * 2026-10 新版福利中心里，「问元宝」有两条入口：
     * 1. 每日任务区的「去提问」→ 进对话页，要自己写文案再按发送；
     * 2. 「趣问元宝得积分」板块的横排卡片「去提问」→ **点一下自动带问题提问**，
     *    既不用写文案也不用按发送键，比第 1 条少两步。
     *
     * 用户实测反馈第 2 条更省事，所以 ASK 优先走它。同一张卡重复点不算新的一次，
     * 因此按卡片标题去重；跨天清空。
     */
    private val usedQuizCards = linkedSetOf<String>()
    /** 本轮 ASK 是点趣问卡直接发起的（页面已自动提问，不需要再写文案）。 */
    @Volatile
    private var askAnsweredByQuizCard = false

    fun setResumedActivity(activity: Activity?) {
        resumedActivity = activity
    }

    fun clearResumedActivity(activity: Activity) {
        if (resumedActivity === activity) resumedActivity = null
    }

    fun cleanupGeneratedMedia(): Int = media.cleanupGeneratedMedia()

    /** 只清理超期素材，保留最近那张供复用。 */
    fun cleanupExpiredMedia(): Int = media.cleanupExpiredMedia()

    fun isWelfareCenterVisible(): Boolean {
        val text = readScreenText(logSample = false)
        return looksLikeWelfareCenter(text)
    }

    /** Reads the five server-rendered counters currently visible in 福利中心. */
    fun readVisibleProgress(): Map<DailyTaskKind, Int> {
        // 领奖弹窗也是"模态遮罩"：它盖住的那半边页面读不到，
        // 先点掉再读，否则读到的是残缺快照。
        dismissRewardDialog("progress")
        val first = readScreenText(logSample = false)
        if (!looksLikeWelfareCenter(first)) return emptyMap()
        var fromText = parseProgressText(first)
        // 接口数据先取：它一次给全五项，且是服务端口径。
        // 做同款那块文字里根本没有 x/3，只能靠这条。
        // 包一层 runCatching：接口/JS 出任何岔子都退回文字解析，不能把整次读进度拖挂。
        val fromApi = runCatching { readProgressViaBridge() }
            .onFailure { Log.e("progress api failed", it) }
            .getOrDefault(emptyMap())
        // **一屏放不下五项**（真机实测 @1272x2800）：福利中心的任务卡片是纵向列表，
        // 首屏只渲染到「使用写作能力」，P图 / 拍题 / 模板 三张卡片根本不在无障碍树里
        // （WebView 只把可视区的虚拟节点暴露给 a11y），`readProgress` 于是返回 null。
        //
        // 上层拿到 null 就当成"还没做"，结果是：这三项无论今天做过几次，都会被判成 0/3，
        // 每轮固定再提交 3 次，整轮结束还因为"五项没全完成"被判失败 → 调度器 WAIT_RETRY
        // 再来一整轮。真机实测 2026-10-21 一轮里 P图/拍题/模板 各重复提交 3 次，
        // 而服务端实际只认了 1 次。
        //
        // 所以缺项就往下滚，把每一屏的文本并起来再解析，最后把页面滚回顶部
        // （后续点 CTA 需要从任务区顶部开始找）。
        // 接口已经读到全五项就不用再滚动捞文字了（滚动是最慢的一步）。
        if (fromText.size < DailyTaskKind.entries.size &&
            fromApi.size < DailyTaskKind.entries.size
        ) {
            val merged = fillProgressByScrolling(first)
            if (merged.length > first.length) {
                fromText = parseProgressText(merged)
                Log.d(
                    "progress: 滚动补齐 ${first.length}→${merged.length} 字，" +
                        "读到=[${fromText.entries.joinToString(",") { "${it.key.id}=${it.value}" }}]",
                )
            }
        }
        observedKinds = fromText.keys.toSet()
        // 页面自己的接口数据优先（JS 钩子抓的 JSON），innerText 正则兜底。
        val sniffed = readSnifferJson()
        val fromSniffer = if (sniffed.isNullOrBlank()) {
            emptyMap()
        } else {
            DailyTaskKind.entries.mapNotNull { kind ->
                progressFromSniffer(sniffed, kind)?.let { completed -> kind to completed }
            }.toMap()
        }
        Log.d(
            "progress read text=[${fromText.entries.joinToString(",") { "${it.key.id}=${it.value}" }}] " +
                "sniffer=[${fromSniffer.entries.joinToString(",") { "${it.key.id}=${it.value}" }}]",
        )
        observedKinds = observedKinds + fromSniffer.keys + fromApi.keys
        // 合并顺序：文字 < 嗅探 < 接口。接口最后覆盖，以服务端口径为准。
        val merged = fromText + fromSniffer + fromApi
        publishProgressToModule(merged)
        return merged
    }

    /**
     * 最近一次进度快照里**真正读到**的任务项。
     *
     * 「没读到」和「没做完」在界面上长得一模一样（都是没有进度文字），
     * 但处理方式必须相反：前者说明这一项不在这屏，后者说明要去补做。
     * 上层据此判断整轮是否算成功，避免把读不到判成失败后无限重跑。
     */
    fun observedKinds(): Set<DailyTaskKind> = observedKinds

    fun perform(task: DailyTaskKind, attempt: Int): TaskActionResult {
        ensureAccessibilityReady("perform:${task.id}")
        // 每轮新的执行（attempt=1）重新点起：同一天二次运行也该能再点一遍卡片。
        if (task == DailyTaskKind.ASK && attempt <= 1) {
            usedQuizCards.clear()
            askAnsweredByQuizCard = false
        }
        // 免 root 版：元宝不在前台就绝不开始点（见 ensureHostForeground）。
        if (!ensureHostForeground("perform:${task.id}")) {
            Log.d("${task.title}: 元宝未就绪，本轮不执行（避免在别的应用上误触）")
            return TaskActionResult(false, null)
        }
        if (!ensureWelfareCenter() && !awaitManualWelfareCenter(20_000L)) {
            Log.d("${task.title}: welfare center not visible")
            return TaskActionResult(false, null)
        }

        // 上一次任务完成时弹的领奖框可能还盖着，先点掉再找 CTA。
        // 这里允许走 OCR：该弹窗整块不在无障碍树里，纯文本判断查不出来。
        dismissRewardDialog("perform:${task.id}", allowOcr = true)
        val before = readProgress(task)
        Log.d("${task.title}: host progress before=${before ?: "unknown"}")
        if (!clickDailyTask(task)) {
            Log.d("${task.title}: unique CTA ${task.cta()} not found ${surfaceDiagnostics()}")
            return TaskActionResult(false, null)
        }

        // **点完 CTA 要确认真的跳走了**（真机 2026-10-21 实证）。
        //
        // 福利中心是 WebView，点击到页面切换之间有几百毫秒空档。这段时间直接去填
        // 输入框，`fillText` 的每条通道都会落空 —— 日志里 ask 连续 3 次都是
        // `prompt: no text channel accepted`（activity 已经报首页，但输入框还没渲染），
        // 于是这一项 4 次重试全白跑。等页面真正离开福利中心，比在填字里盲目重试有效。
        if (BuildConfig.STANDALONE && looksLikeWelfareCenter(readScreenText(logSample = false))) {
            val left = awaitCondition(WELFARE_LEAVE_WAIT_MS) {
                !looksLikeWelfareCenter(readScreenText(logSample = false))
            }
            Log.d("${task.title}: 点 CTA 后离开福利中心=$left")
            if (!left && clickDailyTask(task)) {
                val retried = awaitCondition(WELFARE_LEAVE_WAIT_MS) {
                    !looksLikeWelfareCenter(readScreenText(logSample = false))
                }
                Log.d("${task.title}: 补点一次 CTA 后离开福利中心=$retried")
            }
        }

        pause(1_800L)
        val completedAction = performCapability(task, attempt)
        Log.d("${task.title}: capability completed=$completedAction")
        if (!completedAction) return TaskActionResult(false, null)

        pause(if (task == DailyTaskKind.ASK || task == DailyTaskKind.WRITING) 7_000L else 10_000L)
        val returned = returnToWelfareCenter()
        if (!returned) Log.d("${task.title}: submitted, but welfare page could not be reopened")
        pause(1_500L)
        // 提交成功后福利中心往往立刻弹「恭喜获得积分」，先收掉再读进度，
        // 否则读到的是被模态框遮住的残缺页面，进度会偏低。
        //
        // 这里**不跑 OCR**：紧接着的 readProgress 只读不点，弹窗留着不影响读数；
        // 真正的清理交给下一次 perform 的开头，一次 OCR 就够，不必每个环节都付这个成本。
        dismissRewardDialog("after:${task.id}")

        var after = if (returned) readProgress(task) else null
        // 读不到就再来一次：重新打开福利中心（force=true 会重拉服务端计数）后再读。
        var reread = 0
        while (after == null && reread < 2) {
            reread++
            Log.d("${task.title}: 进度读不到，重新打开福利中心再读（第 $reread 次）")
            openWelfareFromMineTab(force = true)
            pause(2_500L)
            after = readProgress(task)
        }
        // The value rendered by 福利中心 is authoritative even when it did
        // not increase during this pass (for example, it was already 3/3).
        val confirmed = after
        Log.d("${task.title}: host progress after=${after ?: "unknown"}, confirmed=$confirmed")
        return TaskActionResult(true, confirmed)
    }

    /**
     * 我们 → 福利中心：用户指定的查看/复核路线。
     *
     * `force = true` 会先切到“我们”再重新进福利中心，让页面重新拉一次服务端计数；
     * 复核时必须 force，否则看到的数字可能还是上一次渲染的结果。
     */
    fun openWelfareFromMineTab(force: Boolean = false): Boolean {
        // 这是"进元宝里干活"的唯一入口，免 root 版必须先确认元宝在前台 ——
        // 否则下面每一处 tap 都会落在别人家的界面上（真机实测过打在 KernelSU 上）。
        if (!ensureHostForeground("welfare")) return false
        if (!force && isWelfareCenterOpen()) {
            Log.d("navigation: 已在福利中心，无需重新进入")
            return true
        }
        Log.d("navigation: 打开福利中心 force=$force")
        // 自适应入口：旧版点底部“我们”；新版（2.87.20）走左上角抽屉 → 福利中心（纯节点驱动）。
        if (openWelfareEntryAdaptive() && awaitWelfareCenter(4_000L)) {
            Log.d("navigation: 福利中心已打开（自适应入口）")
            afterWelfareOpened()
            return true
        }
        // 自适应入口没成 → 先把自己**恢复到首页**，再走一遍。
        //
        // 这里刻意**不再保留** "点底部我们标签 / 点福利卡片位置" 这类写死比例坐标的兜底。
        // 原因（用户实测）：2.87.20 根本没有底部标签栏，那些坐标落在输入框与屏幕底边之间，
        // 任务做完以后用户看到的就是"点左上角抽屉 → 没进福利中心 → 开始乱点"。
        // 设计原则是"宁可不点，也不误触"，所以宁可这一轮放弃。
        Log.w("navigation: 自适应入口未打开福利中心（${surfaceHint()}）→ 回首页重试一次")
        if (ensureHomeSurface("welfare-retry") &&
            openWelfareEntryAdaptive() &&
            awaitWelfareCenter(8_000L)
        ) {
            Log.d("navigation: 福利中心已打开（回首页后重试）")
            afterWelfareOpened()
            return true
        }
        // 最后一招：只在**当前屏**上做语义匹配 —— 有节点才点，没有就不点。
        if (clickVisible(WELFARE_ALIASES, allowContains = true) && awaitWelfareCenter(6_000L)) {
            Log.d("navigation: 福利中心已打开（当前屏语义点击）")
            afterWelfareOpened()
            return true
        }
        Log.w("navigation: 福利中心入口未打开 → 放弃本轮（不做坐标盲点）${surfaceHint()}")
        return false
    }

    /** 进入福利中心后的统一处理：下滑找任务区 + 装页面接口钩子。 */
    private fun afterWelfareOpened() {
        scrollWelfareToTasks()
        installWelfareSniffer()
    }

    /**
     * 福利中心首屏往往看不到「每日任务」区，先往下滑让任务卡片加载/进入可视区。
     */
    private fun scrollWelfareToTasks() {
        repeat(MAX_WELFARE_SCROLLS) { index ->
            val text = readScreenText(logSample = false)
            if (WELFARE_TASK_MARKERS.any(text::contains)) {
                Log.d("navigation: 已看到任务区（第 ${index + 1} 次检查）")
                return
            }
            val scrolled = AutomationBridge.scrollForward(context)
            Log.d("navigation: 下滑找任务区 attempt=${index + 1} scrolled=$scrolled")
            if (!scrolled) return
            pause(1_200L)
        }
        Log.d("navigation: 下滑 ${MAX_WELFARE_SCROLLS} 次仍未识别到任务区")
    }

    /**
     * 关掉「恭喜获得 xxx 积分」的领奖弹窗。
     *
     * 每完成一次能力任务，福利中心都会弹这个模态框。它是**模态**的：
     * 盖住的那部分页面不进无障碍树，所以被遮住的「去拍题」「做同款」
     * 既看不见也点不到，而且往下滚也没用（弹窗不动）。
     *
     * 真机实测 2026-10-21：P图 第 2 次提交后弹窗盖住页面，紧接着
     * P图 第 3 次 + 拍题 3 次 + 模板 3 次全部 `unique CTA ... not found`，
     * 上一轮 9 次提交里有 6 次是这样空转的。
     *
     * 只点「开心收下」（点了等于收下积分并关闭），不猜右上角的 X。
     */
    private fun dismissRewardDialog(source: String, allowOcr: Boolean = false): Boolean {
        val text = readScreenText(logSample = false)
        if (SurfaceText.looksLikeRewardDialog(text)) {
            val clicked = clickWebTextExact(SurfaceText.REWARD_DISMISS_LABELS) ||
                AutomationBridge.clickText(
                    context,
                    SurfaceText.REWARD_DISMISS_LABELS,
                    allowContains = false,
                )
            Log.d("$source: 检测到领积分弹窗（文本可读）→ 点「开心收下」=$clicked")
            if (clicked) pause(REWARD_DISMISS_PAUSE_MS)
            return clicked
        }
        // 文本路径没命中，不代表没有弹窗：真机实测这个领奖框**整块不在无障碍树里**
        // （dump 里连"恭喜获得"四个字都没有），而它会把被遮住的卡片从 DOM 的
        // aria 树里摘掉 —— 于是"去拍题/做同款"凭空消失。这种情况只能截图 OCR。
        if (!allowOcr) return false
        return dismissRewardDialogByOcr(source)
    }

    /**
     * OCR 兜底：截图找「开心收下」并点它。
     *
     * 用户定的原则是"无障碍读不到字时才用 OCR，且只在盲区"，这里正是那个盲区：
     * 领奖弹窗对无障碍完全不可见，但它是模态的，不点掉后面所有任务都做不了。
     *
     * 落点直接取 OCR 返回的 bounding box 中心，**不写死任何像素或比例** ——
     * 换分辨率时 box 是当场量出来的，天然自适应。
     */
    private fun dismissRewardDialogByOcr(source: String): Boolean {
        val result = runCatching { AutomationBridge.ocrRead(context) }
            .onFailure { Log.e("$source: OCR 调用失败", it) }
            .getOrNull()
        if (result == null || !result.ok) {
            Log.d("$source: OCR 不可用（reason=${result?.reason ?: "null"}）→ 无法兜底")
            return false
        }
        val target = result.items.firstOrNull { item ->
            val compact = item.text.replace(" ", "")
            SurfaceText.REWARD_DISMISS_LABELS.any { compact.contains(it) }
        }
        if (target == null) {
            Log.d("$source: OCR 扫了 ${result.items.size} 条，没有「开心收下」")
            return false
        }
        if (target.right <= target.left || target.bottom <= target.top) {
            Log.d("$source: OCR 命中但 box 非法 ${target.left},${target.top},${target.right},${target.bottom}")
            return false
        }
        val x = (target.left + target.right) / 2
        val y = (target.top + target.bottom) / 2
        val width = AutomationBridge.screenWidth(context)
        val height = AutomationBridge.screenHeight(context)
        if (width <= 0 || height <= 0) return false
        Log.d(
            "$source: OCR 命中「${target.text}」box=" +
                "${target.left},${target.top},${target.right},${target.bottom} → 点 $x,$y（屏 ${width}x$height）",
        )
        val tapped = AutomationBridge.clickPointFraction(
            context,
            (x.toFloat() / width).coerceIn(0.02f, 0.98f),
            (y.toFloat() / height).coerceIn(0.02f, 0.98f),
        )
        Log.d("$source: 点「开心收下」tapped=$tapped")
        if (tapped) pause(REWARD_DISMISS_PAUSE_MS)
        return tapped
    }

    /** 按标题切段解析五项进度；读不到的项不会出现在结果里。 */
    private fun parseProgressText(text: String): Map<DailyTaskKind, Int> =
        DailyTaskKind.entries.mapNotNull { kind ->
            readProgress(kind, text)?.let { completed -> kind to completed }
        }.toMap()

    /**
     * 逐屏下滑，把福利中心的任务卡片全部读进一段合并文本。
     *
     * 为什么必须这么做：任务区是纵向列表，一屏只能放下 2~3 张卡片，屏幕外的
     * 卡片在无障碍树里**不存在**。只读当前这一屏，永远读不到 P图 / 拍题 / 模板
     * 的「已完成 x/3」，上层就会把它们当成一次都没做。
     *
     * 停止条件用「这一屏没有带来任何新文本」而不是「滚了 N 次」—— 页面短、
     * 已经到底、或者滚动根本没生效时都能立刻收手，不会在固定 4 次里白等。
     */
    private fun fillProgressByScrolling(seed: String): String {
        var merged = seed
        for (step in 1..MAX_PROGRESS_SWIPES) {
            if (!AutomationBridge.scrollForward(context)) {
                Log.d("progress: 第 $step 次下滑未被接受 → 停止补齐")
                break
            }
            pause(PROGRESS_SWIPE_PAUSE_MS)
            val next = readScreenText(logSample = false)
            if (next.isBlank() || merged.contains(next)) {
                Log.d("progress: 第 $step 次下滑没有新文本 → 已到底")
                break
            }
            merged = merged + "\n" + next
            if (parseProgressText(merged).size == DailyTaskKind.entries.size) {
                Log.d("progress: 下滑 $step 屏后五项齐全")
                break
            }
        }
        restoreWelfareTop()
        return merged
    }

    /**
     * 把福利中心滚回顶部（任务区之前的「兑换商城」「每日问元宝得积分」就是顶部标志）。
     *
     * 必须有这一步：读进度会把页面滚到底，紧接着要点 CTA，而 CTA 是从上往下找的。
     * 页面停在底部时第 1、2 张卡片反而滚出了可视区，点击就会失败。
     */
    private fun restoreWelfareTop() {
        repeat(MAX_PROGRESS_SWIPES + 2) {
            if (!AutomationBridge.scrollBackward(context)) return
            pause(PROGRESS_RESTORE_PAUSE_MS)
            val text = readScreenText(logSample = false)
            if (WELFARE_TOP_MARKERS.any(text::contains)) {
                Log.d("progress: 已回到福利中心顶部")
                return
            }
        }
        Log.d("progress: 回顶未确认（继续，由 CTA 查找自己滚动兜底）")
    }

    /**
     * 在福利中心页面里装 JS 钩子，直接抓这页自己的接口返回。
     *
     * 实测这页的 `__NEXT_DATA__` 是空壳（`pageProps:{}`，客户端渲染 + 随后 XHR），
     * 所以任务完成度最可靠的来源是页面请求到的 JSON：钩子抓 fetch/XHR 的响应体，
     * 命中任务相关 URL 的存进 `window.__ybdHooked`，Kotlin 侧再读取解析。
     */
    private fun installWelfareSniffer() {
        val activity = resumedActivity ?: return
        val web = onMain { findWebView(activity.window?.decorView) } ?: return
        val installed = evaluateJavascript(web, SNIFFER_INSTALL_SCRIPT)
        Log.d("welfare sniffer install=${installed?.take(40).orEmpty()}")
        val captured = welfareSniffedJson(web)
        if (captured.isNullOrBlank()) {
            // 钩子由 WebViewClient.onPageStarted 在页面脚本之前注入，
            // 所以这里只等页面自己的请求回来即可（刷新页面反而会丢掉当前钩子）。
            pause(2_500L)
            Log.d("welfare sniffer after wait chars=${welfareSniffedJson(web)?.length ?: 0}")
        } else {
            Log.d("welfare sniffer chars=${captured.length}")
        }
    }

    private fun readSnifferJson(): String? {
        val activity = resumedActivity ?: return null
        val web = onMain { findWebView(activity.window?.decorView) } ?: return null
        return welfareSniffedJson(web)
    }

    /**
     * **直接调接口读进度**（2026-11-14 新增，见 evidence 与 YuanbaoApi/NOTES.md）。
     *
     * 为什么要它：页面文字里「做同款」那块**没有 x/3 数字**，只有「已领取」徽标，
     * 靠 innerText 猜必然出错；而页面自己的接口 `activity_center/get-current-tasks`
     * 一次就返回五项任务的 current/target，和界面完全一致。
     *
     * 做法：在页面里发一次桥请求，把结果塞进一个隐藏节点，再同步轮询读回来。
     * 不用 `addJavascriptInterface`，是因为宿主 WebView 已经挂过别的桥，避免撞名。
     */
    private fun readProgressViaBridge(): Map<DailyTaskKind, Int> {
        val activity = resumedActivity
        if (activity == null) {
            Log.d("progress api: no activity")
            return emptyMap()
        }
        val web = onMain { findWebView(activity.window?.decorView) }
        if (web == null) {
            Log.d("progress api: no webview")
            return emptyMap()
        }
        evaluateJavascript(web, BRIDGE_PROGRESS_FETCH_JS)
        val deadline = SystemClock.uptimeMillis() + BRIDGE_PROGRESS_TIMEOUT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            val raw = evaluateJavascript(web, BRIDGE_PROGRESS_READ_JS)
                ?.let(::decodeJavascriptString)?.trim().orEmpty()
            when {
                raw.startsWith("B64:") -> {
                    val json = runCatching {
                        String(
                            Base64.decode(raw.removePrefix("B64:"), Base64.DEFAULT),
                            StandardCharsets.UTF_8,
                        )
                    }.getOrNull() ?: return emptyMap()
                    val parsed = parseBridgeProgress(json)
                    Log.d(
                        "progress api: " + parsed.entries.joinToString(",") { "${it.key.id}=${it.value}" },
                    )
                    return parsed
                }
                raw == "PENDING" || raw.isEmpty() -> SystemClock.sleep(150)
                else -> {
                    // NOBRIDGE / FAIL / ERR：说明这一版页面没有桥或请求失败，
                    // 退回文字解析，不在这里死等。
                    Log.d("progress api unavailable: $raw")
                    return emptyMap()
                }
            }
        }
        Log.d("progress api: timeout")
        return emptyMap()
    }

    /**
     * 把进度推给模块 App 的界面。
     *
     * 原来的 SharedPreferences Provider 通道实测没落盘（模块 shared_prefs 一直是空的），
     * 界面因此永远显示 0/3；广播是系统通道，不依赖两边的进程是否活着，最稳。
     */
    private fun publishProgressToModule(progress: Map<DailyTaskKind, Int>) {
        if (progress.isEmpty()) return
        // 免 root 版本来就在自己进程里，写本地即可。
        if (context.packageName == BuildConfig.APPLICATION_ID) return
        runCatching {
            val day = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date())
            val intent = Intent(ProgressSyncReceiver.ACTION_PROGRESS_SYNC)
                .setPackage(BuildConfig.APPLICATION_ID)
            intent.putExtra(ProgressSyncReceiver.EXTRA_DAY, day)
            progress.forEach { (kind, value) -> intent.putExtra(kind.id, value) }
            context.sendBroadcast(intent)
            Log.d("progress broadcast day=$day items=${progress.size}")
        }.onFailure { Log.e("progress broadcast failed", it) }
    }

    /** 解析 get-current-tasks 的返回：外层 {data:"<json 字符串>"}，要解两层。 */    private fun parseBridgeProgress(payload: String): Map<DailyTaskKind, Int> {
        val outer = runCatching { JSONObject(payload) }.getOrNull() ?: return emptyMap()
        val inner = runCatching { JSONObject(outer.optString("data")) }.getOrNull() ?: return emptyMap()
        val activities = inner.optJSONObject("data")?.optJSONArray("activities") ?: return emptyMap()
        val out = LinkedHashMap<DailyTaskKind, Int>()
        for (i in 0 until activities.length()) {
            val act = activities.optJSONObject(i) ?: continue
            val activityId = act.optString("activity_id")
            val tasks = act.optJSONArray("task_list") ?: continue
            for (j in 0 until tasks.length()) {
                val task = tasks.optJSONObject(j) ?: continue
                val title = task.optJSONObject("basic_info")?.optString("title").orEmpty()
                val condition = task.optJSONObject("condition_info") ?: continue
                val kind = when {
                    title.contains("写作") -> DailyTaskKind.WRITING
                    title.contains("拍题") -> DailyTaskKind.PROBLEM
                    title.contains("P图") -> DailyTaskKind.PIC
                    title.contains("同款") || title.contains("模版") || title.contains("模板") ->
                        DailyTaskKind.TEMPLATE
                    activityId.endsWith("d78a15") -> DailyTaskKind.TEMPLATE
                    else -> null
                } ?: continue
                val completed = condition.optInt("current_counter", -1)
                if (completed in 0..MAX_TASK_COUNTER && !out.containsKey(kind)) {
                    out[kind] = completed
                }
            }
        }
        return out
    }

    private fun welfareSniffedJson(web: View): String? {
        val raw = evaluateJavascript(web, SNIFFER_READ_SCRIPT) ?: return null
        val decoded = decodeJavascriptString(raw)
        if (decoded.isBlank() || decoded == "[]") return null
        Log.d("welfare sniffer sample=${decoded.replace('\n', ' ').take(400)}")
        return decoded
    }

    /** 从抓到的接口 JSON 里按任务标题就近找完成度。 */
    private fun progressFromSniffer(sniffed: String, kind: DailyTaskKind): Int? {
        val anchor = sniffed.indexOf(kind.title).takeIf { it >= 0 } ?: return null
        val window = sniffed.substring(anchor, (anchor + 600).coerceAtMost(sniffed.length))
        PROGRESS_NEAR_TITLE.find(window)?.let { match ->
            val completed = match.groupValues[1].toIntOrNull()
            val required = match.groupValues[2].toIntOrNull()
            if (completed != null && required != null && required in 1..20 && completed in 0..required) {
                return completed
            }
        }
        COUNT_FIELD_REGEX.find(window)?.let { match ->
            val completed = match.groupValues[1].toIntOrNull()
            val required = match.groupValues[2].toIntOrNull()
            if (completed != null && required != null && required in 1..20 && completed in 0..required) {
                return completed
            }
        }
        return null
    }

    /**
     * 确保进到福利中心。
     *
     * **全程节点驱动**：每一步要么点一个真实存在的节点，要么放弃。
     * 老版本这里有一串 `tap(MINE_X, MINE_Y)` / `tap(WELFARE_X, WELFARE_Y)` /
     * `rootTapRelative(...)` 的比例坐标兜底，在 2.87.20 上全部落空 ——
     * 那正是用户反馈的"乱点"来源，已全部删除。
     */
    private fun ensureWelfareCenter(): Boolean {
        if (welfareCenterVisible()) {
            Log.d("navigation: 福利中心已在前台")
            return true
        }
        if (openCachedWelfare() && awaitWelfareCenter(6_000L)) {
            Log.d("navigation: 已通过缓存的官方福利入口打开")
            return true
        }
        // 当前屏上就有"福利中心"节点 → 直接点它（语义匹配，不是坐标）
        if (clickVisible(WELFARE_ALIASES, allowContains = true) && awaitWelfareCenter(7_000L)) {
            Log.d("navigation: 已点当前屏的福利中心卡片")
            return true
        }
        // 宿主自己的 ViewModel 路线（只有 root 版拿得到）
        if (onMain { openOfficialWelfareEntry() } == true && awaitWelfareCenter(7_000L)) {
            Log.d("navigation: 已通过官方福利入口打开")
            return true
        }
        // 标准路线：回首页 → 抽屉 → 福利中心
        if (ensureHomeSurface("ensure-welfare") && openWelfareByDrawer() && awaitWelfareCenter(8_000L)) {
            afterWelfareOpened()
            return true
        }
        Log.w("navigation: 福利中心打不开 → 放弃（不做坐标盲点）${surfaceHint()}")
        return false
    }

    private fun returnToWelfareCenter(): Boolean {
        if (looksLikeWelfareCenter(readScreenText(logSample = false))) return true
        if (openCachedWelfare() && awaitWelfareCenter(6_000L)) return true
        // 用“我们 → 福利中心”代替连按返回键：从对话页连按 3 次 BACK 会落到元宝主界面，
        // 触发“再按一次退出元宝”（用户实测反馈）。
        if (openWelfareFromMineTab(force = true)) return true
        // 兜底最多按一次返回，且不再连续按。
        val backed = backInHost()
        Log.d("navigation: fallback single back dispatched=$backed")
        return backed && awaitWelfareCenter(3_000L)
    }

    private fun openCachedWelfare(): Boolean = onMain { openCachedWelfareEntry() } == true

    private fun awaitManualWelfareCenter(timeoutMs: Long): Boolean {
        Log.d("navigation: waiting for manual welfare center entry")
        val visible = awaitCondition(timeoutMs) {
            looksLikeWelfareCenter(readScreenText(logSample = false))
        }
        Log.d("navigation: manual welfare center visible=$visible")
        return visible
    }

    private fun awaitWelfareCenter(timeoutMs: Long): Boolean = awaitCondition(timeoutMs) {
        welfareCenterVisible()
    }

    /**
     * 福利中心是否已经打开（两个判据任一成立）。
     *
     * 1. **Activity 名就是 `WelfareWebActivity`** —— 最可靠，页面还在加载（WebView 白屏）时也成立；
     * 2. 屏幕文本里同时出现两项任务名和 `n/m` 计数。
     *
     * 为什么必须加第 1 条：福利中心是 WebView，从点到首屏文字渲染出来经常超过 6 秒。
     * 只靠文本判据会在"其实已经进去了"的情况下超时，然后掉进后面的兜底分支继续乱点 ——
     * 用户看到的"点了抽屉进不去福利中心，然后瞎点"就是这个。
     */
    private fun welfareCenterVisible(): Boolean {
        if (WelfareNav.isWelfareActivity(currentActivityName())) return true
        return looksLikeWelfareCenter(readScreenText(logSample = false))
    }

    /**
     * 当前 Activity 名。
     *
     * root 版直接从注入的宿主拿（零成本）；免 root 版没有宿主对象，
     * 只好从无障碍快照里读（一次 binder 调用）。
     */
    private fun currentActivityName(): String {
        resumedActivity?.javaClass?.name?.let { return it }
        return runCatching { AutomationBridge.snapshot(context).activity }.getOrDefault("")
    }

    /** 一句话说明"现在在哪一屏"，只用于日志。 */
    private fun surfaceHint(): String {
        val text = readScreenText(logSample = false)
        val activity = currentActivityName()
        val where = when {
            // **Activity 优先**：只在文本上判断会把"首页渲染中"说成"其它页"，
            // 真机日志里出现过 `at=其它页 activity=…YBHomeActivityV2` 这种自相矛盾的行，
            // 排查时会被带偏。
            SurfaceText.isHomeActivity(activity) -> "首页"
            SurfaceText.isWelfareActivity(activity) -> "福利中心"
            SurfaceText.looksLikeHome(text) -> "首页"
            SurfaceText.looksLikeCapability(text) -> "能力页"
            currentDrawerOpen() -> "侧栏开着"
            else -> "其它页"
        }
        return "at=$where activity=${activity.ifBlank { "none" }}"
    }

    private fun isDedicatedWelfareActivity(name: String): Boolean =
        name.endsWith(".WelfareWebActivity") ||
            name.endsWith(".CampaignActivity") ||
            name.endsWith(".WebBrowserActivity")

    private fun looksLikeWelfareCenter(text: String): Boolean {
        val matchedTasks = DailyTaskKind.entries.count { it.matches(text) }
        return matchedTasks >= 2 && Regex("\\d+\\s*/\\s*\\d+").containsMatchIn(text)
    }

    private fun looksLikeMinePage(text: String): Boolean {
        // The home page itself contains a welfare card and the bottom-tab label,
        // so "福利中心" alone is not evidence that the tab changed.
        if (text.contains("临时对话模式") || text.contains("相机拍摄入口")) return false
        return text.contains("个性化") || text.contains("主题色") ||
            text.contains("我的 Bot") || text.contains("我的Bot") ||
            text.contains("备了福利") || text.contains("我的收藏") ||
            text.contains("账号与安全")
    }

    /**
     * 福利中心里的"干扰按钮"：**绝不能点**（用户实测误触过"去邀请"）。
     * 页面上一旦出现这些词，就只用精确匹配，并放弃位置兜底。
     */
    private val CTA_DISTRACTORS = listOf("邀请", "分享", "助力", "接受", "领取新人", "签到有礼")

    private fun clickDailyTask(task: DailyTaskKind): Boolean {
        // 问元宝优先走「趣问元宝得积分」的横排卡片：点一下页面自己带问题发出去，
        // 不用写文案、不用按发送键，比每日任务区那条 CTA 少两步。
        if (task == DailyTaskKind.ASK) {
            // 每轮都先归零：卡片点完了（all-used）就要老老实实回退到"写文案发送"，
            // 不能留着上一轮的 true，否则会跳过发送、白跑一轮。
            askAnsweredByQuizCard = false
            if (clickQuizAskCard()) return true
        }
        val cta = task.cta()
        if (tryClickCta(task, cta)) return true
        // **卡片可能在屏幕外**：福利中心任务区一屏只放得下 2~3 张卡片，
        // P图 / 拍题 / 模板 默认都在首屏以下，它们的「去P图 / 去拍题 / 做同款」
        // 根本不在无障碍树里。真机实测：不滚动时这三项的 CTA 永远找不到，
        // 每项都白跑 3 次（日志 `unique CTA 去拍题 not found`）。
        //
        // 逐屏下滑再找，仍然只认精确文字 —— 福利中心的「去邀请」和任务 CTA 同屏，
        // 位置兜底必误触，所以宁可找不到也不猜坐标。
        for (step in 1..MAX_CTA_SWIPES) {
            if (!AutomationBridge.scrollForward(context)) break
            pause(PROGRESS_SWIPE_PAUSE_MS)
            if (tryClickCta(task, cta)) {
                Log.d("${task.title}: 下滑 $step 屏后找到 CTA($cta)")
                return true
            }
        }
        // **最后一层兜底**：领奖弹窗。它对无障碍不可见，却会把被遮住的卡片
        // 从 aria 树里摘掉 —— 表现就是"滚也滚不到、找也找不到"。
        // 只有在 CTA 彻底找不到时才走 OCR，正常路径一次都不会跑。
        if (dismissRewardDialog("${task.id}:cta-fallback", allowOcr = true)) {
            if (tryClickCta(task, cta)) {
                Log.d("${task.title}: 点掉领奖弹窗后找到 CTA($cta)")
                return true
            }
        }
        Log.d(
            "${task.title}: 下滑 ${MAX_CTA_SWIPES} 屏仍未找到 CTA($cta) → " +
                "不做位置兜底（避免误触去邀请）",
        )
        return false
    }

    /**
     * 点「趣问元宝得积分」里的横排「去提问」卡片（2026-10 新版福利中心）。
     *
     * 为什么值得单开一条通道：每日任务区的「去提问」进的是空白对话页，必须写文案、
     * 填输入框、再按发送；而趣问卡点一下，页面自己就把问题发出去了 —— 少两步，
     * 也不依赖那个在真机上最脆弱的 Compose 输入框。
     *
     * 识别方式：**同一行出现两个以上「去提问」才算趣问卡**。每日任务区的「去提问」
     * 是列表行里的单个按钮，永远不会和别的一行并排，用这条区分不会误点到它。
     * 返回 true 表示已经点出去（[askAnsweredByQuizCard] 随之置位，跳过后续发送）。
     */
    private fun clickQuizAskCard(): Boolean {
        val activity = resumedActivity ?: return false
        val webView = onMain { findWebView(activity.window?.decorView) } ?: return false
        val usedJson = usedQuizCards.joinToString(",", "[", "]") { jsQuote(it) }
        val result = evaluateJavascript(
            webView,
            """
            (function(){
              var used=$usedJson;
              var all=Array.prototype.slice.call(document.querySelectorAll('*'));
              var items=[];
              for(var i=0;i<all.length;i++){
                var e=all[i], t=(e.innerText||e.textContent||'').trim();
                if(t!=='去提问') continue;
                if(e.getClientRects().length===0) continue;
                var r=e.getBoundingClientRect();
                if(r.width<=0||r.height<=0) continue;
                var child=false;
                for(var c=0;c<e.children.length;c++){
                  var ct=(e.children[c].innerText||e.children[c].textContent||'').trim();
                  if(ct==='去提问'){child=true;break;}
                }
                if(child) continue;
                items.push({el:e,top:r.top,left:r.left});
              }
              if(!items.length) return 'none';
              items.sort(function(a,b){return a.top-b.top;});
              var groups=[],cur=[items[0]];
              for(var j=1;j<items.length;j++){
                if(Math.abs(items[j].top-cur[0].top)<=28) cur.push(items[j]);
                else { groups.push(cur); cur=[items[j]]; }
              }
              groups.push(cur);
              var g=null;
              for(var k=0;k<groups.length;k++){
                if(groups[k].length>=2){ g=groups[k]; break; }
              }
              if(!g) return 'no-group';
              g.sort(function(a,b){return a.left-b.left;});
              for(var m=0;m<g.length;m++){
                var el=g[m].el, card=el.parentElement, text='';
                for(var up=0;up<3&&card;up++){
                  text=(card.innerText||card.textContent||'').replace(/去提问/g,'').replace(/\s+/g,' ').trim();
                  if(text.length>=6) break;
                  card=card.parentElement;
                }
                var key=text.slice(0,24);
                if(used.indexOf(key)>=0) continue;
                var btn=el.closest('button,a,[role="button"],[onclick]')||el;
                btn.click();
                return 'clicked:'+key;
              }
              return 'all-used';
            })()
            """.trimIndent(),
        )
        if (result?.contains("clicked:") != true) {
            Log.d("ask: 趣问卡未点击（${result?.take(80)}）→ 回退每日任务区 CTA")
            return false
        }
        val title = result.substringAfter("clicked:").trim().trim('"').take(40)
        if (title.isNotEmpty()) usedQuizCards.add(title)
        askAnsweredByQuizCard = true
        Log.d("ask: 趣问卡 clicked=[$title] 累计 ${usedQuizCards.size} 张")
        return true
    }

    /** 在当前这一屏上按**精确文字**点 CTA（web 精确 + 无障碍精确）。 */
    private fun tryClickCta(task: DailyTaskKind, cta: String): Boolean {
        if (clickWebTextExact(listOf(cta))) {
            Log.d("${task.title}: web CTA clicked=$cta")
            return true
        }
        // 精确匹配（不放宽 contains）——宽松匹配会误触"去邀请"这类按钮。
        if (AutomationBridge.clickText(context, listOf(cta), allowContains = false)) {
            Log.d("${task.title}: accessibility CTA clicked(exact)=$cta")
            return true
        }
        val distractors = CTA_DISTRACTORS.filter { readScreenText(logSample = false).contains(it) }
        if (distractors.isNotEmpty()) {
            Log.d("${task.title}: 本屏有干扰按钮 $distractors（只做精确匹配，不受影响）")
        }
        return false
    }

    private fun DailyTaskKind.cta(): String = when (this) {
        DailyTaskKind.ASK -> "去提问"
        DailyTaskKind.WRITING -> "去写作"
        DailyTaskKind.PIC -> "去P图"
        DailyTaskKind.PROBLEM -> "去拍题"
        DailyTaskKind.TEMPLATE -> "做同款"
    }

    /**
     * 自检入口：在没有 CTA（当日任务已完成）时也能单独验证最容易坏的两条链路。
     */
    internal fun selfTest(mode: String): String {
        // 自检本身就是用来查"为什么定位不到输入框"的，所以先确保无障碍这一层是活的，
        // 否则所有自检都会以同一个原因失败，反而掩盖真正的差异。
        ensureAccessibilityReady("selfTest:$mode")
        // 这几项只读本机状态、不碰元宝界面，不需要先把元宝拉到前台。
        val pureLocal = mode.lowercase() in setOf("health", "状态", "repair", "自愈", "gen", "素材", "ocr", "识别")
        if (!pureLocal && !ensureHostForeground("selfTest:$mode")) {
            val report = "host_not_foreground（免root版：未把元宝拉到前台，已放弃，避免误触）"
            Log.w("self test mode=$mode summary=$report")
            return report
        }
        return selfTestDispatch(mode)
    }

    private fun selfTestDispatch(mode: String): String = when (mode.lowercase()) {
        "media", "pic" -> selfTestMediaFlow(DailyTaskKind.PIC)
        "problem" -> selfTestMediaFlow(DailyTaskKind.PROBLEM)
        "template" -> selfTestTemplateFlow()
        "send" -> selfTestSend()
        "scroll" -> selfTestScroll()
        "welfare" -> selfTestWelfare()
        "picpicker" -> selfTestOpenPicPicker()
        "confirm" -> {
            // 只点"框选确认"（用于停在该屏时精准验证，不重新生成/导航）。
            val ok = confirmProblemCrop()
            Log.w("self test confirm result=$ok")
            "confirm=$ok"
        }
        "gen", "素材" -> selfTestGenerateMedia()
        "ocr", "识别" -> selfTestOcr()
        "compat" -> selfTestCompat()
        "health", "状态" -> selfTestHealth()
        "repair", "自愈" -> selfTestRepair()
        "geometry", "geom" -> selfTestComposerGeometry()
        "focus" -> selfTestFocusComposer()
        "composer", "probe" -> selfTestComposerProbe()
        "fill" -> selfTestFillComposer()
        "drawer", "nav" -> selfTestDrawerNavigation()
        "all" -> selfTestAll()
        "ask" -> selfTestAsk()
        "writing" -> selfTestWriting()
        else -> "unknown mode=$mode"
    }

    /**
     * **确认元宝在前台**，不在就先拉起来；拉不起来就**不许动手**。
     *
     * 这一条是免 root 版和 root 版最大的行为差异，也是真机踩出来的：
     *
     * root 版的调度器跑在**元宝自己的进程里**，所以"调度器活着"本身就意味着
     * 元宝活着 —— 前台这件事是**隐含保证**的，从来不需要检查。
     *
     * 免 root 版的调度器跑在自己的进程里，无障碍服务一绑上就会起，而那时
     * 前台可能是桌面、可能是别的 App。真机实测：第一次启动时它按日程判定
     * "今天还没跑"就直接开工，在 **KernelSU 的界面上**盲点了一下
     * （日志 `TAPTRACE #4 action=tap xy=1017,1145`）—— 这正是用户最讨厌的误触。
     *
     * 所以免 root 版在每一次 [perform] / 自检之前，都必须先过这道门。
     * root 版直接返回 true，行为一个字不变。
     */
    private fun ensureHostForeground(source: String): Boolean {
        if (!BuildConfig.STANDALONE) return true
        // **必须用 isHostForeground()，不能用 hostWindowVisible()。**
        //
        // 两者差一个层次：前者看"活动窗口是不是元宝"，后者只看"窗口列表里有没有元宝"。
        // 免 root 版的界面在前台时，元宝窗口仍然留在列表里 —— 用后者会误判成
        // "元宝已在前台"，于是不拉起、不等待，直接往下走，然后死在第一步导航上。
        if (isHostForeground()) return true
        Log.w("$source: 元宝不在前台 → 先拉起元宝，未就绪前不做任何点击")
        launchHostPackage()
        val deadline = SystemClock.uptimeMillis() + HOST_LAUNCH_WAIT_MS
        var lastGuardAttemptAt = 0L
        while (SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(500L)
            if (isHostForeground()) {
                Log.w("$source: 元宝已到前台，继续")
                return true
            }
            // 等待期间如果在等的是**厂商安全提示**，它不会自己消失 —— 顺手点掉。
            //
            // 为什么放在这里而不是启动时一次性检查：这个弹窗出现得比 launchHostPackage
            // 返回还晚（元宝起来之后系统才弹），所以必须在等待循环里持续观察。
            // 每 4 秒最多试一次，避免在弹窗上来回扫。
            val now = SystemClock.uptimeMillis()
            if (now - lastGuardAttemptAt >= GUARD_RETRY_GAP_MS) {
                lastGuardAttemptAt = now
                if (tryDismissSystemGuard("$source-guard")) {
                    // 点掉后再给它一点时间切回元宝，然后继续原来的循环。
                    SystemClock.sleep(800L)
                }
            }
        }
        Log.w("$source: ${HOST_LAUNCH_WAIT_MS}ms 内没能把元宝拉到前台 → 放弃本轮（不做盲点）")
        return false
    }

    /**
     * 若当前最上层是**厂商安全中心**，尝试点它的"保持开启"。
     *
     * 只在最上层包名命中白名单时才发起，避免每次都去遍历别人的窗口树。
     * 返回 true 表示确实点成功了。
     */
    private fun tryDismissSystemGuard(source: String): Boolean {
        val snapshot = runCatching { AutomationBridge.snapshot(context) }.getOrNull() ?: return false
        val top = WindowStackPolicy.topPackage(snapshot.windows)
        if (!SystemGuardPolicy.isGuardPackage(top)) return false
        Log.w("$source: 最上层是 $top（厂商安全提示）→ 尝试点“保持开启”")
        val result = runCatching { AutomationBridge.dismissSystemGuard(context) }.getOrNull()
        if (result == null) {
            Log.w("$source: 安全提示处理通道不可达")
            return false
        }
        if (result.clicked) {
            Log.w("$source: 已点“${result.label}”，系统无障碍提示已关闭")
            return true
        }
        if (result.seen) {
            // 看到了弹窗但没找到安全的按钮 —— 绝不按坐标兜底，交给人工。
            Log.w("$source: 安全提示在，但没找到安全的保留按钮 → 不点，需要人工处理一次")
        }
        return false
    }

    /**
     * 元宝当前是否在无障碍树里（即"能用眼睛看到"）。
     *
     * 供 [DailyTaskRunner] 判断"现在能不能干活"。root 版恒为 true ——
     * 它的调度器本来就跑在元宝进程里，不存在"元宝不在前台"这种状态。
     */
    fun isHostForeground(): Boolean {
        // 注意 root 版**不能**直接返回 true。
        //
        // "宿主进程活着"和"元宝在前台"是两件事：用户切到微信/QQ 时，元宝进程照样活着，
        // 但屏幕上根本不是元宝。此时若放行，后面每一处 tap 都会打在别人的界面上。
        // （免 root 版当初就是在 KernelSU 界面上盲点了一次。）
        if (resumedActivity != null) return true

        // 免 root 版：必须看**活动窗口**是不是元宝，而不是"窗口列表里有没有元宝"。
        //
        // 这里踩过一个坑（1.4.2 自检实证）：lite 自己的界面在前台时，元宝的窗口
        // **仍然留在无障碍树里**（系统不销毁后台窗口），于是
        // `hostWindowVisible()`（判据是"存在元宝窗口"）返回 true，
        // 自检就以为"元宝已在前台"、跳过了拉起这一步；
        // 紧接着 `ensureHomeSurface` 发现当前 Activity 是 lite 自己、判成"其它页"，
        // 而 `backInHost` 又因为"不在元宝前台"拒绝按返回 ——
        // 结果是一整轮自检**一个点击都没发**就退出了（日志：`Create=false`、taps=0）。
        //
        // 判据用**窗口栈最上层**，不是 `snapshot.packageName`。
        //
        // 后者取的是第一个窗口的包名，而 `com.android.systemui` 常常排在前面 ——
        // 元宝明明在前台，判据却说不是（1.4.2 自检实证：元宝已到前台仍报
        // "20000ms 内没能把元宝拉到前台"）。
        // 详见 [WindowStackPolicy.topPackage] 的注释。
        val snapshot = runCatching { AutomationBridge.snapshot(context) }.getOrNull()
            ?: return hostWindowVisible()
        val top = WindowStackPolicy.topPackage(snapshot.windows)
        if (top.isBlank()) {
            // 窗口列表读不到（老版本服务/异常返回）：退回"窗口存在"这个较宽的判据，
            // 但至少**排除**我们自己盖在元宝上面的情况。
            return hostWindowVisible() &&
                !WindowStackPolicy.isSelfOnTop(snapshot.windows, BuildConfig.APPLICATION_ID)
        }
        if (WindowStackPolicy.isSelfOnTop(snapshot.windows, BuildConfig.APPLICATION_ID)) return false
        return top == TARGET_PACKAGE
    }

    /** 无障碍树里当前能不能看到元宝的窗口。 */
    private fun hostWindowVisible(): Boolean = runCatching {
        AutomationBridge.uiNodes().any { it.packageName == TARGET_PACKAGE }
    }.getOrDefault(false)

    private fun launchHostPackage(): Boolean = runCatching {
        val launch = context.packageManager.getLaunchIntentForPackage(TARGET_PACKAGE)
            ?: return@runCatching false
        // 由模块自己拉起元宝时打上 arming，允许这一轮自动执行。
        runCatching { me.codex.yuanbaodaily.config.YuanbaoSharedStore.armAutoPass(context) }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        context.startActivity(launch)
        true
    }.onFailure { Log.e("launch host package failed", it) }.getOrDefault(false)

    /**
     * **开工前把无障碍这层拉起来**，干不了活的时候先尝试自愈一次。
     *
     * 为什么要有它：无障碍服务是整个自动化唯一的眼睛和手 ——
     * 它掉线时**每一个**任务都会以同一个原因失败（"定位不到输入框"），
     * 而从用户角度看就是"模块坏了"。
     *
     * 两种情形要分开：
     * - **模块进程还活着、只是服务没绑上** → `METHOD_A11Y_REPAIR` 通道可用，
     *   直接在模块进程里重绑（见 [me.codex.yuanbaodaily.automation.AccessibilityRepair]）。
     * - **模块进程整个死了** → 宿主**没法**把它叫起来（宿主没有模块的包可见性，
     *   provider / bindService 全部失败，实测 `transport=none`）。
     *   这种情况只能靠模块自己注册的**体检闹钟**（见 [AlarmBootstrap]）把进程拉起来。
     *   所以这里只如实记录，不假装成功。
     *
     * 返回是否已就绪；无论如何都不阻塞超过约 10 秒。
     */
    private fun ensureAccessibilityReady(source: String): Boolean {
        if (automationReady()) return true
        var last = "未尝试"
        repeat(A11Y_PREFLIGHT_ATTEMPTS) { round ->
            Log.w("a11y preflight[$source]: 第 ${round + 1}/$A11Y_PREFLIGHT_ATTEMPTS 次尝试自愈")
            last = AutomationBridge.repairAccessibility(context)
            Log.w("a11y preflight[$source]: 自愈报告=$last")
            val deadline = SystemClock.uptimeMillis() + A11Y_PREFLIGHT_WAIT_MS
            while (SystemClock.uptimeMillis() < deadline) {
                if (automationReady()) {
                    Log.w("a11y preflight[$source]: 自愈成功，无障碍已就绪")
                    return true
                }
                SystemClock.sleep(400L)
            }
            // 重试是有意义的：模块进程常见的状态是"活着但被系统冻结"，
            // 而宿主手里的 binder 每次调用都会把它唤醒；一次没成不代表一直不成。
            SystemClock.sleep(A11Y_PREFLIGHT_RETRY_GAP_MS)
        }
        Log.w(
            "a11y preflight[$source]: $A11Y_PREFLIGHT_ATTEMPTS 次均不可用（$last）｜" +
                "报告为 transport=none 说明模块进程已死 —— 宿主无法反向拉起它（没有包可见性），" +
                "需要模块自己注册的体检闹钟，或手动打开一次模块界面",
        )
        return false
    }

    /** 无障碍树里能不能看到**我们自己关心的窗口**（宿主 / 系统界面都算"这层是活的"）。 */
    private fun automationReady(): Boolean = runCatching {
        AutomationBridge.uiNodes().isNotEmpty()
    }.getOrDefault(false)

    /**
     * **服务健康自检**：一条日志回答"现在这台机器到底能不能自动化"。
     *
     * 加它的原因（真机 `evidence/trace_w7` 实证）：ColorOS 在**设备重启后**或内存紧张时
     * 会杀掉模块进程，而系统**不会**自动重新绑定无障碍服务 ——
     * 表现就是宿主侧 `bridge transport=none` / `DeadObjectException`，
     * 无障碍侧 `no allowed accessibility window`（只看得到 `com.android.systemui`），
     * 用户看到的现象正是"**定位不到输入框**"。
     *
     * 这种时候模块本身没问题，是**服务掉线**了：去系统设置里把
     * "元宝每日任务自动化"的无障碍开关关掉再打开（或在无障碍列表里重新勾选）即可恢复。
     *
     * 所以这里把判断需要的三件事一次打出来：服务是否可用、看到的是哪个包、输入栏有没有安全落点。
     */
    private fun selfTestHealth(): String {
        val nodes = AutomationBridge.uiNodes()
        val packages = nodes.map { it.packageName }.distinct().joinToString(",")
        val editorRow = chatComposerRowFraction()
        val info = runCatching { AutomationBridge.composerGeometry(context) }.getOrNull()
        val foreground = resumedActivity?.javaClass?.name.orEmpty()
        val hostPackage = "com.tencent.hunyuan.app.chat"
        val seesHost = packages.contains(hostPackage)
        val report = "serviceAlive=${nodes.isNotEmpty()} nodes=${nodes.size} " +
            "windows=[${packages.ifEmpty { "无" }}] " +
            "看到宿主=$seesHost " +
            "editorRow=${editorRow ?: "null"} " +
            "safe=${info?.safe ?: "null"} " +
            "foreground=$foreground"
        Log.w("self test health $report")
        if (!seesHost) {
            Log.w(
                "self test health ⚠️ 无障碍服务看不到宿主窗口 → 服务掉线/未绑定，" +
                    "尝试自动重绑（等价于用户手动关开无障碍开关）…",
            )
            val repair = AutomationBridge.repairAccessibility(context)
            Log.w("self test health 自愈结果=$repair")
            return "$report 自愈=$repair"
        }
        return report
    }

    /**
     * **自愈自检**：强制重绑无障碍服务一次，报告结果。
     *
     * 用途：`mode=health` 里那步自愈是"发现掉线才触发"，不好反复验证；
     * 这里无条件强制跑一遍完整链路（摘掉 → 沉降 → 装回 → 等服务回调），
     * 用来确认 `WRITE_SECURE_SETTINGS` 授权是否到位、整条自愈是否真的能跑通。
     *
     * 注意：跑完当前无障碍连接会断一下再重建（约 1~7 秒），属预期行为。
     */
    private fun selfTestRepair(): String {
        Log.w("self test repair 开始强制重绑无障碍服务…")
        val report = AutomationBridge.repairAccessibility(context)
        Log.w("self test repair 结果=$report")
        return report
    }

    /**
     * 兼容性自检：换元宝版本后先跑这个。
     *
     * 报告宿主 versionCode、当前 activity、五项入口文案是否还能找到、选择器/福利中心是否可见。
     * 模块里与版本无关的部分（无障碍 / root / WebView JS）不受版本影响，这里主要看**文案/页面**
     * 有没有改名或改版。
     */
    /**
     * 输入栏几何自检：只**读取并校验**落点，不真的点击。
     *
     * 用来在真机上验证"误触修复"：报告无障碍树解析出的输入框/发送键真实 bounds、
     * 最终落点、以及该落点是否通过安全校验。
     */
    /**
     * **素材生成自检**：只往相册里生成一张图，然后确认 MediaStore 集合里查得到。
     *
     * 全程**不点任何界面**，所以可以在用户正在用手机时安全运行。
     * 用途：把"P图没有素材照片"拆成两半 —— 是**没生成**，还是**生成了但相册看不到**。
     */
    private fun selfTestGenerateMedia(): String {
        val generated = media.create(DailyTaskKind.PIC, 1)
            ?: return "gen=failed（create 返回 null，说明插入或发布校验失败，看 media publish 日志）"
        val indexed = media.awaitIndexed(generated.displayName)
        val report = "gen=${generated.displayName} uri=${generated.uri} " +
            "indexed=$indexed scene=${generated.pictureScene}"
        Log.w("self test gen $report")
        return report
    }

    private fun selfTestComposerGeometry(): String {
        val info = AutomationBridge.composerGeometry(context)
            ?: return "geometry=null（无障碍树里没有输入框）"
        val report = "editor=[${info.editorLeft},${info.editorTop}]" +
            "[${info.editorRight},${info.editorBottom}] " +
            "send=(${info.sendX},${info.sendY}) " +
            "editorCenter=(${info.editorCenterX},${info.editorCenterY}) " +
            "safe=${info.safe}"
        Log.w("self test geometry $report")
        return report
    }

    /**
     * 点输入框自检：真正走一遍 `focusChatComposer`（含语音模式自动切回键盘 + 重试）。
     *
     * 用于真机验证"点击不到输入框 / 空输入框时误触 +、切换语音"这个反馈。
     */
    private fun selfTestFocusComposer(): String {
        val before = AutomationBridge.uiNodes(force = true)
        val hadEditor = before.any { it.className.contains("EditText") }
        val ok = focusChatComposer()
        val after = AutomationBridge.uiNodes(force = true)
        val editor = after.firstOrNull { it.className.contains("EditText") }
        val report = "hadEditor=$hadEditor focusDispatched=$ok " +
            "editorAfter=${editor?.box?.toShortString() ?: "null"} nodesAfter=${after.size}"
        Log.w("self test focus $report")
        return report
    }

    private fun selfTestCompat(): String {
        val version = runCatching { Target.installedVersion(context) }.getOrNull()
        val activity = resumedActivity?.javaClass?.name.orEmpty()
        val text = readScreenText(logSample = false)
        val labels = listOf("做同款", "P图", "拍题答疑", "AI写作", "福利中心", "每日任务", "创作", "发现", "我们")
        val found = labels.filter(text::contains)
        val missing = labels - found.toSet()
        val report = "hostVersion=$version activity=$activity " +
            "labelsFound=[${found.joinToString(",")}] missing=[${missing.joinToString(",")}] " +
            "pickerVisible=${isMediaPickerVisible()} welfare=${looksLikeWelfareCenter(text)}"
        Log.w("self test compat $report")
        return report
    }

    /**
     * 五项任务全自检：直接用各自入口（不走福利中心）。
     *
     * 问元宝=对话页；写作=发现→AI写作；P图=创作→P图；搜题=发现→拍题答疑；做同款=创作→做同款。
     */
    private fun selfTestAll(): String {
        val results = mutableListOf<String>()
        results += "ask=" + selfTestAsk()
        results += "writing=" + selfTestWriting()
        results += "pic=" + selfTestMediaFlow(DailyTaskKind.PIC)
        results += "problem=" + selfTestMediaFlow(DailyTaskKind.PROBLEM)
        // 拍题结束后会停在解题结果页，先返回主界面再进"创作"找做同款。
        backInHost()
        pause(1_500L)
        results += "template=" + selfTestTemplateFlow()
        val summary = results.joinToString(" | ")
        Log.w("self test all summary=$summary")
        return summary
    }

    /**
     * 输入栏**只写不点**探针：验证"文案到底有没有进到元宝那颗 Compose 输入框里"。
     *
     * 为什么需要它：真机慢速扫描发现宿主会 `prompt: text channel=hostEditText`
     * 后读回 `len=32`，但元宝输入框**仍然是空的** —— 说明写进了页面上**别的**
     * EditText（侧栏搜索框之类）。这条探针把"写入前后"的每一路读数都打出来，
     * 一次就能分清"写进去了"和"写错地方了"。
     */
    private fun selfTestFillComposer(): String {
        val text = "自检写入composer123"
        val steps = mutableListOf<String>()

        fun state(): String = "${AutomationBridge.composerState(context)}/'${
            AutomationBridge.composerText(context).orEmpty().take(24)
        }'"

        steps += "before=${state()}"

        // ⓪ 诊断：宿主焦点视图 + 输入连接是否拿得到（决定 ② 能不能成立）
        val focusInfo = onMain {
            val root = resumedActivity?.window?.decorView
            val focus = root?.findFocus()
            val editor = root?.let { focusedEditorView(it) }
            val ic = editor?.let { view ->
                runCatching { view.onCreateInputConnection(EditorInfo()) }.getOrNull()
            }
            "findFocus=${focus?.javaClass?.simpleName} editorView=${editor?.javaClass?.simpleName} " +
                "ic=${ic != null} activity=${resumedActivity?.javaClass?.simpleName}"
        }
        steps += "focus:${focusInfo ?: "null"}"

        // ⓪' 诊断：剪贴板在**宿主**和**服务**两侧各能不能读到
        val hostClip = copyToClipboard(text)
        val serviceClip = AutomationBridge.pasteIntoEditor(context)
        steps += "clipboard hostSet=$hostClip servicePasteProbe=$serviceClip → ${state()}"
        if (AutomationBridge.composerState(context) == ComposerState.Text.HAS_TEXT) {
            return finish(steps)
        }

        // ① 无障碍 ACTION_SET_TEXT（服务端直接对那颗 EditText 节点写）
        val viaSetText = AutomationBridge.setText(context, text)
        pause(1_200L)
        steps += "a11ySetText=$viaSetText → ${state()}"
        if (AutomationBridge.composerState(context) == ComposerState.Text.HAS_TEXT) {
            return finish(steps)
        }

        // ② 先按真实几何聚焦输入框，再用**宿主的 InputConnection** commitText
        //    （Compose 的 AndroidComposeView 会把焦点编辑器的会话交出来）
        AutomationBridge.tapEditorByGeometry(context)
        pause(1_000L)
        val viaConnection = commitTextInHost(text)
        pause(1_500L)
        steps += "focus+inputConnection=$viaConnection → ${state()}"
        if (AutomationBridge.composerState(context) == ComposerState.Text.HAS_TEXT) {
            return finish(steps)
        }

        // ③ 剪贴板 + 无障碍 ACTION_PASTE（插入语义）
        copyToClipboard(text)
        val viaPaste = AutomationBridge.pasteIntoEditor(context)
        pause(1_500L)
        steps += "clipboardPaste=$viaPaste → ${state()}"
        if (AutomationBridge.composerState(context) == ComposerState.Text.HAS_TEXT) {
            return finish(steps)
        }

        // ④ root `input text`（只能英文/数字，最后兜底）
        val viaRoot = AutomationBridge.rootSetText(context, "abc123")
        pause(1_500L)
        steps += "rootSetText=$viaRoot → ${state()}"

        return finish(steps)
    }

    private fun finish(steps: List<String>): String {
        val nodes = AutomationBridge.uiNodes(force = true)
        val voiceIcon = nodes.count { (it.text + it.description).contains("切换语音") }
        val holdToTalk = nodes.count { it.text.replace(" ", "").trim('.', '…') == "按住说话" }
        val report = steps.joinToString(" | ") + " || voiceIcon=$voiceIcon holdToTalk=$holdToTalk"
        Log.w("self test fill $report")
        return report
    }

    /**
     * 问元宝：走**与正式任务完全相同**的入口（福利中心 → "去提问" CTA）→ 发一条。
     *
     * 为什么不再用 `tapTab(TAB_ASK)`：2.87.20 的底栏已经改成
     * `专家 / 技能 / AI录音笔 / AI创作`，**根本没有"问元宝"这个 tab**。
     * 旧实现会退化到写死比例 (0.159, 0.964) → 实测落在 **模型选择弹窗**的"快速"上
     * （y≈1931），点下去把页面带崩 —— 这正是自检链路一直"看起来在跑、其实全错"的原因。
     * 正式任务本来就走福利中心 CTA，自检跟着走才测得到真实链路。
     */
    private fun selfTestAsk(): String {
        val opened = openWelfareFromMineTab(force = true)
        Log.w("self test ask 福利中心打开=$opened")
        if (!opened) return "entry=false(welfare)"
        pause(2_000L)
        val before = readProgress(DailyTaskKind.ASK)
        val clicked = clickDailyTask(DailyTaskKind.ASK)
        Log.w("self test ask 福利 CTA(去提问) clicked=$clicked before=$before")
        // CTA 不在（当日已完成/已改版）时退回**首页输入栏**这条路：
        // 首页那条输入栏与对话页是同一个 Compose 输入框，一样能验"找不到输入框"。
        if (!clicked) {
            Log.w("self test ask 没找到'去提问' CTA → 退回首页输入栏直发")
            backInHost()
            pause(1_500L)
        }
        pause(3_000L)
        var sent = sendPrompt(prompts.ask(1), timeoutMs = 12_000L)
        if (!sent) {
            Log.w("self test ask 第一次没发出去，等一会儿重试")
            pause(3_000L)
            sent = sendPrompt(prompts.ask(1), timeoutMs = 12_000L)
        }
        Log.w("self test ask entry=${if (clicked) "welfare" else "home"} sent=$sent")
        return "entry=${if (clicked) "welfare" else "home"} sent=$sent"
    }

    /**
     * 输入栏探针：**逐步**报告输入框状态，专门用来定位"找不到问元宝输入框"。
     *
     * 每一步都输出可核对的原始读数（无障碍节点 / InputConnection / 原生 EditText），
     * 配合 `--ei …TAP_PACE_MS 7000` 与 `tools/tapwatch.py` 可以**逐帧核对**点击是否正确。
     *
     * ```bash
     * adb shell am broadcast -a me.codex.yuanbaodaily.action.SELF_TEST \
     *   -p com.tencent.hunyuan.app.chat \
     *   --es me.codex.yuanbaodaily.extra.SELF_TEST_MODE composer
     * ```
     */
    private fun selfTestComposerProbe(): String {
        val steps = mutableListOf<String>()

        // ① 无障碍树里的输入框（Compose 虚拟节点也在这里暴露）。
        val nodes = AutomationBridge.uiNodes(force = true)
        val editor = nodes.firstOrNull { it.className.contains("EditText") }
        val placeholder = nodes.count { node ->
            (node.text + node.description).contains("发消息或按住说话")
        }
        val voiceIcon = nodes.count { (it.text + it.description).contains("切换语音") }
        steps += "nodes=${nodes.size} editor=${editor?.box?.toShortString() ?: "null"} " +
            "editorText='${editor?.text.orEmpty().take(20)}' placeholder=$placeholder voiceIcon=$voiceIcon"

        // ② 服务进程给出的文案状态（唯一能看到 Compose 文案的通道）。
        val a11yState = AutomationBridge.composerState(context)
        val a11yText = AutomationBridge.composerText(context).orEmpty()
        steps += "a11yState=$a11yState a11yText='${a11yText.take(30)}'"

        // ③ 宿主两条通道。
        val icText = editorTextViaInputConnection()
        val native = onMain { findPreferredEditText()?.text?.toString() }
        steps += "inputConnection=${icText?.let { "'${it.take(30)}'" } ?: "null"} " +
            "nativeEditText=${native?.let { "'${it.take(30)}'" } ?: "null"}"

        // ④ 合并判定（真正决定"能不能点发送"的那一个）。
        val hasText = editorHasText()
        val chatPage = isChatComposerPresent()
        steps += "isChatComposerPresent=$chatPage editorHasText=$hasText"

        val report = steps.joinToString(" | ")
        Log.w("self test composer $report")
        return report
    }

    /**
     * 写作入口（用户指定）：**主页**上那一行快捷入口里的 `AI写作`。
     *
     * 用户明确要求："不用走福利中心，直接屏幕上找 ai 写作即可，就在主页"。
     *
     * 真机 dump（`evidence/home_probe.xml`，1272×2800）证明它**直接可见、无需滑动**：
     * ```
     * View desc='AI写作'  [406,2302][462,2358]
     * TextView 'AI写作'   [476,2301][619,2358]
     * 可点容器            [371,2246][654,2414]  → center=(512,2330)
     * ```
     * 该行完整内容：`快速 / AI写作 / 作业批改 / AI创作`（y≈2301..2358）。
     *
     * 为什么不再走福利中心：福利中心要先切"我们"再进去，任一环失败整条链直接失败
     * （`evidence/trace_w2` 实证 `navigation: 打开福利中心失败` → `entry=false`，
     * 写作任务连输入框都没碰到就结束了）。
     */
    /**
     * 从**首页快捷入口**进入创作页（P图 / 做同款 都在这个页面里）。
     *
     * ## 为什么不走福利中心
     *
     * 自检必须能在**当天任务已经做完**的时候跑 —— 而那正是最常态：
     * 用户晚上想验证一下，任务早就 3/3 了，福利中心里「去提问 / 去P图 / 去做同款」
     * 这些 CTA **全都不存在**。靠 CTA 的自检等于在最有用的时刻失效（用户实测反馈）。
     *
     * ## 走法与写作一致（`openWritingFromHome` 已验证可用）
     *
     * 1. 结构通道：按语义文字点一次；
     * 2. 坐标通道：那颗快捷入口的语义节点 `clickable=false`，要点它的**可点祖先**；
     * 3. 底栏是**可横滑**的（`快速 / AI创作 / 拍题答疑 / AI录音笔…`），
     *    `AI创作` 可能在首屏之外 —— 横滑一次再试。
     */
    private fun openCreatePageFromHome(): Boolean {
        if (!ensureHomeSurface("create")) {
            Log.w("create: 回不到首页 ${surfaceHint()}")
            return false
        }
        // 底栏滚动位置会保留 → 先复位，避免 `AI创作` 被上一个任务滑到屏外。
        resetBottomBar()
        pause(900L)
        // 到达判据：创作页有模板列表，或者已经是某个能力页。
        fun arrived(): Boolean {
            val text = readScreenText(logSample = false)
            if (templateListVisible(text) || SurfaceText.looksLikeCapability(text)) return true
            // 兜底：Activity 已经不是首页，说明这一跳至少把页面切走了
            // （创作页在部分版本上没有模板列表也没有能力页标记）。
            return !SurfaceText.isHomeActivity(currentActivityName())
        }
        repeat(2) { attempt ->
            val byStructure = clickVisible(listOf("AI创作"), allowContains = true)
            Log.w("create: 首页 AI创作 结构通道 attempt=${attempt + 1} hit=$byStructure")
            if (byStructure && awaitCondition(6_000L) { arrived() }) return true
            val byNode = AutomationBridge.tapNodeByLabel(context, "AI创作")
            Log.w("create: 首页 AI创作 坐标通道 attempt=${attempt + 1} hit=$byNode")
            if (awaitCondition(8_000L) { arrived() }) return true
            Log.w("create: 首页没点到 AI创作 → 横滑底栏再试")
            swipeBottomBarLeft()
            pause(1_200L)
            if (clickVisible(listOf("AI创作"), allowContains = true) && awaitCondition(6_000L) { arrived() }) {
                return true
            }
            // 底栏滚动位置会保留，`AI创作` 也可能落在左侧屏外 —— 反向再找一次。
            resetBottomBar()
            pause(1_200L)
            if (clickVisible(listOf("AI创作"), allowContains = true) && awaitCondition(6_000L) { arrived() }) {
                return true
            }
            if (attempt == 0) {
                backInHost()
                pause(1_200L)
            }
        }
        Log.w("create: 进不了创作页 ${surfaceHint()}")
        return false
    }

    /**
     * 从**首页**进入拍题（相机/选图）页。
     *
     * 同样不依赖福利中心 CTA。2.87.20 的底栏可横滑，`拍题答疑` 可能在首屏之外，
     * 所以"点一次 → 横滑再点一次"，仍找不到就如实报告（绝不盲点）。
     */
    private fun openProblemFromHome(): Boolean {
        if (!ensureHomeSurface("problem-entry")) {
            Log.w("problem-entry: 回不到首页 ${surfaceHint()}")
            return false
        }
        // 底栏滚动位置**会保留**：上一个任务把它滑到 `AI写作` 那一端之后，
        // `拍题答疑` 就落到屏幕左侧外。先复位到最左端，再开始找。
        resetBottomBar()
        pause(900L)
        repeat(2) { attempt ->
            val byStructure = clickVisible(listOf("拍题答疑", "拍题"), allowContains = true)
            Log.w("problem-entry: 结构通道 attempt=${attempt + 1} hit=$byStructure")
            if (byStructure && awaitCondition(8_000L) { isCameraSurfaceVisible() }) return true
            val byNode = AutomationBridge.nodeCenter(context, "拍题答疑")
                ?.let { clickScreenPointInProcess(it.first, it.second) } == true
            Log.w("problem-entry: 坐标通道 attempt=${attempt + 1} hit=$byNode")
            if (awaitCondition(8_000L) { isCameraSurfaceVisible() }) return true
            Log.w("problem-entry: 没点到拍题答疑 → 横滑底栏再试")
            swipeBottomBarLeft()
            pause(1_200L)
            if (clickVisible(listOf("拍题答疑"), allowContains = true) &&
                awaitCondition(8_000L) { isCameraSurfaceVisible() }
            ) {
                return true
            }
            // **双向搜索**：目标也可能在左侧屏外，只往一个方向滑必然漏。
            resetBottomBar()
            pause(1_200L)
            if (clickVisible(listOf("拍题答疑"), allowContains = true) &&
                awaitCondition(8_000L) { isCameraSurfaceVisible() }
            ) {
                return true
            }
            if (attempt == 0) {
                backInHost()
                pause(1_200L)
            }
        }
        Log.w("problem-entry: 进不了拍题页 ${surfaceHint()}")
        return false
    }

    /**
     * 从创作页进入 **P图**（判据是"相册选择器已打开"，或已离开创作页）。
     *
     * ## 为什么不能直接 `clickVisible(["P图"])`
     *
     * 创作页上有**两个** "P图"，实测 dump（`evidence/ui_pic.xml`）：
     *
     * | 节点 | bounds | 作用 |
     * | --- | --- | --- |
     * | 顶部标签 | `[508,372][599,449]` | 分类筛选，点了**页面不变**（还停在模板列表） |
     * | 底部浮动按钮 | `[773,2545][858,2607]` | **直接打开相册选择器** |
     *
     * 按文字点会命中面积更小的那个（顶部标签），于是"点了 P图 却没换页"，
     * 后面 `openMediaPicker()` 找"相册"当然找不到 ——
     * 1.4.1 自检里的 `pickerAfterEntry=false` / `album entry clicked=false` 就是这么来的。
     *
     * 所以先用 `bottomOnly=true`（服务端只取 `centerY > 0.75H`）精确拿底部那颗。
     * 真机实测：它点下去会**跳过编辑页直接弹相册**，所以到达判据用
     * [isMediaPickerVisible] 而不是"编辑页可见"。
     */
    private fun openPictureEntry(): Boolean {
        // **先等创作页渲染完。**
        //
        // 真机实证（1.4.8）：`create: hit=true` 之后立刻去找底部"P图"并点下去，
        // 坐标 817,2576 算得没错（手工在同一个页面点它确实打开相册），
        // 但自检那次却进了 `YBAnimatedTemplateDetailActivity`（模板详情） ——
        // 因为**从首页跳到创作页的转场还没结束**，那一击落在了旧页面的模板卡片上。
        //
        // 判据用创作页独有的标签栏（写真/风格化/图文）或"做同款"卡片。
        val ready = awaitCondition(CREATE_PAGE_READY_WAIT_MS) {
            val text = readScreenText(logSample = false)
            templateListVisible(text) ||
                (text.contains("写真") && text.contains("风格化")) ||
                text.contains("做同款")
        }
        Log.w("picture-entry: 创作页就绪=$ready")
        if (!ready) {
            Log.w("picture-entry: 创作页没渲染出来 → 不点，避免点到模板卡片 ${surfaceHint()}")
            return false
        }
        // **节点树比文本晚一步。**
        //
        // 真机实证（1.4.9）：文本判据已经说"创作页就绪=true"，
        // 但紧接着 `nodeCenter("P图", bottomOnly)` 返回 null ——
        // 同一屏上手工点 (815,2576) 明明能打开相册。
        // 同一轮里 `guard` 也出现过"第一次 candidates=0、第二次成功"，
        // 说明无障碍的节点树在页面切换后有几秒滞后。
        //
        // 所以这里按"找得到才点、找不到就等一拍重试"来做，最多 3 次。
        repeat(PICTURE_ENTRY_ATTEMPTS) { attempt ->
            val bottom = runCatching {
                AutomationBridge.nodeCenter(context, "P图", bottomOnly = true)
            }.getOrNull()
            if (bottom != null) {
                Log.w(
                    "picture-entry: 底部浮动 P图 xy=${bottom.first},${bottom.second} " +
                        "attempt=${attempt + 1}",
                )
                // 免 root 版这条路走 clickPointByViewDispatch 的无障碍手势分支
                // （没有宿主 View 树），正好也验证了那条通道。
                if (clickScreenPointInProcess(bottom.first, bottom.second) &&
                    awaitCondition(8_000L, ::isMediaPickerVisible)
                ) {
                    Log.w("picture-entry: 相册已打开（底部 P图）")
                    return true
                }
                Log.w("picture-entry: 点了但相册没开 attempt=${attempt + 1} → 重试")
            } else {
                Log.w("picture-entry: 还没看到底部 P图 节点 attempt=${attempt + 1}")
            }
            pause(1_200L)
        }
        // 兜底：某些版本没有浮动按钮，P图 就是一个列表项。
        val byText = clickVisible(listOf("P图"), allowContains = true) || tapDumpText("P图")
        Log.w("picture-entry: 语义点 P图 hit=$byText")
        if (awaitCondition(6_000L, ::isMediaPickerVisible)) return true
        // 最后一种情况：已经离开创作页（进了编辑页），但相册还没弹 ——
        // 交给调用方的 openMediaPicker 继续，不要把整轮判死。
        val leftCreatePage = !SurfaceText.isHomeActivity(currentActivityName()) &&
            !templateListVisible(readScreenText(logSample = false))
        Log.w("picture-entry: 未弹相册，是否已离开创作页=$leftCreatePage ${surfaceHint()}")
        return leftCreatePage
    }

    private fun openWritingFromHome(): Boolean {
        repeat(2) { attempt ->
            // ① 结构通道：WebView 精确/包含 → 无障碍 `ACTION_CLICK_TEXT` → 宿主 View 树。
            val byStructure = clickVisible(listOf("AI写作"), allowContains = true)
            Log.w("writing: 主页 AI写作 结构通道 attempt=${attempt + 1} hit=$byStructure")
            if (byStructure && awaitWritingPage(6_000L)) return true
            // ② **坐标通道**（1.1.9 真机实测必需）：主页那颗 `AI写作` 的语义节点
            //    `clickable=false`（`evidence/home_probe.xml`：
            //    `desc='AI写作' [406,2302][462,2358]` / `text='AI写作' [476,2301][619,2358]`，
            //    两者的**可点祖先容器**是 `[371,2246][654,2414]`），
            //    而宿主 View 树里没有 Compose 节点 —— 所以上面那条链**整条打不中**
            //    （`evidence/trace_w4` 实证：`结构通道 hit=false` → `主页入口 entered=false`）。
            //    这条通道由无障碍服务取"可点祖先矩形中心"做一次真实触摸。
            val byNode = AutomationBridge.tapNodeByLabel(context, "AI写作")
            Log.w("writing: 主页 AI写作 坐标通道 attempt=${attempt + 1} hit=$byNode")
            if (awaitWritingPage(8_000L)) return true
            if (attempt == 0) {
                // **底栏是可横向滚动的，`AI写作` 常在首屏之外**（1.4.21 真机实测：
                // 首屏只有 `快速 / AI创作 / 拍题答疑 / 智能…`，左滑一下 `AI写作` 才出来）。
                // 老代码不滑、直接回首页重试，于是两次都点同一个不存在的节点，
                // 日志只有 `主页入口 entered=false`，看不出是"入口压根没滑出来"。
                if (!SurfaceText.looksLikeHome(readScreenText(logSample = false))) {
                    Log.w("writing: 第 1 次点 AI写作 未进写作页 → 先回首页")
                    backInHost()
                    pause(1_200L)
                }
                val swiped = swipeBottomBarLeft()
                Log.w("writing: 底栏左滑 dispatched=$swiped → 重试 AI写作")
                pause(1_200L)
            }
        }
        Log.w("writing: 主页 AI写作 入口未打开")
        return false
    }

    /**
     * 当前是不是"AI写作"页。
     *
     * 真机实测（`evidence/write_probe.xml`）该页文案：
     * `选择写作类型 / 通用 / 作文 / 作文素材 / 营销文案 / 公文 / 发布平台 / 写作风格 / 字数`，
     * 输入框占位符是 `请输入你要写的主题`。
     */
    private fun isWritingPage(): Boolean = WritingPage.looks(readScreenText(logSample = false))

    private fun awaitWritingPage(timeoutMs: Long): Boolean {
        if (awaitCondition(timeoutMs) { isWritingPage() }) {
            Log.w("writing: 已进入写作页")
            return true
        }
        Log.w("writing: 等 ${timeoutMs}ms 仍未进入写作页")
        return false
    }

    private fun selfTestWriting(): String {
        // 用户指定：**主页直接点 AI写作**（不走福利中心）。
        val entered = openWritingFromHome()
        Log.w("self test writing 主页入口 entered=$entered")
        if (!entered) return "entry=home=false"
        pause(1_500L)
        val submitted = performWriting(prompts.writing(1))
        Log.w("self test writing entry=home submitted=$submitted")
        return "entry=home submitted=$submitted"
    }

    /**
     * 在底部导航条上**向左滑**。
     *
     * 2.87.20 的底栏是**可横向滚动**的（`快速 / AI创作 / 拍题答疑 / AI录音笔 …`），
     * "AI写作"等入口在更后面，需要先滑出来才能点到（用户实测）。
     *
     * ## 为什么改成"无障碍手势优先"
     *
     * 老实现只有一条路：`onMain { resumedActivity?.window?.decorView ?: return false }`
     * —— 也就是**宿主 View 树直接派发触摸事件**。
     *
     * 这条路只有 root 版（LSPosed 注入进元宝进程）才存在。免 root 版的
     * `resumedActivity` 恒为 null，所以这里**每次都直接返回 false**，
     * 于是 `openCreatePageFromHome()` / `openProblemFromHome()` 里那句
     * "没点到 → 横滑底栏再试"在免 root 版里从来没真正执行过：
     * 一旦 `AI创作` / `拍题答疑` 不在底栏首屏，P图 / 拍题 / 模板就直接进不去，
     * 而日志只会显示"进不了创作页"，看不出是**滑动这一步压根没发生**。
     *
     * 无障碍手势（`dispatchGesture`）两种形态都能用，而且更接近真实触摸，
     * 所以放到第一位；View 派发只在 root 版作为兜底保留。
     * 与同类实现保持一致：[openTaskPanel] 早就是这个顺序。
     */
    /**
     * 底栏快捷入口那一行的**纵向比例候选**（0~1，按全屏高度算）。
     *
     * ## 为什么不能写死 0.845
     *
     * 老实现是 `listOf(0.845f, 0.865f, 0.80f)` —— 这三个数是在开发机
     * （1272×2800、手势导航）上量的。换到别的机器会失配，而且**用户几乎必然遇到**：
     *
     * - **导航栏模式不同**：三键导航比手势导航高约 100px，可用高度变矮，
     *   底栏整体上移 → 0.845 落到快捷入口**上方**的卡片上，横滑变成滑卡片，
     *   "AI创作/AI写作"永远出不来；
     * - **屏幕比例不同**：16:9（1080×1920）与 20:9（1080×2400）下同一个百分比
     *   对应的物理位置不同；
     * - **折叠屏/平板**：分屏或展开态下高度差异更大。
     *
     * 所以先**从无障碍树问真实坐标**：底栏上那些入口的文字节点（`快速`/`AI创作`/
     * `拍题答疑`/`AI写作`…）中心 y 就是这一行的真实位置，跟分辨率、导航栏模式无关。
     * 问不到时才退回一组覆盖常见比例的候选值。
     */
    /**
     * 底栏快捷入口行的候选 y 比例（第一个是实测锚点，其余是兜底）。
     *
     * ## 兜底为什么不是百分比
     *
     * 原来兜底写死成高度百分比 `0.845 / 0.805 / 0.885 / 0.76 / 0.92`。这只在
     * **1272×2800（宽高比 2.20）** 上成立。近 3 年直板机的实测宽高比在
     * **2.158–2.225** 之间（跨度 3%），同一组百分比换到别的机型就偏 40–95px：
     *
     * | 机型 | 屏高 | 0.845 的落点 | 真实底栏 | 偏差 |
     * | --- | --- | --- | --- | --- |
     * | 开发机（2.20） | 2800 | 2366 | 2366 | 0 |
     * | 一加 Ace 6 渲染档（2.20） | 2378 | 2009 | 1980 | **+29** |
     * | 1440 宽 2K（2.20） | 3168 | 2677 | 2662 | +15 |
     * | Mate 90 Pro Max（2.158） | 2848 | 2407 | 2414 | −7 |
     *
     * 底栏是 `layout_gravity=bottom` 的贴底布局，位置只跟"距底多少 dp"有关。
     * 所以兜底改成用 [ScreenScale.bottomAnchoredFraction] 反算：
     * 开发机上的结果与旧值完全等价（434px→0.845、546→0.805……），
     * 但换到 1080 渲染档或 2K 屏上会自动跟着屏高走。
     */
    private fun bottomBarRowFractions(): List<Float> {
        val scale = AutomationBridge.screenScale(context)
        val height = scale.heightPx
        val measured = if (height > 0) {
            BOTTOM_BAR_ENTRY_LABELS.mapNotNull { label ->
                AutomationBridge.nodeCenter(context, label, bottomOnly = true)?.second
            }
                .map { it.toFloat() / height }
                .filter { it in 0.55f..0.99f }
        } else {
            emptyList()
        }
        val anchor = if (measured.isEmpty()) null else measured.average().toFloat()
        val fallbacks = BOTTOM_BAR_FALLBACK_INSETS_REF
            .map { scale.bottomAnchoredFraction(it) }
            .filter { f -> f > 0f && (anchor == null || kotlin.math.abs(f - anchor) > 0.015f) }
        return listOfNotNull(anchor) + fallbacks
    }

    private fun swipeBottomBarLeft(): Boolean {
        for (y in bottomBarRowFractions()) {
            val gestureDrag = AutomationBridge.dragBottomBar(context, from = 0.90f, to = 0.20f, y = y)
            if (gestureDrag) {
                Log.w("self test 底栏左滑 a11y y=$y dispatched=true")
                return true
            }
        }
        // root 版兜底：宿主 View 树直接派发触摸事件（免 root 版没有这条路）。
        // 纵向位置同样用节点实测值，不再写死 0.845。
        val yFraction = bottomBarRowFractions().firstOrNull() ?: DEFAULT_BOTTOM_BAR_Y
        val viewDispatch = onMain {
            val root = resumedActivity?.window?.decorView ?: return@onMain false
            val bounds = Rect().also { root.getGlobalVisibleRect(it) }
            if (bounds.width() <= 0 || bounds.height() <= 0) return@onMain false
            val y = bounds.top + bounds.height() * yFraction
            val fromX = bounds.left + bounds.width() * 0.90f
            val toX = bounds.left + bounds.width() * 0.12f
            runCatching {
                val downTime = SystemClock.uptimeMillis()
                var event = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, fromX, y, 0)
                root.dispatchTouchEvent(event)
                event.recycle()
                repeat(8) { step ->
                    val moment = downTime + 40L * (step + 1)
                    val x = fromX + (toX - fromX) * (step + 1) / 9f
                    event = MotionEvent.obtain(downTime, moment, MotionEvent.ACTION_MOVE, x, y, 0)
                    root.dispatchTouchEvent(event)
                    event.recycle()
                }
                event = MotionEvent.obtain(downTime, downTime + 400L, MotionEvent.ACTION_UP, toX, y, 0)
                val ok = root.dispatchTouchEvent(event)
                event.recycle()
                ok
            }.getOrDefault(false)
        } == true
        Log.w("self test 底栏左滑 viewDispatch=$viewDispatch a11yUnavailable=true")
        return viewDispatch
    }

    /**
     * 把底栏快捷入口**滑回最左端**（反方向复位）。
     *
     * 底栏那排入口是可横向滚动的，而且**滚动位置会保留**：上一个任务为了点 `AI写作`
     * 把它滑到最右端之后，下一个任务要找的 `拍题答疑` 就落到屏幕**左侧外**了。
     * 老逻辑只会继续左滑（[swipeBottomBarLeft]）——越滑越远，必然找不到。
     *
     * 真机实证（1.4.21）：先跑写作（把底栏左滑找 AI写作），紧接着跑拍题，
     * 三次全失败并报 `进不了拍题页`，而屏幕上那排入口只是被滑走了。
     *
     * 所以"找底栏入口"必须是**双向搜索**，且进流程前先复位一次最省事。
     */
    private fun resetBottomBar(): Boolean {
        for (y in bottomBarRowFractions()) {
            if (AutomationBridge.dragBottomBar(context, from = 0.20f, to = 0.95f, y = y)) {
                Log.w("self test 底栏右滑复位 y=$y dispatched=true")
                return true
            }
        }
        Log.w("self test 底栏右滑复位 a11y 不可用（root 版可忽略）")
        return false
    }

    /**
     * 打开"全部任务"面板。
     *
     * 用户实测：底部栏右端有个**三横杠的圆形图标**，点它即可打开全部任务（AI写作/翻译/PPT生成…），
     * 位置 ≈ (1152,2347) @1272x2800。这是拿 `AI写作` 最稳的入口（它不在底栏前几屏里）。
     */
    private fun openTaskPanel(): Boolean {
        // 用户实测：要**按住在"底框"上往左拖**才会露出右端的"三横杠圆图标"。
        // 纵向位置从节点实测（底栏入口文字）取，兜底才用常见比例 —— 见 [bottomBarRowFractions]。
        for (y in bottomBarRowFractions()) {
            val gestureDrag = AutomationBridge.dragBottomBar(context, from = 0.88f, to = 0.50f, y = y)
            Log.w("self test 拖底框 y=$y a11y=$gestureDrag")
            pause(900L)
            // 拖完看是否已能看到"全部任务"面板里的 AI写作
            if (AutomationBridge.nodeCenter(context, "AI写作") != null) {
                Log.w("self test 拖完就出现 AI写作（y=$y）")
                return true
            }
        }
        val dispatched = clickPointByViewDispatch(TASK_PANEL_X, TASK_PANEL_Y)
        Log.w("self test 打开全部任务面板 dispatched=$dispatched")
        pause(2_000L)
        return dispatched
    }

    /** 长按底部栏并往左滑一点点（用于露出"全部任务"圆图标）。 */
    private fun longPressSwipeLeft(): Boolean = onMain {
        val root = resumedActivity?.window?.decorView ?: return@onMain false
        val bounds = Rect().also { root.getGlobalVisibleRect(it) }
        if (bounds.width() <= 0 || bounds.height() <= 0) return@onMain false
        val y = bounds.top + bounds.height() * 0.838f
        val fromX = bounds.left + bounds.width() * 0.85f
        val toX = bounds.left + bounds.width() * 0.62f
        runCatching {
            val downTime = SystemClock.uptimeMillis()
            var event = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, fromX, y, 0)
            root.dispatchTouchEvent(event)
            event.recycle()
            // 先按住不动（长按）
            event = MotionEvent.obtain(downTime, downTime + 500L, MotionEvent.ACTION_MOVE, fromX + 2f, y, 0)
            root.dispatchTouchEvent(event)
            event.recycle()
            repeat(6) { step ->
                val moment = downTime + 500L + 60L * (step + 1)
                val x = fromX + (toX - fromX) * (step + 1) / 7f
                event = MotionEvent.obtain(downTime, moment, MotionEvent.ACTION_MOVE, x, y, 0)
                root.dispatchTouchEvent(event)
                event.recycle()
            }
            event = MotionEvent.obtain(downTime, downTime + 950L, MotionEvent.ACTION_UP, toX, y, 0)
            val ok = root.dispatchTouchEvent(event)
            event.recycle()
            ok
        }.getOrDefault(false)
    } == true

    /**
     * 等上一轮回答**生成结束**再继续。
     *
     * 用户实测：上一轮回答还没说完时去拍题，会选不了图片。所以拍题/选图前先等"生成中"类文字消失。
     */
    private fun awaitGenerationIdle(timeoutMs: Long = 60_000L) {
        val busyWords = listOf(
            "生成中", "正在生成", "停止生成", "思考中", "正在思考", "正在分析", "生成图片中", "正在画",
        )
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val text = readScreenText(logSample = false)
            val busy = busyWords.firstOrNull { text.contains(it) }
            if (busy == null) break
            Log.w("prompt: 上一轮还在“$busy”，等它结束…")
            pause(1_500L)
        }
        pause(3_000L)
    }

    /**
     * 关掉元宝的"图片生成完毕，点击查看"提醒卡片（App 内浮层，会挡住下面的照片/文字）。
     *
     * 实测卡片文字节点 [308,494][1062,572]，关闭 × 在同一行右侧 ≈ (1145,533)，
     * 即相对文字中心偏移约 +460px。用宿主进程内真实触摸点它。
     */
    private fun dismissGenerationCard(): Boolean {
        val center = AutomationBridge.nodeCenter(context, "图片生成完毕") ?: return false
        val window = onMain {
            resumedActivity?.window?.decorView?.let { root ->
                Rect().also { root.getGlobalVisibleRect(it) }
            }
        } ?: return false
        if (window.width() <= 0 || window.height() <= 0) return false
        val x = center.first + (window.width() * GENERATION_CARD_CLOSE_FRACTION).toInt()
        val y = center.second
        val dispatched = clickPointByViewDispatch(
            x.toFloat() / window.width(),
            y.toFloat() / window.height(),
        )
        Log.w("prompt: 关闭“图片生成完毕”卡片 dispatched=$dispatched at ($x,$y)")
        return dispatched
    }

    /**
     * **前台看门狗**：确保元宝在前台。
     *
     * 实测坑：模块按 BACK 会把元宝**退出** ✗ → 之后的点击全都落到别的 App（QQ 群聊 ✗）。
     * 所以每次动作前检查；不在元宝就把它拉回来（模块自身就是元宝，可直接拉自己的 Launcher）。
     */
    /** 仅当元宝在前台时才按 BACK（否则会退到别的 App ✗）。 */
    /** 仅当元宝在前台时才按 BACK（否则会把别的 App 弄乱/退出元宝 ✗）。 */
    private fun backInHost(): Boolean {
        if (AutomationBridge.snapshot(context).packageName != TARGET_PACKAGE) {
            Log.w("guard: 不在元宝前台，跳过 BACK")
            return false
        }
        return AutomationBridge.back(context)
    }

    /**
     * 切到某个底部导航页（**自适应**）。
     *
     * 各版本底部导航文案不同：2.85.1/2.87.10 是 `问元宝/创作/发现/我们`，
     * 2.87.20 变成 `快速/AI创作/拍题答疑/AI录音笔`。所以这里按**别名**逐个找节点坐标，
     * 用宿主进程内真实触摸去点；再不行才退回"底部第 N 个"和固定坐标。
     * 判定成功一律用**页面特征**（导航文字在任何页都存在，不能用它判定）。
     */
    private fun tapTab(label: String, xFraction: Float, yFraction: Float, marker: String) {
        val tab = SelfTestTab.entries.firstOrNull { it.label == label }
        val candidates = (tab?.aliases ?: listOf(label)) + label
        // **绝不在这里按 BACK**：实测连按两次会把元宝退回桌面，后续点击就落到桌面
        // 其它应用（QQ）上（用户实测 ✗）。底部导航不可见时只记录，交给上层的其它路径。
        if (candidates.distinct().all { AutomationBridge.nodeCenter(context, it, bottomOnly = true) == null }) {
            Log.w("self test tab=$label 底部导航暂不可见（不按 BACK，避免退出元宝）")
        }
        // ① 首选：按**真实导航节点**点第 N 个（不写死坐标，所以不会误触"快速"）
        val tabIndex = tab?.ordinal ?: -1
        if (tabIndex >= 0) {
            repeat(2) { attempt ->
                if (AutomationBridge.tapBottomTab(context, tabIndex, label)) {
                    Log.w("self test tab=$label byBottomTab index=$tabIndex attempt=${attempt + 1}")
                    pause(1_800L)
                    if (tabReached(tab, marker)) return
                    pause(1_500L)
                    if (tabReached(tab, marker)) return
                }
            }
        }
        for (alias in candidates.distinct()) {
            // **必须限定在底部导航带内**（bottomOnly=true）。
            // 实测踩过：不限定范围时，别名"快速"会命中**模型弹窗**里的"快速"项
            // （在页面中部 y≈1931），点下去等于在弹窗里乱选模型，页面直接乱掉。
            val center = AutomationBridge.nodeCenter(context, alias, bottomOnly = true)
            Log.w("self test tab=$label alias=$alias nodeCenter(bottomOnly)=$center")
            if (center == null) continue
            val dispatched = clickScreenPointInProcess(center.first, center.second)
            Log.w("self test tab=$label byNodeCenter alias=$alias dispatched=$dispatched")
            pause(1_200L)
            if (tabReached(tab, marker)) return
            pause(1_800L)
            if (tabReached(tab, marker)) return
        }
        if (clickVisible(listOf(label), allowContains = true)) {
            Log.w("self test tab=$label byText=true")
            pause(2_000L)
            if (tabReached(tab, marker)) return
        }
        // 注意：**不要**用写死的底部坐标兜底 —— 2.87.20 上 (0.159,0.964) 正好是"快速"，
        // 会误触（用户实测）。这里宁可判定"没切过去"，交给上层重试。
        Log.w("self test tab=$label 没切过去（marker=$marker 未出现），不做坐标兜底以免误触")
    }

    /**
     * 打开"福利中心"。**纯节点驱动**，不写死任何屏幕比例坐标。
     *
     * 用户指定的真实入口（2.87.20）：
     * ```
     * 首页左上角"两条横杠的圈圈"  →  侧栏  →  福利中心  →  各项任务
     * ```
     *
     * 真机 dump 证据：
     * ```
     * [0,176][210,344]     View  clickable=true  resource-id='ic_navigation_show'
     * [91,218][175,302]    View  clickable=false content-desc='抽屉页入口'
     * ```
     * 入口按钮**没有 text，只有 content-desc**，而且内层节点 `clickable=false`，
     * 所以要用 `tapNodeByLabel`（走"文字+content-desc → 可点祖先真实 bounds"这条路）。
     *
     * 三个真机坑（详见 evidence/MISCLICK_FIX.md 第五节）：
     * - **点汉堡常被吞**：连点 3 次才打开侧栏 ⇒ 必须"点击 → 校验 → 重试"。
     * - **侧栏"福利中心"行不可点**：`clickable=false`，要用真实行坐标派发触摸。
     * - **dumps 抓不到福利中心内容**（纯 WebView）⇒ 用 Activity 名判定。
     */
    private fun openWelfareEntryAdaptive(): Boolean {
        // **已经在福利中心就什么都不要点**！
        // 实测教训：福利页顶部是"邀请"横幅区域，此时再点左上角会误触「去邀请」。
        if (isWelfareCenterOpen()) {
            Log.w("navigation: 已在福利中心，跳过入口点击（避免误触去邀请）")
            return true
        }
        // 旧版：底部"我们"页里有福利中心（保留，兼容老版本）
        if (AutomationBridge.nodeCenter(context, "我们") != null) {
            Log.w("navigation: 检测到旧版底部“我们”入口")
            if (openWelfareFromMineTabLegacy()) return true
        }
        // 新版（2.87.20）：首页左上角抽屉入口 → 侧栏 → 福利中心
        if (openWelfareByDrawer()) return true
        return false
    }

    /**
     * 用户指定路线：**首页左上角抽屉入口 → 侧栏 → 福利中心**。
     *
     * 全程节点驱动：入口与"福利中心"行都用它们自己的真实 bounds，
     * 不出现任何 `0.09 / 0.459` 之类的写死比例。行坐标由
     * [WelfareNav.findWelfareRow] 从最新节点快照里取（纯函数，有单测）。
     */
    private fun openWelfareByDrawer(): Boolean {
        if (!openAppDrawer()) return false
        val nodes = AutomationBridge.uiNodes(force = true)
        // **必须用抽屉专用判据**（标签完全相等 + 行宽受限），不能用 nodeCenter：
        // nodeCenter 取"面积最小的匹配节点"，而侧栏展开时首页的福利卡片仍在无障碍树里，
        // 完全可能选到首页那张卡片的位置 —— 点下去落在侧栏的别的一行上（元宝派/技能），
        // 于是永远进不了福利中心。这正是用户报的"点了抽屉但进不去"。
        val width = screenWidthPx()
        val row = WelfareNav.findWelfareRow(nodes, width)
        if (row == null) {
            Log.w("navigation: 侧栏里找不到“${WELFARE_ROW_LABEL}”行 → 关掉侧栏，不盲点")
            closeDrawerIfOpen()
            return false
        }
        if (!WelfareNav.isDrawerOpen(nodes, width)) {
            Log.w("navigation: 侧栏没真正展开（row 已见但入口尚未移到右侧）→ 再点一次入口")
            if (!openAppDrawer()) return false
        }
        val rowCenter = row.centerX to row.centerY
        Log.w("navigation: 侧栏点福利中心 @(${rowCenter.first},${rowCenter.second})")
        clickScreenPointInProcess(rowCenter.first, rowCenter.second)
        if (awaitWelfareCenter(6_000L)) {
            Log.w("navigation: 福利中心已打开（抽屉入口 → 福利中心）✓")
            return true
        }
        Log.w("navigation: 抽屉路线没进福利中心（activity=${resumedActivity?.javaClass?.simpleName}）")
        return false
    }

    /**
     * 打开首页左侧抽屉。
     *
     * **必须重试**：真机实测"点汉堡菜单被吞"是常态（连续点 3 次才开），
     * 所以这里点击一次就立刻用节点校验，最多试 [DRAWER_OPEN_ATTEMPTS] 次。
     *
     * "侧栏已打开"的判定在 [WelfareNav.isDrawerOpen]（纯函数，有单测）：
     * `福利中心` 行出现，且 `抽屉页入口` 已挪到屏幕右侧。
     */
    /**
     * **确保停在元宝首页**。
     *
     * 为什么需要它（用户实测的"乱点"根因之一）：
     * 一项任务做完以后，App 停在**能力页**（写作页 / P图页 / 拍题页）。
     * 那一页左上角是"返回"，而首页左上角才是"抽屉入口"，两者位置几乎重合。
     * 老代码不区分自己在哪一屏，直接在能力页上找"抽屉页入口" —— 找不到，
     * 于是掉进比例坐标兜底，在页面上乱点。
     *
     * 返回首页的顺序（全程不猜坐标）：
     * 1. Activity 已经是 `YBHomeActivity` → 直接算成功（最可靠）；
     * 2. 侧栏开着 → 点右侧那个"关闭"入口（侧栏展开后抽屉入口会挪到右半屏）；
     * 3. 能力页 / 其它页 → 按一次系统返回键。
     *
     * 注意第 1 条必须先判：**在首页按返回会直接退出元宝**，这是绝不能误判的一次判断。
     */
    private fun ensureHomeSurface(source: String): Boolean {
        repeat(HOME_SURFACE_ATTEMPTS) { attempt ->
            if (SurfaceText.isHomeActivity(currentActivityName())) {
                // **Activity 对了不等于页面画好了。**
                //
                // 免 root 版冷启动元宝后，`YBHomeActivityV2` 会先出现、再花几秒渲染。
                // 真机实证（1.4.7）：`元宝已到前台` 之后 5 秒就去找"AI创作"，
                // 两次结构/坐标通道全 hit=false、横滑也无效，最后判"进不了创作页"，
                // 而此时屏幕上确实还是首页 —— 只是节点还没生成。
                //
                // 所以这里要等一个**首页特征**出现，而不是拿到 Activity 就往下走。
                val ready = awaitCondition(HOME_READY_WAIT_MS) {
                    val text = readScreenText(logSample = false)
                    SurfaceText.looksLikeHome(text) || text.contains("AI创作")
                }
                Log.d("navigation[$source]: 已在首页（activity）ready=$ready")
                if (ready) return true
                // 等不到特征也不算失败：允许调用方继续用语义/坐标通道试，
                // 但至少先给页面留出渲染时间，避免"刚起来就点"。
                return true
            }
            val text = readScreenText(logSample = false)
            if (SurfaceText.looksLikeHome(text)) {
                Log.d("navigation[$source]: 已在首页（文案）")
                return true
            }
            if (currentDrawerOpen()) {
                Log.w("navigation[$source]: 侧栏开着 → 先关掉（attempt=${attempt + 1}）")
                closeDrawerIfOpen()
                pause(700L)
                return@repeat
            }
            val where = if (SurfaceText.looksLikeCapability(text)) "能力页" else "其它页"
            Log.w("navigation[$source]: 停在$where → 返回一次（attempt=${attempt + 1}）")
            if (!backInHost()) return false
            pause(900L)
        }
        val home = SurfaceText.isHomeActivity(currentActivityName()) ||
            SurfaceText.looksLikeHome(readScreenText(logSample = false))
        Log.w("navigation[$source]: 回首页结果=$home ${surfaceHint()}")
        return home
    }

    /** 关掉已展开的侧栏；只用真实节点或系统返回键，不猜坐标。 */
    private fun closeDrawerIfOpen(): Boolean {
        val nodes = AutomationBridge.uiNodes(force = true)
        val width = screenWidthPx()
        if (!WelfareNav.looksOpen(nodes, width)) return true
        val close = WelfareNav.findRightDrawerEntry(nodes, width)
        if (close != null) {
            Log.w("navigation: 点侧栏关闭入口 @(${close.centerX},${close.centerY})")
            if (clickScreenPointInProcess(close.centerX, close.centerY)) {
                pause(700L)
                if (!currentDrawerOpen()) return true
            }
        }
        // 侧栏是覆盖层，一次返回只会关它，不会退出元宝。
        Log.w("navigation: 没找到侧栏关闭入口 → 用返回键关侧栏")
        backInHost()
        pause(700L)
        return !currentDrawerOpen()
    }

    private fun openAppDrawer(): Boolean {
        if (currentDrawerOpen()) return true
        repeat(DRAWER_OPEN_ATTEMPTS) { attempt ->
            val entry = AutomationBridge.nodeCenter(context, DRAWER_ENTRY_LABEL)
            if (entry == null) {
                Log.w("navigation: 找不到抽屉入口节点(${DRAWER_ENTRY_LABEL}) attempt=${attempt + 1}")
                return false
            }
            // 优先走"文字+content-desc → 真实 bounds"通道（入口只有 content-desc，
            // 内层 clickable=false，要取外层可点祖先的矩形）。
            val tapped = AutomationBridge.tapNodeByLabel(context, DRAWER_ENTRY_LABEL) ||
                clickScreenPointInProcess(entry.first, entry.second)
            Log.w("navigation: 点抽屉入口 @(${entry.first},${entry.second}) attempt=${attempt + 1} tapped=$tapped")
            pause(DRAWER_SETTLE_MS)
            if (currentDrawerOpen()) {
                Log.w("navigation: 侧栏已打开（attempt=${attempt + 1}）")
                return true
            }
            Log.w("navigation: attempt=${attempt + 1} 后侧栏仍未打开 → ${drawerStateHint()}")
        }
        Log.w("navigation: 侧栏 $DRAWER_OPEN_ATTEMPTS 次都没打开 → 放弃")
        return false
    }

    /**
     * 用最新节点快照跑一遍 [WelfareNav.looksOpen]（策略本身有单测）。
     *
     * 这里刻意用**宽松判据**控制"还要不要继续点汉堡"的循环：真机上点一次是开、
     * 再点一次就是关。若用严格判据（要求入口挪到右侧）而那一帧没读到入口节点，
     * 循环会以为"没开"再点一下，把刚打开的侧栏又关掉 —— 自检实测踩过这个坑。
     */
    private fun currentDrawerOpen(): Boolean =
        WelfareNav.looksOpen(AutomationBridge.uiNodes(force = true), screenWidthPx())

    /** 侧栏状态的一句话诊断（写日志用，不参与决策）。 */
    private fun drawerStateHint(): String {
        val nodes = AutomationBridge.uiNodes(force = true)
        val width = screenWidthPx()
        val row = WelfareNav.findWelfareRow(nodes, width)
        val entry = WelfareNav.findDrawerEntry(nodes)
        val rightEntry = WelfareNav.anyDrawerEntryOnRight(nodes, width)
        return "nodes=${nodes.size} row=${row?.let { "${it.centerX},${it.centerY}" } ?: "无"} " +
            "entry=${entry?.let { "${it.centerX},${it.centerY}" } ?: "无"} onRight=$rightEntry"
    }

    /**
     * 是否已经处在福利中心。
     *
     * **双通道判定**（单一通道都会漏）：
     * 1. **Activity 名**：`com.tencent.hunyuan.app.chat.biz.welfare.ui.WelfareWebActivity`
     *    —— 真机 WindowManager 日志确认这是福利中心真正的宿主 Activity，最可靠。
     *    判据在 [WelfareNav.isWelfareActivity]（纯函数，有单测）。
     * 2. **页面文本**：`looksLikeWelfareCenter`（WebView 文本通道 + 任务计数）。
     *
     * 注意：**不能只用 uiautomator dump** —— 福利中心是纯 WebView，
     * 真机实测 dump 只有 6 个原生容器节点，H5 文字一个都拿不到。
     */
    private fun isWelfareCenterOpen(): Boolean {
        val activity = resumedActivity?.javaClass?.name.orEmpty()
        if (WelfareNav.isWelfareActivity(activity)) return true
        if (isDedicatedWelfareActivity(activity)) return true
        return looksLikeWelfareCenter(readScreenText(logSample = false))
    }

    /** 兼容旧版（2.85.x/2.87.10）：底部"我们"页 → 福利中心。 */
    private fun openWelfareFromMineTabLegacy(): Boolean {
        AutomationBridge.nodeCenter(context, "我们")?.let { mine ->
            Log.w("navigation: 点“我们” @(${mine.first},${mine.second})")
            clickScreenPointInProcess(mine.first, mine.second)
            pause(2_000L)
            val welfare = AutomationBridge.nodeCenter(context, WELFARE_ROW_LABEL)
            if (welfare != null) {
                Log.w("navigation: 点福利中心 @(${welfare.first},${welfare.second})")
                clickScreenPointInProcess(welfare.first, welfare.second)
                pause(2_000L)
                return isWelfareCenterOpen()
            }
        }
        return false
    }

    /** 切页是否成功：用**页面特征**判定（问元宝页放宽为对话页的几个特征词）。 */
    private fun tabReached(tab: SelfTestTab?, marker: String): Boolean {
        val text = readScreenText(logSample = false)
        if (text.contains(marker)) return true
        return when (tab) {
            SelfTestTab.ASK ->
                text.contains("发消息") || text.contains("按住说话") ||
                    text.contains("Hi，") || text.contains("登录体验更多功能")
            SelfTestTab.CREATE -> text.contains("做同款") || text.contains("生图") || text.contains("p图")
            SelfTestTab.DISCOVER -> text.contains("AI写作") || text.contains("拍题答疑")
            SelfTestTab.MINE -> text.contains("福利") || text.contains("每日任务")
            null -> false
        }
    }

    private fun tapTab(tab: SelfTestTab) = tapTab(tab.label, tab.x, tab.y, tab.marker)

    /** 平台 dump 里按文字找节点并点它的中心（无障碍文字点击失败时兜底）。 */
    private fun tapDumpText(label: String): Boolean {
        val nodes = AutomationBridge.uiNodes(force = true)
        val node = nodes.filter { it.label.contains(label) }.maxByOrNull { it.box.height }
            ?: return false
        val bounds = onMain {
            resumedActivity?.window?.decorView?.let { root ->
                Rect().also { root.getGlobalVisibleRect(it) }
            }
        } ?: return false
        if (bounds.width() <= 0 || bounds.height() <= 0) return false
        val xFraction = (node.box.centerX - bounds.left).toFloat() / bounds.width()
        val yFraction = (node.box.centerY - bounds.top).toFloat() / bounds.height()
        Log.w("self test tapDumpText label=$label xy=${node.box.centerX},${node.box.centerY}")
        return clickPointByViewDispatch(xFraction, yFraction)
    }

    private enum class SelfTestTab(
        val label: String,
        val x: Float,
        val y: Float,
        /** 该页面的特征文本，用来确认真的切过去了。 */
        val marker: String,
        /** 各版本底部导航的别名（2.85.1/2.87.10 与 2.87.20 不同）。 */
        val aliases: List<String>,
    ) {
        /**
         * "问元宝"页。
         *
         * ⚠️ 别名里**绝不能再放"快速"**：2.87.20 的底栏已经没有"问元宝"，
         * 而**模型选择弹窗**里恰好有一项叫"快速"（y≈1931，页面中部）。
         * 旧实现带着这个别名，`nodeCenter` 会命中弹窗 → 一点就把页面带崩
         * （真机 TAPTRACE 实证：`nodeCenter label=快速 xy=241,1931`）。
         * 现在导航改走福利中心 CTA（见 [selfTestAsk]），这里只保留严格别名。
         */
        ASK("问元宝", 0.159f, 0.964f, "发消息或按住说话", listOf("问元宝")),
        CREATE("创作", 0.387f, 0.964f, "做同款", listOf("创作", "AI创作")),
        DISCOVER("发现", 0.550f, 0.964f, "AI写作", listOf("发现", "AI创作")),
        MINE("我们", 0.786f, 0.964f, "福利中心", listOf("我们", "福利中心")),
    }

    private val TAB_ASK = SelfTestTab.ASK
    private val TAB_CREATE = SelfTestTab.CREATE
    private val TAB_DISCOVER = SelfTestTab.DISCOVER

    /**
     * 自检：用**文字点击**打开 `创作 → P图` 的相册选择器（不依赖坐标，供自动化验证用）。
     */
    private fun selfTestOpenPicPicker(): String {
        val tab = clickVisible(listOf("创作"), allowContains = true)
        pause(2_000L)
        val entry = clickVisible(listOf("P图"), allowContains = true)
        pause(3_500L)
        val picker = isMediaPickerVisible()
        Log.w("self test picpicker tab=$tab entry=$entry picker=$picker")
        return "tab=$tab entry=$entry picker=$picker"
    }

    /**
     * 自检"我们 → 福利中心"以及"从对话页回到福利中心"的路线。
     *
     * 重点验证**不再连按返回键**（连按会触发“再按一次退出元宝”）。
     */
    private fun selfTestWelfare(): String {
        val open = openWelfareFromMineTab(force = true)
        pause(1_200L)
        val back = returnToWelfareCenter()
        Log.w("self test welfare openFromMine=$open returnToWelfare=$back")
        return "openFromMine=$open returnToWelfare=$back"
    }

    /**
     * 自检**用户指定的抽屉路线**：首页 → 左上角抽屉 → 侧栏 → 福利中心。
     *
     * 每一步都报告真实节点坐标与判定结果，便于换版本后快速定位是哪一跳坏了：
     * ```
     * adb shell am broadcast -a me.codex.yuanbaodaily.action.SELF_TEST \
     *   -p com.tencent.hunyuan.app.chat \
     *   --es me.codex.yuanbaodaily.extra.SELF_TEST_MODE drawer
     * ```
     */
    private fun selfTestDrawerNavigation(): String {
        val activityBefore = resumedActivity?.javaClass?.simpleName.orEmpty()
        val entry = AutomationBridge.nodeCenter(context, DRAWER_ENTRY_LABEL)
        Log.w(
            "self test drawer stage=entry activity=$activityBefore " +
                "entry=${entry?.let { "${it.first},${it.second}" } ?: "null"}",
        )
        if (entry == null) return "entry=null activity=$activityBefore"

        val drawerOpened = openAppDrawer()
        Log.w("self test drawer stage=drawer opened=$drawerOpened")
        if (!drawerOpened) return "entry=${entry.first},${entry.second} drawer=false"

        val row = AutomationBridge.nodeCenter(context, WELFARE_ROW_LABEL)
        Log.w(
            "self test drawer stage=row " +
                "row=${row?.let { "${it.first},${it.second}" } ?: "null"}",
        )
        if (row == null) return "drawer=true row=null"

        val opened = openWelfareByDrawer()
        val activityAfter = resumedActivity?.javaClass?.simpleName.orEmpty()
        val centerOpen = isWelfareCenterOpen()
        Log.w(
            "self test drawer stage=done opened=$opened activity=$activityAfter " +
                "welfareCenter=$centerOpen",
        )
        return "entry=${entry.first},${entry.second} row=${row.first},${row.second} " +
            "drawer=$drawerOpened opened=$opened activity=$activityAfter center=$centerOpen"
    }

    /** 自检"滑动"是否真的生效（连续三次，逐次记录内容是否真的移动）。 */
    private fun selfTestScroll(): String {
        val results = mutableListOf<String>()
        repeat(3) { index ->
            val before = readScreenText(logSample = false)
            val moved = scrollTemplateListOnce()
            val after = readScreenText(logSample = false)
            Log.w(
                "self test scroll attempt=${index + 1} moved=$moved " +
                    "charsBefore=${before.length} charsAfter=${after.length}",
            )
            results += "a${index + 1}=$moved"
        }
        return results.joinToString(",")
    }

    /**
     * 滑动（只用无障碍滚动）。
     *
     * 注意：早期版本在这里用"宿主进程内合成滑动手势"兜底，真机实测会把创作页滑成**白屏**
     * （同步派发的一串 MOVE 触发了异常 fling），所以合成手势只保留给"点按"（模板页发送键），
     * 滚动一律走无障碍单步滚动。
     */
    private fun scrollForwardWithFallback(): Boolean = AutomationBridge.scrollForward(context)

    private fun swipeByViewDispatch(forward: Boolean): Boolean = onMain {
        val root = resumedActivity?.window?.decorView ?: return@onMain false
        val bounds = Rect().also { root.getGlobalVisibleRect(it) }
        if (bounds.width() <= 0 || bounds.height() <= 0) return@onMain false
        val x = bounds.left + bounds.width() * 0.5f
        val fromY = bounds.top + bounds.height() * (if (forward) 0.78f else 0.30f)
        val toY = bounds.top + bounds.height() * (if (forward) 0.30f else 0.78f)
        val dispatched = runCatching {
            val downTime = SystemClock.uptimeMillis()
            val down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, fromY, 0)
            root.dispatchTouchEvent(down)
            down.recycle()
            repeat(8) { step ->
                val moment = downTime + 40L * (step + 1)
                val y = fromY + (toY - fromY) * (step + 1) / 9f
                val move = MotionEvent.obtain(downTime, moment, MotionEvent.ACTION_MOVE, x, y, 0)
                root.dispatchTouchEvent(move)
                move.recycle()
            }
            val up = MotionEvent.obtain(downTime, downTime + 400L, MotionEvent.ACTION_UP, x, toY, 0)
            val ok = root.dispatchTouchEvent(up)
            up.recycle()
            ok
        }.getOrDefault(false)
        Log.w("self test swipe viewDispatch forward=$forward dispatched=$dispatched")
        dispatched
    } == true

    /** 自检"发送/生成"这一步（用在已经打开的目标页面上，例如模板详情页/P图页）。 */
    private fun selfTestSend(): String {
        // 直接走真实发送链（含"输入框右侧按钮"这条 Compose 兜底），
        // 而不是只试模板页的 ids / 右下角坐标。
        val sent = clickSendButton()
        Log.w("self test send clickSendButton=$sent")
        return "clickSendButton=$sent"
    }

    /**
     * 自检"选图 + 文案 + 发送"链路（P 图 / 拍题）。
     *
     * 生成素材 → 打开相册入口 → 自动选图 → 回到元宝后写文案并发送。
     */
    private fun selfTestMediaFlow(kind: DailyTaskKind): String {
        dismissGenerationCard()
        awaitGenerationIdle()
        val generated = media.create(kind, 1) ?: return "media generation failed"
        // 元宝自带相册需要一两秒才会把新照片索引出来（用户提示：进相册后多等两秒）。
        pause(2_000L)
        Log.w("self test media kind=${kind.id} generated=${generated.displayName}")
        // 没在选择器里就按类型走各自入口。
        //
        // **一律从首页快捷入口进**，不走福利中心 CTA：
        // 当天任务做完后 CTA 全部消失（用户实测反馈），而"晚上跑一次自检"正是最常态的场景。
        if (!isMediaPickerVisible()) {
            val entered = if (kind == DailyTaskKind.PIC) {
                // P图 = 首页「AI创作」→ 创作页 → **底部浮动「P图」按钮**
                // （不是顶部那个标签，见 openPictureEntry 的注释）
                val create = openCreatePageFromHome()
                Log.w("self test media 创作页=$create")
                if (create) openPictureEntry() else false
            } else {
                // 拍题 = 首页快捷入口/底栏的「拍题答疑」（底栏可横滑）
                openProblemFromHome()
            }
            Log.w("self test media 入口 entered=$entered kind=${kind.id}")
            pause(4_500L)
            Log.w("self test media pickerAfterEntry=${isMediaPickerVisible()}")
        }
        val slot = ensureMaterialSlotOpened()
        val picker = openMediaPicker()
        Log.w("self test media pickerOpen=$picker slotOpened=$slot")
        if (!picker) return "album entry not opened (generated=${generated.displayName})"
        val selected = selectGeneratedMedia(generated)
        // 元宝的选择器和能力页同属一个包名，选完可能仍停/又回到选择器；
        // 这时必须先返回能力页，否则"写文案"会落进选择器的搜索框（照片也会被替换掉）。
        if (isMediaPickerVisible()) {
            Log.w("self test media 仍在选择器 → 无障碍返回能力页")
            backInHost()
            pause(1_500L)
        }
        val onCapabilityPage = !isMediaPickerVisible()
        Log.w("self test media onCapabilityPage=$onCapabilityPage")
        // 拍题没有“图片1”标签，而是进入“框选一道题 → 确认”屏；P图 才有“图片1”。
        val cropPage = kind == DailyTaskKind.PROBLEM && awaitCondition(10_000L) {
            readScreenText(logSample = false).let {
                it.contains("确认") || it.contains("框选") || it.contains("解析更精准")
            }
        }
        var cropConfirmed = false
        if (cropPage) {
            // 用户实测：选完照片马上点“确认”太快，等 1~2 秒让框选页稳定。
            pause(1_800L)
            cropConfirmed = confirmProblemCrop()
            Log.w("self test media cropConfirm=$cropConfirmed")
            pause(2_500L)
        }
        val attached = if (kind == DailyTaskKind.PROBLEM) cropPage else onCapabilityPage &&
            awaitCondition(8_000L, ::materialAttached)
        Log.w("self test media selected=$selected attached=$attached")
        if (!selected) return "auto select failed (album opened)"
        if (!attached && kind != DailyTaskKind.PROBLEM) return "picker closed but material slot still empty (image not accepted)"
        val foreground = awaitTargetForeground(10_000L)
        Log.w("self test media targetForeground=$foreground")
        pause(1_200L)
        // 拍题：进入框选页并点“确认”就算完成（不需要文案/发送）；P图 才需要写文案再发送。
        val submitted = if (kind == DailyTaskKind.PIC) {
            sendPrompt(prompts.picture(generated.pictureScene, 1), timeoutMs = 12_000L)
        } else {
            cropConfirmed
        }
        // 写完文案后再确认一次素材是否还在（替换式写入会把图片附件抹掉）。
        // 拍题没有"图片1"标签（它走框选确认页），所以这条检查只对 P图 有意义。
        val attachedAfter = if (kind == DailyTaskKind.PROBLEM) attached else materialAttached()
        if (!attachedAfter && kind != DailyTaskKind.PROBLEM) {
            Log.w("prompt: 文案写入后素材位丢失（图片被替换掉了）")
        }
        Log.w("self test media submitted=$submitted attachedAfter=$attachedAfter")
        return "slot=$slot picker=$picker selected=$selected attached=$attached " +
            "foreground=$foreground submitted=$submitted"
    }

    /** 复核发现进度没涨时调用：下一轮改成"每轮换模板"（必要时才滑动）。 */
    internal fun requireTemplateVariety(reason: String) {
        if (!templateVarietyRequired) {
            Log.d("template: 切换到换模板模式 reason=$reason")
        }
        templateVarietyRequired = true
    }

    /** 新一轮开始时回到"怎么简单怎么来"的默认模式。 */
    internal fun resetTemplatePolicy() {
        templateVarietyRequired = false
        usedTemplateKeys.clear()
    }

    /**
     * 点拍题框选页的"确认"，**逐级尝试并验证页面真的推进**。
     *
     * 真机教训：无障碍文字点击会"返回成功但按钮没按下"（Compose 按钮），所以每一级之后
     * 都要确认已经离开框选页；没离开就换更强的方式（宿主进程内真实触摸 → 模块侧底部按钮）。
     */
    private fun confirmProblemCrop(): Boolean {
        // ① 最稳：取"确认"文字节点的真实坐标（新版是 clickable 的 TextView，实测 [84,2489][1188,2657]），
        //    用宿主进程内真实触摸点它。
        val center = AutomationBridge.nodeCenter(context, "确认", bottomOnly = true)
        if (center != null) {
            val dispatched = clickScreenPointInProcess(center.first, center.second)
            Log.w("problem: cropConfirm by=nodeCenter(${center.first},${center.second}) dispatched=$dispatched")
            if (dispatched && awaitCropConfirmed(8_000L)) return true
        }
        if (clickVisible(listOf("确认"), allowContains = true) && awaitCropConfirmed(8_000L)) {
            Log.w("problem: cropConfirm by=text")
            return true
        }
        // 写死的"底部居中 0.5/0.89…"兜底已**删除**：确认键在页面底部，
        // 用比例猜 y 很容易落到"取消/重拍"这些相邻控件上 → 误触。
        // 优先用上面"按真实节点文字/ID"的两条通道，取不到就判定失败，交给上层重试。
        if (AutomationBridge.clickBottom(context) && awaitCropConfirmed(5_000L)) {
            Log.w("problem: cropConfirm by=bottomButton")
            return true
        }
        Log.w("problem: 确认没点动（仍在框选页）")
        return false
    }

    /** 框选确认成功的标志：已经离开"一次框选一道题"那一屏，或已出现解析相关文本。 */
    private fun awaitCropConfirmed(timeoutMs: Long): Boolean = awaitCondition(timeoutMs) {
        val text = readScreenText(logSample = false)
        !text.contains("一次框选一道题") ||
            text.contains("解题") || text.contains("答案") || text.contains("分析") ||
            text.contains("解析") || text.contains("发送")
    }

    /**
     * 当前是否停在**模板详情页**（`AITemplateDetailActivity`）。
     *
     * 2026-10 福利中心改版：任务区里「做同款」不再跳创作页的模板列表，而是直接打开
     * 这个模板的详情页。`performTemplate` 据此跳过"再选一次模板卡片"那一步。
     */
    private fun onTemplateDetailPage(): Boolean {
        // **Activity 名优先，而且要走 currentActivityName()**：
        // root 版它直接返回宿主当前的 Activity（零延迟）；而 `snapshot.activity`
        // 是"最后一个变化的窗口"，详情页刚打开时还报福利中心 —— 真机实测
        // 2026-11-08 晚了 19 秒才变，`performTemplate` 等 8 秒就判定失败，
        // 表现为点进详情页却又退回模板列表、整项永远做不成。
        val activity = currentActivityName()
        if (activity.contains("AITemplateDetail", ignoreCase = true)) return true
        // **activity 不可靠时以页面内容为准**。
        val text = readScreenText(logSample = false)
        if (text.isBlank()) return false
        // 先排除福利中心本身（否则卡片上的"做同款"会被当成详情页特征）。
        if (TEMPLATE_DETAIL_EXCLUDE.any(text::contains)) return false
        // 2026-11 新版详情页：右上角「分享」图标（contentDescription）+ 底部提示词长文。
        // 旧判据里的「图1」「比例 3:4」在这版页面上**一个都不存在**（真机实测），
        // 所以补「分享 + 有正文」这条；福利中心已被上面的排除项挡掉。
        if (text.contains("分享") && text.length >= 60) return true
        return text.contains("图1") || text.contains("参考上传图") ||
            text.contains("比例 3:4") || text.contains("比例 9:16")
    }

    /**
     * 做同款的"发送"：模板详情页右下角的圆形 ⬆️ 没有文字也没有 ID。
     *
     * 真机教训：文字/ID 匹配会"返回成功但没按下"，导致 `submitted=true` 是假成功
     * （日志里 2.4 秒就返回，且没有任何点击痕迹）。这里逐级尝试并**验证页面真的推进**。
     */
    private fun submitTemplateFlow(): Boolean {
        // ⓪ **新版详情页优先**：右下角是圆形 ⬆️ 发送键，它正上方还有一颗同尺寸的 ⬇️ 下载键，
        //    只能靠"更靠下"区分。`clickBottom` 正是按 bottom 降序取最靠下的可点节点，
        //    比几何通道（这页没有输入栏）可靠。
        if (onTemplateDetailPage() &&
            AutomationBridge.clickBottom(context) &&
            awaitTemplateSent(8_000L)
        ) {
            Log.w("template: send by=bottomButton(detail)")
            return true
        }
        // ① 首选：**真实几何** —— 无障碍树里"与输入框同一行、位于其右侧"的那颗按钮。
        //    原先这里盲点 3 个写死比例落点（0.866/0.928 等），实测会落在输入框行外 → 误触。
        if (AutomationBridge.tapSendByGeometry(context) && awaitTemplateSent(8_000L)) {
            Log.w("template: send by=geometry")
            return true
        }
        if (AutomationBridge.clickButtonNearEditor(context) && awaitTemplateSent(8_000L)) {
            Log.w("template: send by=nearEditor")
            return true
        }
        if (AutomationBridge.clickBottom(context) && awaitTemplateSent(8_000L)) {
            Log.w("template: send by=bottomButton")
            return true
        }
        if (clickVisible(TEMPLATE_SEND_TEXT, allowContains = true) && awaitTemplateSent(8_000L)) {
            Log.w("template: send by=text")
            return true
        }
        Log.w("template: 发送没生效（仍在模板详情页）")
        return false
    }

    /** 发送成功的标志：已经离开模板详情页（进入生成中/结果页等）。 */
    private fun awaitTemplateSent(timeoutMs: Long): Boolean = awaitCondition(timeoutMs) {
        val snapshot = AutomationBridge.snapshot(context)
        val activity = snapshot.activity.orEmpty()
        val text = readScreenText(logSample = false)
        !activity.contains("AITemplateDetail") ||
            text.contains("生成中") || text.contains("生成完成") || text.contains("已保存") ||
            text.contains("重新生成") || text.contains("分享")
    }

    /** 自检"做同款"链路：找模板（必要时下滑）→ 点击 → 发送。 */
    private fun selfTestTemplateFlow(): String {
        // 做同款入口在「创作」页。**从首页快捷入口进**（不依赖福利中心 CTA），
        // 老路径 `tapTab(创作)` 只在快捷入口失败时作为兜底 —— 底栏可横滑，
        // `创作` 不在首屏时它是点不到的。
        var onCreatePage = openCreatePageFromHome()
        Log.w("self test template 首页入口创作页=$onCreatePage")
        if (!onCreatePage) {
            tapTab(TAB_CREATE)
            pause(3_000L)
            onCreatePage = templateListVisible(readScreenText(logSample = false))
            Log.w("self test template 底栏兜底后创作页=$onCreatePage")
        }
        val selection = selectUnusedTemplate()
        Log.w("self test template clicked=${selection.clicked} key=${selection.key.take(80)}")
        if (!selection.clicked) return "no clickable template (scrolled)"
        pause(1_800L)
        val submitted = submitTemplateFlow()
        Log.w("self test template submitted=$submitted")
        return "template=${selection.key.take(60)} submitted=$submitted"
    }

    /**
     * 模板详情页（`AITemplateDetailActivity`）的生成键是一个**没有文字和 ID 的圆形 Button**，
     * 位于窗口右下角（实测 bounds≈[1017,2513][1185,2681]）。文本/ID 匹配都找不到它，
     * 所以这里按"窗口右下角的无标签可点按钮"定位，最后再用坐标兜底。
     */
    private fun clickBottomRightActionButton(): Boolean = onMain {
        val root = resumedActivity?.window?.decorView ?: return@onMain false
        val window = Rect().also { root.getGlobalVisibleRect(it) }
        if (window.width() <= 0 || window.height() <= 0) return@onMain false
        val buttons = mutableListOf<View>()
        collectButtons(root, buttons)
        val target = buttons
            .filter { it.isShown && it.isEnabled }
            .map { view -> view to Rect().also { view.getGlobalVisibleRect(it) } }
            .filter { (_, bounds) ->
                bounds.width() > 0 && bounds.height() > 0 &&
                    bounds.centerX() > window.left + window.width() * 0.70f &&
                    bounds.centerY() > window.top + window.height() * 0.70f
            }
            .minByOrNull { (_, bounds) -> bounds.width().toLong() * bounds.height() }
        if (target != null) {
            val (view, bounds) = target
            Log.d(
                "template: unlabeled bottom-right Button class=${view.javaClass.simpleName} " +
                    "bounds=${bounds.toShortString()}",
            )
            if (view.performClick() || view.callOnClick()) return@onMain true
        } else {
            Log.d("template: no unlabeled bottom-right Button (buttons=${buttons.size})")
        }
        // 坐标兜底已**删除**：写死的 (0.866,0.928) 实测落在输入框行右侧区域，
        // 换机型/换版本必然点偏 → 误触。找不到无标签按钮就交给上层的真实几何通道，
        // 由它按无障碍树实测 bounds 点，取不到就判定失败（宁可不点，不点错）。
        Log.d("template: no unlabeled bottom-right Button (buttons=${buttons.size}) → 交给真实几何通道")
        return@onMain AutomationBridge.tapSendByGeometry(context)
    } == true

    /**
     * 模板列表翻一次，并确认内容**真的动了**。
     *
     * 无障碍 `ACTION_SCROLL_FORWARD` 有时会返回成功但屏幕没动，所以这里对比滚动前后的
     * 屏幕文本；没变就改用宿主进程内的触摸滑动（`swipeByViewDispatch`）。
     */
    private fun scrollTemplateListOnce(): Boolean {
        val before = readScreenText(logSample = false)
        if (!templateListVisible(before)) {
            Log.d("template: 当前看不到模板列表，跳过预滑动（chars=${before.length}）")
            return false
        }
        val dispatched = scrollForwardWithFallback()
        pause(TEMPLATE_SCROLL_PAUSE_MS)
        val after = readScreenText(logSample = false)
        // 真机踩到过"滚过头 → 模板列表离开可视区 → 屏幕一片白"：
        // 一旦列表特征消失就立刻回滚，绝不把页面留在白屏上。
        if (!templateListVisible(after)) {
            Log.d(
                "template: 滑动后模板列表离开可视区（chars ${before.length}→${after.length}），回滚一次",
            )
            AutomationBridge.scrollBackward(context)
            pause(900L)
            val restored = readScreenText(logSample = false)
            Log.d(
                "template: 回滚后列表可见=${templateListVisible(restored)} chars=${restored.length}",
            )
            return false
        }
        val moved = after != before
        Log.d(
            "template: 滚动 dispatched=$dispatched moved=$moved chars=${before.length}→${after.length}",
        )
        return moved
    }

    /** 模板列表特征文本（列表离开可视区时这些都会消失）。 */
    private fun templateListVisible(text: String): Boolean =
        TEMPLATE_LIST_MARKERS.any(text::contains)

    private fun collectButtons(view: View?, output: MutableList<View>) {
        if (view == null || !view.isShown) return
        if (view is Button) output += view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) collectButtons(view.getChildAt(index), output)
        }
    }

    /**
     * 用宿主自己的 View 树合成一次真实触摸。
     *
     * Compose 页面上"没有文字/ID 的按钮"（例如模板详情页右下角的 ⬆️ 生成键）既找不到节点，
     * 也不一定吃得住来自无障碍服务的裸手势；而宿主进程给自己的窗口派发 MotionEvent
     * 完全在应用内部，不需要任何权限，走的就是正常触摸链路。
     */
    /**
     * 按**屏幕比例**点一个位置。
     *
     * ## 免 root 版必须走无障碍手势（这条是 P图/拍题 在免 root 下失败的关键）
     *
     * 调这个函数的按钮**都没有文字**，语义匹配找不到，只能按坐标点：
     * - `PICKER_NEWEST_X/Y` —— 相册里最新那一张（**选素材**）
     * - `CAMERA_ALBUM_X/Y` —— 拍题相机页左下角的"相册"缩略图
     * - `CAPABILITY_EDITOR_X/Y` —— 能力页的输入框
     * - `TASK_PANEL_X/Y` —— "全部任务"圆图标
     *
     * 老实现只有 `resumedActivity?.window?.decorView` 一条路（宿主 View 树派发触摸事件），
     * 而免 root 版的 `resumedActivity` **恒为 null** → 这里每次都直接返回 false。
     * 后果：免 root 版**永远点不到这几颗按钮** —— 用户看到的是
     * "进了相册但不选照片""拍题进不去相册""能力页写不进字"，
     * 而且日志里只有一句 `view dispatch ... dispatched=false`，很难看出是形态问题。
     *
     * 所以：没有宿主对象时改走无障碍手势（`dispatchGesture`），点同一个比例位置。
     * 无障碍手势的坐标系是 `resources.displayMetrics`（全屏），
     * 与 `decorView` 的可见矩形只在状态栏高度上有 ~2% 差异 ——
     * 对这几颗"大目标"按钮没有影响。
     */
    private fun clickPointByViewDispatch(xFraction: Float, yFraction: Float): Boolean {
        val clampedX = xFraction.coerceIn(0.02f, 0.98f)
        val clampedY = yFraction.coerceIn(0.02f, 0.98f)
        if (resumedActivity == null) {
            val tapped = AutomationBridge.clickPointFraction(context, clampedX, clampedY)
            val width = screenWidthPx()
            val height = screenHeightPx()
            val absX = (width * clampedX).toInt()
            val absY = (height * clampedY).toInt()
            Log.d("pointer: a11y fraction x=$clampedX y=$clampedY abs=($absX,$absY) ok=$tapped")
            TapTrace.record(
                "a11yPointFraction",
                absX,
                absY,
                "frac=$clampedX,$clampedY ok=$tapped screen=${width}x$height",
            )
            TapTrace.pace()
            return tapped
        }
        val dispatched = onMain {
            val root = resumedActivity?.window?.decorView ?: return@onMain false
            val bounds = Rect().also { root.getGlobalVisibleRect(it) }
            if (bounds.width() <= 0 || bounds.height() <= 0) return@onMain false
            val x = bounds.left + bounds.width() * clampedX
            val y = bounds.top + bounds.height() * clampedY
            val down = MotionEvent.obtain(SystemClock.uptimeMillis(), SystemClock.uptimeMillis(), MotionEvent.ACTION_DOWN, x, y, 0)
            val up = MotionEvent.obtain(down.downTime, down.downTime + 60L, MotionEvent.ACTION_UP, x, y, 0)
            val ok = runCatching {
                root.dispatchTouchEvent(down)
                root.dispatchTouchEvent(up)
            }.getOrDefault(false)
            down.recycle()
            up.recycle()
            Log.d("template: view dispatch x=${x.toInt()} y=${y.toInt()} dispatched=$ok")
            TapTrace.record("hostViewDispatch", x.toInt(), y.toInt(), "frac=$clampedX,$clampedY ok=$ok")
            ok
        } == true
        TapTrace.pace()
        return dispatched
    }

    private fun performCapability(task: DailyTaskKind, attempt: Int): Boolean = when (task) {
        DailyTaskKind.ASK -> if (askAnsweredByQuizCard) {
            // 趣问卡点完页面已经自己把问题发出去了，再走 sendPrompt 会在同一个对话里
            // 多发一条完全多余的文案（用户反馈的原话：不用复制文案、不用按发送键）。
            Log.d("ask: 趣问卡已自动提问 → 跳过写文案/按发送")
            true
        } else {
            sendPrompt(prompts.ask(attempt))
        }
        DailyTaskKind.WRITING -> performWriting(prompts.writing(attempt))
        DailyTaskKind.PIC -> performPicture(attempt)
        DailyTaskKind.PROBLEM -> performProblem(attempt)
        DailyTaskKind.TEMPLATE -> performTemplate(attempt)
    }

    private fun performWriting(prompt: String): Boolean {
        val filled = awaitCondition(7_000L) { fillText(prompt) }
        if (!filled) {
            Log.d("writing: no editable field found")
            return false
        }
        pause(500L)
        val submitted = clickDirectIds(GENERATE_IDS) ||
            AutomationBridge.clickIds(context, GENERATE_IDS) ||
            clickVisible(GENERATE_TEXT) ||
            clickSendButton()
        Log.d("writing: filled=$filled submitted=$submitted")
        return submitted
    }

    private fun performPicture(attempt: Int): Boolean {
        dismissGenerationCard()
        val generated = media.create(DailyTaskKind.PIC, attempt)
        if (generated == null) {
            Log.d("picture: local input generation failed")
            return false
        }
        Log.d("picture: generated ${generated.displayName} scene=${generated.pictureScene}")
        // 相册需要一两秒才会索引出新照片（用户提示）。
        pause(2_000L)
        // **关键校验**：选择器是查 MediaStore 集合，不是查我们插入的那条 uri。
        // 真机上见过"uri 有了、集合里还没有"的时机差 —— 用户看到的就是
        // "进了相册但没有素材照片"。这里等到集合里真的能查到，再往下走。
        val indexed = media.awaitIndexed(generated.displayName)
        Log.d("picture: 集合索引 indexed=$indexed name=${generated.displayName}")
        if (!indexed) Log.w("picture: 生成成功但相册集合里查不到 → 选择器里可能看不到这张图")
        // 注意：这里**不要**因为"看起来选择器已在前台"就按返回。
        // 我昨天加过这么一段，结果在 P图 能力页上被误触发（能力页也有"相册/照片"字样），
        // 直接按返回把页面退掉，然后重进、再退 —— 用户看到的就是"反复点返回"。
        // 选择器列表过期的问题在 selectGeneratedMedia 内部按"扫不到→重扫一次"处理，不用退。
        // 先点素材位（“点击添加创作素材”），否则选完的图不会被页面接收。
        val slot = ensureMaterialSlotOpened()
        if (!openMediaPicker()) return false
        var selected = selectGeneratedMedia(generated)
        if (!selected) {
            // 相册可能还在加载 —— 等一会儿**在原地重扫一次**，绝不按返回重开
            // （按返回会把相册关掉，再重开，就是"反复点返回"）。
            Log.w("picture: 首次没选到素材（见上面 no media candidate 日志）→ 1.5s 后原地重扫")
            pause(1_500L)
            selected = selectGeneratedMedia(generated)
        }
        if (!selected) {
            Log.w(
                "picture: 扫不到素材格子 → 本轮放弃（不按返回、不盲点）。" +
                    "请把上面 no media candidate 那行日志发回来定位",
            )
            return false
        }
        if (isMediaPickerVisible()) {
            Log.d("picture: 仍在选择器 → 返回能力页")
            backInHost()
            pause(1_500L)
        }
        val attached = awaitCondition(8_000L, ::materialAttached)
        Log.d("picture: slot=$slot selected=$selected attached=$attached")
        var attachedNow = attached
        // 选到"空白照片格"时页面不会接收（用户实测：删掉旧图后相册留下空壳）。
        // 重试：重新开选择器，直接点**第 1 行第 2 格**（最新那张，用户截图实测 ≈0.384/0.229）。
        var retry = 0
        while (!attachedNow && retry < 2) {
            retry++
            Log.d("picture: 素材位仍空，第 $retry 次改点“第1行第2格”（≈$PICKER_NEWEST_X,$PICKER_NEWEST_Y）")
            pause(1_500L)
            if (!openMediaPicker()) break
            pause(1_500L)
            clickPointByViewDispatch(PICKER_NEWEST_X, PICKER_NEWEST_Y)
            pause(2_500L)
            if (isMediaPickerVisible()) {
                confirmMediaSelection()
                pause(1_500L)
            }
            attachedNow = awaitCondition(6_000L, ::materialAttached)
            Log.d("picture: 重试 $retry 后 attached=$attachedNow")
        }
        if (!attachedNow) Log.d("picture: 素材位仍空 —— 页面没有接收这张图")
        if (!awaitTargetForeground(10_000L)) {
            Log.d("picture: media selected but Yuanbao did not return to foreground")
            return false
        }
        pause(1_200L)
        val prompt = prompts.picture(generated.pictureScene, attempt)
        val submitted = sendPrompt(prompt, timeoutMs = 12_000L) ||
            clickDirectIds(GENERATE_IDS) ||
            AutomationBridge.clickIds(context, GENERATE_IDS) ||
            clickVisible(GENERATE_TEXT)
        Log.d("picture: selected=$selected submitted=$submitted prompt=${prompt.take(100)}")
        return submitted
    }

    private fun performProblem(attempt: Int): Boolean {
        dismissGenerationCard()
        awaitGenerationIdle()
        val generated = media.create(DailyTaskKind.PROBLEM, attempt)
        if (generated == null) {
            Log.d("problem: local question image generation failed")
            return false
        }
                Log.d("problem: generated ${generated.displayName}")
        // 用户实测：上一轮回答还没说完时选不了图片。所以每轮先等生成结束；失败就等一会儿再来一次。
        var problemRound = 0
        while (problemRound < 3) {
            awaitGenerationIdle()
            val slot = ensureMaterialSlotOpened()
            val picked = openMediaPicker()
            val selected = picked && selectGeneratedMedia(generated)
            if (selected) {
                if (isMediaPickerVisible()) {
                    Log.d("problem: 仍在选择器 → 返回能力页")
                    backInHost()
                    pause(1_500L)
                }
                // 拍题选完图会进入“一次框选一道题 → 确认”这一屏（与 P图 不同：没有“图片1”标签）。
                val cropPage = awaitCondition(10_000L) {
                    readScreenText(logSample = false).let {
                        it.contains("确认") || it.contains("框选") || it.contains("解析更精准")
                    }
                }
                Log.d("problem: cropPage=$cropPage round=${problemRound + 1} slot=$slot")
                if (cropPage) {
                    // 用户实测：选完照片后要等 1~2 秒再点“确认”，否则太快。
                    pause(1_800L)
                    val cropConfirmed = confirmProblemCrop()
                    Log.d("problem: cropConfirm=$cropConfirmed（确认后元宝会直接开始解析）")
                    pause(2_500L)
                    if (cropConfirmed) return true
                }
            } else {
                Log.d("problem: 第 ${problemRound + 1} 轮没选上（slot=$slot picked=$picked）")
            }
            problemRound++
            Log.d("problem: 等上一轮生成结束后重试 round=$problemRound")
            pause(3_000L)
        }
        return false
    }

    private fun performTemplate(attempt: Int): Boolean {
        // 默认"怎么简单怎么来"：允许重复使用同一个可见模板（不滑动、不换模板）。
        // 只有当复核发现进度没涨时，才由 requireTemplateVariety() 切成"每轮换模板"。
        if (templateVarietyRequired) {
            Log.d("template: 换模板模式（上一轮复核进度未涨）attempt=$attempt")
        } else {
            usedTemplateKeys.clear()
            Log.d("template: 简化模式（可重复使用同一模板，不滑动）attempt=$attempt")
        }
        if (attempt == 1 && usedTemplateKeys.size >= REQUIRED_TEMPLATE_ROUNDS) {
            usedTemplateKeys.clear()
        }

        pause(900L)
        // **2026-10 福利中心改版**：任务卡片里的「做同款」不再是跳到创作页的模板列表，
        // 而是**直接打开该模板的详情页**（`AITemplateDetailActivity`）。
        // 旧逻辑进来还要再找一次「做同款」卡片，必然找不到 —— 实测日志
        // `template: no unused visible template; scroll retry required`，整项永远失败。
        // 已经在详情页就跳过选择，直接发送。详情页要 1~3s 才渲染，这里等一会儿再判。
        if (awaitCondition(TEMPLATE_DETAIL_WAIT_MS, ::onTemplateDetailPage)) {
            Log.d("template: 已在模板详情页（福利中心 CTA 直达）→ 跳过模板选择，直接发送")
            val submitted = submitTemplateFlow()
            if (submitted) pause(1_000L)
            Log.d("template: direct-detail submitted=$submitted")
            return submitted
        }

        val selection = selectUnusedTemplate()
        if (!selection.clicked) {
            // CTA 转场比 900ms 慢时也会走到这里：再确认一次是不是已经停在详情页。
            if (onTemplateDetailPage()) {
                Log.d("template: 选择失败但已进入详情页 → 直接发送")
                val submitted = submitTemplateFlow()
                if (submitted) pause(1_000L)
                Log.d("template: direct-detail(after-miss) submitted=$submitted")
                return submitted
            }
            Log.d("template: no unused visible template; scroll retry required")
            return false
        }
        selection.key.takeIf(String::isNotBlank)?.let(usedTemplateKeys::add)
        pause(1_800L)

        // The recommendation flow already contains its own source image and
        // prompt. It only needs the final send/submit action; opening the
        // gallery here caused the old implementation to leave the task page.
        val submitted = submitTemplateFlow()
        if (submitted) pause(1_000L)
        Log.d(
            "template: selected=${selection.key.take(120)} " +
                "used=${usedTemplateKeys.size} submitted=$submitted",
        )
        return submitted
    }

    private fun selectUnusedTemplate(): AutomationBridge.TextClickResult {
        repeat(MAX_TEMPLATE_SCROLLS + 1) { scrollAttempt ->
            val result = AutomationBridge.clickNextText(
                context = context,
                aliases = TEMPLATE_USE_TEXT,
                blockedKeys = usedTemplateKeys,
                allowContains = true,
            )
            if (result.clicked) return result
            if (scrollAttempt >= MAX_TEMPLATE_SCROLLS) return result
            val scrolled = scrollTemplateListOnce()
            Log.d("template: scroll forward attempt=${scrollAttempt + 1} dispatched=$scrolled")
            if (!scrolled) return result
            pause(TEMPLATE_SCROLL_PAUSE_MS)
        }
        return AutomationBridge.TextClickResult(false, "")
    }

    /**
     * 能力页（智能P图/拍题）必须**先点“点击添加创作素材”**再进选择器，否则选完的图
     * 不会被页面接收 —— 实测从入口直接进选择器时，素材位始终空着（只有占位文案）。
     */
    private fun ensureMaterialSlotOpened(): Boolean {
        val text = readScreenText(logSample = false)
        if (MATERIAL_SLOT_TEXT.none(text::contains)) return false
        val clicked = clickVisible(MATERIAL_SLOT_TEXT, allowContains = true)
        Log.d("media: 素材位 clicked=$clicked")
        if (!clicked) return false
        val picker = awaitCondition(8_000L, ::isMediaPickerVisible)
        Log.d("media: 素材位进入选择器=$picker")
        return picker
    }

    /**
     * 素材位是否真的填上了。
     *
     * 注意：能力页**始终**显示“添加创作素材”这个入口，所以不能拿它当占位符判据；
     * 真正的信号是页面把已选素材标成 `图片1/图片2…`（并且选择器已经关闭）。
     */
    private fun materialAttached(): Boolean {
        if (isMediaPickerVisible()) return false
        val text = readScreenText(logSample = false)
        if (text.isBlank()) return false
        return ATTACHED_MEDIA_REGEX.containsMatchIn(text)
    }

    /**
     * 把屏幕坐标换算成宿主窗口内的相对坐标，并在**宿主进程内**派发一次真实触摸。
     *
     * 实测：元宝自带的相册选择器对无障碍手势的点击"看起来成功但不选中"，
     * 而进程内派发的触摸（走应用自己的输入链路）能真正选中图片。
     */
    /**
     * 点一个**绝对屏幕坐标**（由无障碍树里的节点 bounds 算出来）。
     *
     * 免 root 版没有宿主 View 树，不能靠 `decorView` 换算比例 ——
     * 直接用屏幕真实尺寸换算，再交给 [clickPointByViewDispatch]
     * （那边会根据有没有宿主对象，选无障碍手势或 View 派发）。
     */
    private fun clickScreenPointInProcess(x: Int, y: Int): Boolean {
        val bounds: Rect? = onMain {
            resumedActivity?.window?.decorView?.let { root ->
                Rect().also { root.getGlobalVisibleRect(it) }
            }
        }
        val origin = bounds?.takeIf { it.width() > 0 && it.height() > 0 }
        val left = origin?.left ?: 0
        val top = origin?.top ?: 0
        val width = origin?.width() ?: screenWidthPx()
        val height = origin?.height() ?: screenHeightPx()
        if (width <= 0 || height <= 0) return false
        val xFraction = (x - left).toFloat() / width
        val yFraction = (y - top).toFloat() / height
        if (xFraction !in 0f..1f || yFraction !in 0f..1f) {
            Log.w("pointer: 坐标超出屏幕 x=$x y=$y screen=${width}x$height origin=($left,$top)")
            return false
        }
        return clickPointByViewDispatch(xFraction, yFraction)
    }

    private fun openMediaPicker(): Boolean {
        if (isMediaPickerVisible()) return true
        // 拍题相机页（2.87.20）：**左下角是"相册"缩略图（没有文字）**，右下角是"悬浮窗搜题"。
        // 这里只点一次左下角，绝不重复点（重复会误触右下角的悬浮窗搜题）。
        if (isCameraSurfaceVisible()) {
            val album = clickPointByViewDispatch(CAMERA_ALBUM_X, CAMERA_ALBUM_Y)
            Log.d("media: 拍题相机页点左下角相册 thumbnail=$album xy=($CAMERA_ALBUM_X,$CAMERA_ALBUM_Y)")
            if (album && awaitCondition(8_000L, ::isMediaPickerVisible)) return true
        }
        if (isCameraSurfaceVisible()) {
            val cameraAlbum = clickVisible(CAMERA_ALBUM_TEXT, allowContains = true)
            Log.d("media: camera surface album action clicked=$cameraAlbum")
            if (cameraAlbum && awaitCondition(8_000L, ::isMediaPickerVisible)) return true
        }
        // Prefer a labelled album action. On real devices the generic image
        // icon can be the shutter/camera entry, while the text node opens the
        // actual gallery.
        val clicked = clickVisible(ALBUM_TEXT, allowContains = true) ||
            clickDirectIds(ALBUM_IDS) ||
            AutomationBridge.clickIds(context, ALBUM_IDS)
        Log.d("media: album entry clicked=$clicked")
        if (!clicked) return false
        return awaitCondition(8_000L, ::isMediaPickerVisible)
    }

    private fun isMediaPickerVisible(): Boolean {
        return isMediaPickerVisible(AutomationBridge.snapshot(context))
    }

    private fun isMediaPickerVisible(snapshot: AutomationBridge.Snapshot): Boolean {
        val activity = resumedActivity?.javaClass?.name.orEmpty()
        if (activity.contains("PickerActivity", ignoreCase = true) ||
            activity.contains("PictureSelector", ignoreCase = true) ||
            activity.contains("PhotoPicker", ignoreCase = true) ||
            activity.contains("DocumentsActivity", ignoreCase = true)
        ) return true
        val text = snapshot.text.lowercase()
        if (AccessibilityPackagePolicy.isCameraPackage(snapshot.packageName)) return false
        if (AccessibilityPackagePolicy.isMediaPackage(snapshot.packageName)) return true
        // A picker window can already exist while the framework still reports the
        // host window as active (the usual state right after the picker opens).
        // The accessibility service inspects every window, so trust its answer.
        if (snapshot.pickerVisible && !text.contains("福利中心")) return true
        // Yuanbao hosts both task pages and its album surface in the same
        // package. Use album-specific labels instead of package name alone.
        return snapshot.packageName == TARGET_PACKAGE &&
            PICKER_TEXT_MARKERS.any(text::contains) &&
            !text.contains("福利中心")
    }

    /**
     * 当前是不是拍题/相机的取景页。
     *
     * **必须先排除首页和福利中心**：真机实证（1.4.7）首页文案里带「相机」字样，
     * 于是被判成拍题相机页，`openMediaPicker` 直接去点屏幕左下角那个"相册"缩略图 ——
     * 而那里在首页是别的东西。这类误判比"识别不出"危险得多。
     */
    private fun isCameraSurfaceVisible(): Boolean {
        val snapshot = AutomationBridge.snapshot(context)
        if (AccessibilityPackagePolicy.isCameraPackage(snapshot.packageName)) return true
        val activity = currentActivityName()
        if (SurfaceText.isHomeActivity(activity)) return false
        if (SurfaceText.isWelfareActivity(activity)) return false
        if (SurfaceText.looksLikeHome(snapshot.text)) return false
        if (snapshot.text.contains("福利中心")) return false
        val text = snapshot.text.lowercase()
        return text.contains("拍照") || text.contains("相机") ||
            text.contains("camera") && !PICKER_TEXT_MARKERS.any(text::contains)
    }

    private fun selectGeneratedMedia(generated: GeneratedTaskMedia): Boolean {
        val entry = AutomationBridge.snapshot(context)
        if (!isMediaPickerVisible(entry)) {
            Log.d(
                "media: picker not visible package=${entry.packageName} " +
                    "activity=${entry.activity} pickerVisible=${entry.pickerVisible} " +
                    "windows=${entry.windows}",
            )
            return false
        }
        val blockedKeys = linkedSetOf<String>()
        var emptyScans = 0
        repeat(MAX_MEDIA_SELECTION_ATTEMPTS) { pickerAttempt ->
            val before = captureMediaSurface()
            // 选择器和能力页在同一个包名里，"目标页可见"必须排除选择器仍在前台的情况。
            if (before.targetVisible && !isMediaPickerVisible()) {
                Log.d("media: user selection returned to Yuanbao before automated tap")
                return true
            }
            if (before.confirmVisible) {
                if (confirmMediaSelection()) return true
                pause(MEDIA_RETRY_PAUSE_MS)
                return@repeat
            }
            if (!before.pickerVisible) {
                pause(MEDIA_RETRY_PAUSE_MS)
                return@repeat
            }

            val tap = AutomationBridge.clickLatestMedia(
                context = context,
                marker = generated.marker,
                blockedKeys = blockedKeys,
            )
            Log.d(
                "media: candidate gesture=${tap.dispatched} attempt=${pickerAttempt + 1} " +
                    "key=${tap.candidateKey} xy=${tap.x},${tap.y} candidates=${tap.candidateCount} " +
                    "package=${tap.packageName} marker=${generated.marker}",
            )
            if (tap.candidateKey.isBlank() || tap.x < 0 || tap.y < 0) {
                emptyScans++
                // **绝不在这里按返回重开相册**。
                // 旧实现在连续扫不到格子时会 `reopenMediaPicker()`（= 按返回 + 重新打开），
                // 用户实测的结果是"点返回、反复这样"。而且按返回没有任何好处：
                // 相册列表是打开那一刻的快照，重开只是回到同一个画面。
                //
                // 这里只**原地重扫**（相册可能还在加载），连续两次都空就把结论交回上层，
                // 由它打一条可定位的日志并结束本轮 —— 宁可不做，也不反复按返回。
                if (emptyScans >= MEDIA_EMPTY_SCAN_LIMIT) {
                    Log.w("media: 连续 $emptyScans 次扫不到照片格子 → 结束选择（不按返回）")
                    return false
                }
                Log.d("media: 本轮没扫到格子 → 原地重扫 emptyScans=$emptyScans")
                pause(MEDIA_RETRY_PAUSE_MS)
                return@repeat
            }

            // **服务端已经点过一次了，这里不能再补一刀。**
            //
            // 旧逻辑是"无障碍手势不可靠，再用进程内触摸点同一个坐标"。
            // 那在 root 版成立（服务端用 ACTION_CLICK、宿主再补真实触摸）。
            // 但 1.4.12 起服务端 `clickLatestMedia` 内部已经改用 `tapAt`（dispatchGesture，
            // 就是真实触摸），而免 root 版的 `clickScreenPointInProcess` 也只能走手势 ——
            // 于是**同一张缩略图被点了两次**：
            //
            // ```
            // media candidate gesture=true key=…|324,569,633,878
            // media: 进程内触摸已派发 xy=478,723          ← 第二次，把选中取消了
            // media: accessibility produced no surface transition
            // media: candidate blocked after unchanged surface
            // ```
            //
            // 真机表现：相册先关闭又弹回来、或者干脆什么都没选上，然后判"没前进"。
            var advanced = if (tap.dispatched) {
                // 手势已派发 → 只等界面变化，不再补点。
                awaitMediaAdvance(before, MEDIA_GESTURE_VERIFY_MS)
            } else if (tap.x > 0 && tap.y > 0) {
                // 只有手势**没**派发成功时才用备用通道补一次（root 版的宿主 View 派发）。
                val inProcess = clickScreenPointInProcess(tap.x, tap.y)
                if (inProcess) Log.d("media: 手势未派发，改用进程内触摸 xy=${tap.x},${tap.y}")
                awaitMediaAdvance(before, MEDIA_GESTURE_VERIFY_MS)
            } else {
                null
            }
            if (advanced == null) {
                val rootDispatched = AutomationBridge.rootTap(context, tap.x, tap.y)
                Log.d(
                    "media: accessibility produced no surface transition; " +
                        "root same-coordinate tap=$rootDispatched xy=${tap.x},${tap.y}",
                )
                if (rootDispatched) {
                    advanced = awaitMediaAdvance(before, MEDIA_ROOT_VERIFY_MS)
                }
            }
            if (advanced == null) {
                blockedKeys += tap.candidateKey
                Log.d("media: candidate blocked after unchanged surface key=${tap.candidateKey}")
                pause(MEDIA_RETRY_PAUSE_MS)
                return@repeat
            }
            if (advanced.targetVisible) {
                Log.d("media: thumbnail selection returned directly to Yuanbao")
                return true
            }
            if (confirmMediaSelection()) return true
            if (awaitTargetForeground(3_000L)) return true
            pause(MEDIA_RETRY_PAUSE_MS)
        }
        Log.d("media: picker did not return selected image to Yuanbao")
        return false
    }

    private fun confirmMediaSelection(): Boolean {
        if (captureMediaSurface().targetVisible) return true
        val before = captureMediaSurface()
        val dispatched = AutomationBridge.clickIds(context, MEDIA_CONFIRM_IDS) ||
            AutomationBridge.clickText(
                context,
                MEDIA_CONFIRM_TEXT,
                allowContains = true,
            ) ||
            clickDirectIds(MEDIA_CONFIRM_IDS)
        Log.d("media: preview confirm semantic dispatch=$dispatched package=${before.packageName}")
        if (dispatched && awaitMediaSelectionDone(MEDIA_CONFIRM_VERIFY_MS)) return true

        // 元宝自带选择器的“确定/完成”多半没有文字/ID：直接点窗口底部最靠下的可点按钮。
        val bottom = AutomationBridge.clickBottom(context)
        Log.d("media: confirm bottom-most button clicked=$bottom")
        if (bottom && awaitMediaSelectionDone(MEDIA_CONFIRM_VERIFY_MS)) return true

        // 确定/完成 在这类预览页上常常是个**没有文字、没有 ID** 的按钮（在窗口底部），
        // 文字与 ID 都匹配不到。按底部两个常见落点用模块侧"最深可点节点"通道再试一次。
        for ((xFraction, yFraction) in MEDIA_CONFIRM_POINTS) {
            val clicked = AutomationBridge.clickPointFraction(context, xFraction, yFraction)
            Log.d("media: confirm bottom fallback ($xFraction,$yFraction) clicked=$clicked")
            if (clicked && awaitMediaSelectionDone(MEDIA_CONFIRM_VERIFY_MS)) return true
        }

        val latest = captureMediaSurface()
        val isColorOsPreview = (before.packageName == COLOROS_GALLERY_PACKAGE ||
            latest.packageName == COLOROS_GALLERY_PACKAGE) &&
            (before.confirmVisible || latest.confirmVisible)
        if (isColorOsPreview) {
            val accessibilityTap = AutomationBridge.tap(
                context,
                COLOROS_PREVIEW_CONFIRM_X,
                COLOROS_PREVIEW_CONFIRM_Y,
            )
            if (accessibilityTap && awaitMediaSelectionDone(MEDIA_CONFIRM_VERIFY_MS)) return true
            val rootTap = rootTapRelative(COLOROS_PREVIEW_CONFIRM_X, COLOROS_PREVIEW_CONFIRM_Y)
            Log.d("media: ColorOS preview root confirm=$rootTap")
            if (rootTap && awaitMediaSelectionDone(MEDIA_CONFIRM_VERIFY_MS)) return true
        }
        return false
    }

    /**
     * 选图是否真的完成了：目标页可见**并且**选择器已经关闭。
     *
     * 选择和能力页同属元宝包名，只看"目标页可见"会在还在选择器里时就误判成功。
     */
    private fun awaitMediaSelectionDone(timeoutMs: Long): Boolean = awaitCondition(timeoutMs) {
        val snapshot = captureMediaSurface()
        snapshot.targetVisible && !snapshot.pickerVisible
    }

    private fun awaitTargetForeground(timeoutMs: Long): Boolean = awaitCondition(timeoutMs) {
        captureMediaSurface().targetVisible
    }

    private fun captureMediaSurface(): SurfaceSnapshot {
        val snapshot = AutomationBridge.snapshot(context)
        val pickerVisible = isMediaPickerVisible(snapshot)
        val text = snapshot.text.lowercase()
        val colorOsPreview = snapshot.packageName == COLOROS_GALLERY_PACKAGE &&
            text.contains("选择项目") && text.lineSequence().any { it.trim() == "选择" }
        return SurfaceSnapshot(
            packageName = snapshot.packageName,
            signature = snapshot.signature.ifBlank {
                "${snapshot.packageName}|${snapshot.text.replace(Regex("\\s+"), " ").hashCode()}"
            },
            pickerVisible = pickerVisible,
            targetVisible = snapshot.packageName == TARGET_PACKAGE && !pickerVisible,
            confirmVisible = snapshot.confirmVisible || colorOsPreview,
        )
    }

    private fun awaitMediaAdvance(before: SurfaceSnapshot, timeoutMs: Long): SurfaceSnapshot? {
        var advanced: SurfaceSnapshot? = null
        awaitCondition(timeoutMs) {
            val current = captureMediaSurface()
            if (MediaSelectionVerifier.hasAdvanced(before, current)) {
                advanced = current
                true
            } else {
                false
            }
        }
        return advanced
    }

    private fun sendPrompt(prompt: String, timeoutMs: Long = 7_000L): Boolean {
        Log.d("prompt: sendPrompt 进入 len=${prompt.length} 另有自动化在跑=${AutomationGate.isBusy()}")
        // ⓪'' **侧栏没收起来就别动输入框**（1.1.6 新增，真机 `evidence/trace_e3_ask` 实证）。
        //
        // 侧栏（抽屉）展开时，主内容窗口被整体推到屏幕**右侧之外**：同一帧里无障碍树
        // 报输入框 `[1108,2274][1871,2571]`（屏幕 1272 宽），算出的发送落点是
        // `x=2087` —— 完全在屏幕外。此时不管几何算法多准都没用，点下去必然是白点，
        // 而上层只会看到"发送没生效"，于是把同一条提示词连点 4 次、白跑几十秒。
        //
        // 前置把页面状态修好，比在后面加各种拒绝更有效。
        if (drawerOpen()) {
            Log.w("prompt: 侧栏处于展开状态（主窗口被推出屏幕，输入框几何不可信）→ 先收起")
            closeDrawer()
            if (drawerOpen()) {
                Log.w("prompt: 侧栏收不起来 → 本次不发送（宁可不发，也不在错误的窗口上点击）")
                return false
            }
        }
        // ⓪ **先清掉残留文案**（真机 `evidence/trace_d5_ask` 实测的关键修复）。
        //
        // 上一轮"写成功但发送失败"会在输入框里留下整段文案。此时直接再写一次，
        // 新文案是被**追加**上去的 → 两段提示词拼成一条发出去
        // （实测：`…按重要程度排序。关于做好…`，36 字 + 下一段拼接）。
        // 所以先清空并回读确认；清不掉就**不再写入**（宁可不发，也不发两段拼的）。
        val leftover = AutomationBridge.composerText(context).orEmpty()
        // **只有"真正的残留提示词"才允许清空**（1.1.7，真机 `evidence/trace_reg_pic` 实证）。
        //
        // 两个否定条件，缺一不可：
        // ① `isMeaningfulText()` —— 元宝往输入框挂图片/文件时，节点文字只有一个
        //    **对象替换符** `\uFFFC`（P图页实测 `text='\uFFFC'`）。它被当成"上一轮残留"
        //    后清理逻辑会执行 `ACTION_SET_TEXT("")`，把**刚附加的照片抹掉**，
        //    于是 P图 必然提交失败（`picture: selected=true submitted=false`，
        //    输入框宽度 1006→1153 正是图片消失的痕迹）。
        // ② `materialAttached()` —— 输入框里挂着素材时，那就是用户此刻要用的内容，
        //    清掉等于毁掉输入（这条本来就写在 `fillText` 的 `materialAttached()` 分支里，
        //    但清残留发生在它**之前**，所以必须在这里再挡一次）。
        val leftoverIsText = leftover.isMeaningfulText()
        val hasMaterial = leftover.isNotBlank() && materialAttached()
        if (leftoverIsText && !hasMaterial) {
            val cleared = clearComposer()
            Log.w("prompt: 输入框有残留文案 len=${leftover.length} → 清空结果=$cleared")
            if (!cleared) {
                Log.w("prompt: 残留文案清不掉 → 不再追加写入，改为直接把残留发出去")
                return clickSendButton()
            }
        } else if (leftover.isNotBlank()) {
            Log.d(
                "prompt: 输入框里的内容不算残留文案（有文字=$leftoverIsText" +
                    " 已挂素材=$hasMaterial）→ 原样保留，绝不清空",
            )
        }
        var filled = awaitCondition(timeoutMs) { fillText(prompt) }
        // **写入必须由权威通道独立回读确认**（1.1.6）。
        //
        // 真机 `evidence/trace_d7_ask` 实证：有一条线程报 `filled=true`，但输入框
        // 从头到尾都是空的（`composerState` 一路 `EMPTY`），随后 `clickSendButton`
        // 正确拒绝点击 → `submitted=false`，而"已写入"的假象让上层以为只差"发送"
        // 这一步失败，于是那一条任务**静默白跑**（正确提示词一直没进过输入框）。
        // 任何通道"谎报成功"都会在这里被拦下：话语权只给权威读数。
        if (filled && !awaitCondition(COMPOSER_TEXT_WAIT_MS) { editorHasText() }) {
            Log.w(
                "prompt: fillText 报成功但权威通道读不到文案 len=${prompt.length}" +
                    " → 判定未写入（交给上层清空/重试，不带着空框去点发送）",
            )
            filled = false
        }
        if (!filled) {
            Log.d("prompt: no editable input for ${prompt.take(40)}")
            return false
        }
        // 给"图片上传/素材就绪"留时间：发太快元宝会回“系统异常，回答生成失败”（用户实测）。
        pause(2_500L)
        val submitted = clickSendButton()
        Log.d("prompt: filled=$filled submitted=$submitted text=${prompt.take(80)}")
        return submitted
    }

    /**
     * 清空元宝输入框并**回读确认**。
     *
     * 只走无障碍 `ACTION_SET_TEXT("")`；写完回读 `composerState`，必须变成 `EMPTY`
     * 才算清干净（写失败却返回成功会让上层把两段文案叠在一起发出去）。
     */
    private fun clearComposer(): Boolean {
        if (!AutomationBridge.clearComposer(context)) return false
        return awaitCondition(COMPOSER_TEXT_WAIT_MS) {
            AutomationBridge.composerState(context) == ComposerState.Text.EMPTY
        }
    }

    /**
     * 写文案，并且**把真正生效的通道名字报出来**。
     *
     * 返回 `null` = 所有通道都没写进去；否则返回通道名（会原样进日志）。
     *
     * 为什么不让各分支自己 `Log.d` 就完事：真机 `evidence/trace_d7_ask` 出现过
     * `filled=true` 但输入框**一直是空的**（`submitted=false`，那一条任务静默白跑），
     * 而日志里**找不到任何通道成功的行** —— 说明有一条 `return true` 绕过了日志。
     * 改成"返回通道名、由唯一出口打一行"之后，任何一次假成功都能被直接指认。
     */
    private fun fillText(value: String): Boolean {
        val channel = fillTextChannel(value)
        Log.d("prompt: fillText channel=${channel ?: "none"} len=${value.length}")
        return channel != null
    }

    private fun fillTextChannel(value: String): String? {
        // Channels are reported explicitly: in a cloned ("双开") host the
        // accessibility service and the Provider are both unreachable, and only
        // the in-process WebView/View/input-connection/root paths can work.
        //
        // **不能点"输入框右侧那个位置"**（用户实测纠正）：那里
        //   **空文案时是"切换语音"** ✗、**有文案时才是"发送"** ✓。
        // 但**点输入框本身**是安全的：`tapEditorCenter` 只取输入框矩形**内部**的安全落点
        // （见 `ComposerGeometry.editorFocusPoint`），解析不出安全落点就放弃、绝不盲点。
        // 1.1.4 起 `writeComposerText` 内部会先做这一步 —— 真机实证：Compose 输入框
        // **必须先拿到焦点**（输入法连上）`ACTION_PASTE` 才会成功，否则 `pasted=false`。
        pause(600L)
        if (fillWebText(value)) return "webview"
        // ⓪ **对话栏优先**（真机 `evidence/trace_c1` 实证）：
        //    元宝 2.87.20 的对话输入框是 Compose 虚拟节点，宿主 View 树里**没有**它。
        //    先让宿主去"递归找第一个 EditText"会写进**页面上别的**输入框
        //    —— 实测：`prompt: text channel=hostEditText` 之后宿主读回 `len=32`，
        //    而元宝输入框 `text=''` 仍然是空的（文案进了侧栏搜索框）。
        //    所以这里先让**无障碍服务**把它看到的那颗 EditText 写上，并且**回读校验**。
        //
        // **1.1.9 修正**：改用 [isComposerLikePage]（对话页 **或 AI写作页**）。
        // 写作页和对话页一样是 Compose 输入框 —— 真机 dump（`evidence/write_noprobe.xml`）
        // 里那颗 EditText 文字为空、宿主 View 树里找不到它，只有无障碍服务看得见：
        // ```
        // 键盘收起 EditText [70,2435][1062,2645] focused=true
        // 键盘弹起 EditText [70,1438][1062,1648] focused=true   ← 位置随键盘上下移动
        // 占位符 TextView '请输入你要写的主题'
        // ```
        // 旧写法用 `isChatComposerPresent()`，它会把含 `选择写作类型` 的写作页**排除**掉，
        // 于是写作页永远走不到这条 Compose 通道 —— 宿主 View 树又找不到那颗 Compose EditText，
        // 结果就是用户报的**"AI写作定位不到输入框"**。
        if (isComposerLikePage()) {
            // **幂等闸**（1.1.5，1.1.6 收紧）。真机 `evidence/trace_d6_ask` 实测：
            // `awaitCondition(timeoutMs) { fillText(prompt) }`（`sendPrompt`）与
            // `selfTestAsk` 的重试都会**再次进入 `fillText`**，而每进一次就再写一遍文案
            // —— `setText` 是整段替换、`paste` 是插入，于是两段提示词被**拼接**成一条发出去
            // （实测输入框里变成 `…按重要程度排序。关于做好…`）。
            //
            // **1.1.6 收紧为"必须是同一条文案"**：旧写法只看"非空"，于是当输入栏里
            // 躺着的是**别路线程写进去的另一段**提示词（`trace_d7_ask` 里自检线程与任务
            // 线程并发写入时就是这个局面）时，它会直接 `return true` 报"写好了"，
            // 而后面的发送校验又发现框里不是我们要发的内容 —— 那一条任务就白跑了。
            // 现在：只有**逐字相同**才认幂等；是别的文案就按"没写进去"处理，
            // 交给上层去清空/重试（`sendPrompt` 开头本来就有清残留的逻辑）。
            val existing = AutomationBridge.composerText(context).orEmpty()
            // 写入时要在最前面补的附图占位符（见下方 ￼ 的说明）。
            var writePrefix = ""
            if (existing.isNotBlank() && existing.trim() != value.trim()) {
                // **P图 编辑页的"图片N"是附图标记，不是残留文案。**
                //
                // 真机实证（1.4.14）：输入框里是 `📷 图片1`，`composerText` 读回 len=1，
                // 幂等闸判成"躺着的是别的文案"→ 直接 return null → 所有通道都放弃
                // （日志 `fillText channel=none`）。可那个框本来就是"附图 + 文案"的结构，
                // 附图标记不是我们要覆盖的内容。
                //
                // 只放过"很短且含图"的情况：用户真写了一句话时（len 更大）仍然按残留处理，
                // 不会把别人的长文案悄悄覆盖掉。
                // 判据**不依赖页面识别、也不假设它是哪个字**。
                //
                // 过程记录：1.4.15 用 `isPictureEditPage()` 判断 —— 页面上确有
                // `text="图片1"`（见 `evidence/uq.xml`），但 `composerText` 走的是
                // 输入框节点通道，读回来的不是页面上那个"图片1"；
                // 1.4.16 又假设它是"图"字（len=1 且 contains 图），仍然没命中 ——
                // 说明那个字符就是个**附图标记的内部表示**（图标/占位符），不是中文字。
                //
                // 所以只按**长度**判断：≤4 个字符 ⇒ 只能是附图标记这类占位内容。
                // 用户真写的文案远长于这个门槛，仍按残留处理，不会被悄悄覆盖。
                val badge = existing.trim()
                val attachmentBadge = badge.length <= 4
                if (attachmentBadge) {
                    // **把附图占位符带进写入内容，否则图片会被替换掉。**
                    //
                    // 真机实证（1.4.17）：`a11ySetText` 是**整段替换**，
                    // 输入框里那个 `￼`（码点 65532，Compose 里表示"一个附件"）
                    // 被一起抹掉了 —— 日志末尾 `文案写入后素材位丢失（图片被替换掉了）`，
                    // 于是"文案发出去了、但图没了"。
                    //
                    // `ComposerState.PLACEHOLDER_CHARS` 里本来就登记了 `￼`
                    // 是"附件占位符"，只是写入路径一直没把它保留下来。
                    writePrefix = badge.filter { it.code == 0xFFFC }.toString()
                    Log.w(
                        "prompt: 输入栏是附图标记（len=${badge.length} " +
                            "codes=${badge.map { it.code }}）→ 保留占位符 " +
                            "${writePrefix.length} 个后写入",
                    )
                } else {
                    Log.w(
                        "prompt: 输入栏躺着的是**别的**文案 len=${existing.length}" +
                            "（本次要写 len=${value.length}）→ 不认幂等，按未写入处理",
                    )
                    return null
                }
            }
            if (existing.isNotBlank() && existing.trim() == value.trim()) {
                Log.w("prompt: 输入栏已是同一条文案 len=${existing.length} → 幂等，不重复写入")
                return "a11yComposer(idempotent)"
            }
            if (writeComposerText(writePrefix + value)) return "a11yComposer(verified)"
        }
        val direct = onMain {
            val input = findPreferredEditText() ?: return@onMain false
            input.requestFocus()
            input.setText(value)
            input.setSelection(input.text?.length ?: 0)
            true
        } == true
        // **宿主 `setText` 对 Compose 输入框是"写了但不算数"**（1.4.20 真机实证）。
        //
        // 元宝的输入栏是 `AndroidComposeView` 内部的输入代理，文本真值在 Compose
        // 自己的状态里；直接对那颗 `EditText` 调 `setText` 只改了 Android 侧副本，
        // Compose 不认，界面照旧是占位符。日志形态：
        // `fillText channel=hostEditText len=41` 紧接着 `composerState state=EMPTY`，
        // 于是外层权威校验判"未写入"→ 整次 ask 白跑（首页语音态必现）。
        //
        // 所以这条通道必须**自己回读**：读得到才算它成功，读不到就继续往下走
        // `writeComposerText` 的无障碍粘贴（那条对 Compose 是有效的）。
        if (direct && awaitComposerText()) return "hostEditText"
        if (direct) {
            Log.w("prompt: host 通道写入后回读为空（Compose 不认宿主 setText）→ 继续走无障碍粘贴")
        }
        // **追加**通道优先于"替换"通道：`commitText` 只把文案插到光标处，
        // 不会清掉输入框里已有的图片附件；而 `ACTION_SET_TEXT` 是**整段替换**，
        // 会把已选的照片（图片1）抹掉 —— 这就是"只看到照片或只看到文案"的原因。
        //
        // 注意：**绝不**在写文案前去点击"可编辑节点"。在这个页面无障碍树里的"可编辑节点"
        // 实际是输入区/添加素材入口，点一下就会把素材选择器重新打开（用户看到的就是
        // "选完素材又点了一次选择素材"），素材同时被顶掉。
        // 已有素材时，用"剪贴板 + 粘贴"追加文案：粘贴是插入语义，不会清空图片附件。
        if (materialAttached()) {
            copyToClipboard(value)
            repeat(2) { attempt ->
                if (AutomationBridge.pasteIntoEditor(context)) {
                    return "clipboardPaste attempt=${attempt + 1}"
                }
                pause(500L)
            }
            val focusedTap = clickPointByViewDispatch(CAPABILITY_EDITOR_X, CAPABILITY_EDITOR_Y)
            Log.d("prompt: tap capability editor to focus dispatched=$focusedTap")
            pause(700L)
            if (AutomationBridge.pasteIntoEditor(context)) return "clipboardPasteAfterFocus"
        }
        // 通用兜底：任何页面（含对话页）都试一次"剪贴板 + 粘贴"，它不依赖输入框类型。
        run {
            copyToClipboard(value)
            // 对话页/写作页的输入框都是 Compose：先**点一下输入框**把焦点给它，再粘贴
            // （否则粘贴无处落）。1.1.9 起把写作页也算进来 —— 它同样是 Compose 输入框。
            //
            // 1.4.20：**首页的 `isComposerLikePage()` 是 false**（它按对话页/写作页/编辑器判），
            // 于是首页从来不聚焦、粘贴必然 `pasted=false`。而首页输入栏默认是"按住说话"
            // 语音态，那个 `EditText` 是隐藏代理，没有焦点就没有粘贴落点。
            // 所以这里改成"只要没有素材就聚焦"—— `focusChatComposer()` 自己走
            // `tapEditorCenter`（节点几何），拿不到安全落点就放弃，不会盲点。
            // 有素材时不碰：那种页面的"可编辑节点"是素材入口，点一下会把选择器重新打开。
            if (isComposerLikePage() || !materialAttached()) {
                focusChatComposer()
            }
            // **这里必须回读**（1.4.21 真机实证）：首页输入栏是 Compose 的输入代理，
            // `ACTION_PASTE` 会返回 `pasted=true` 但文本**没进 Compose 状态**
            // （同一次日志：`pasted=true` 紧跟 `composerState state=EMPTY text=''`）。
            // 旧代码看到 `pasted=true` 就 return，把"没写进去"谎报成功，
            // 外层权威校验再判失败 → ask 在首页必挂（`sent=false`）。
            //
            // **已挂素材时不设回读门槛**：那种页面本来就可能"只发图"，而且不能用
            // `setText` 兜底（整段替换会把图片附件抹掉），把假阴性变成整条链路失败
            // 比"文案没跟上"更糟。
            val hasMaterial = materialAttached()
            if (AutomationBridge.pasteIntoEditor(context) && (hasMaterial || awaitComposerText())) {
                return if (hasMaterial) "clipboardPasteGeneric(material)" else "clipboardPasteGeneric(verified)"
            }
            // 首页这类输入框只有 `ACTION_SET_TEXT` 能真的落字（同一次真机探针：
            // `servicePasteProbe=true → EMPTY`、`a11ySetText=true → HAS_TEXT`）。
            // **有素材时禁止走 setText**：它是整段替换，会把图片附件（￼ 占位符）抹掉。
            if (!hasMaterial && writeComposerText(value)) {
                return "a11yComposer(verified,afterPasteNoop)"
            }
        }
        repeat(3) { attempt ->
            if (commitTextInHost(value)) return "inputConnection(append) attempt=${attempt + 1}"
            pause(500L)
        }
        if (materialAttached()) {
            Log.w("prompt: 输入框已有素材且追加通道不可用 → 跳过替换式写入，避免丢失照片")
            return null
        }
        if (AutomationBridge.setText(context, value)) return "accessibility(replace)"
        // Cloned host: focus the composer the platform dump exposes, then let the
        // host's own input connection carry the text. `input text` cannot type
        // Chinese (it can only synthesise key events), so it is the last resort.
        val focused = AutomationBridge.focusDumpEditor(context)
        if (focused && commitTextInHost(value)) return "inputConnection(focused)"
        if (AutomationBridge.rootSetText(context, value)) return "root focused=$focused"
        Log.d("prompt: no text channel accepted the prompt ${surfaceDiagnostics()}")
        return null
    }

    /**
     * Commits text through the host's own input connection.
     *
     * The injected host process can hand the string straight to the focused
     * editor (Compose composer or EditText), which keeps Chinese prompts intact
     * where the shell's `input text` would produce key events only.
     */
    /** 把文案放进宿主进程的剪贴板，供模块侧 ACTION_PASTE 使用。 */
    private fun copyToClipboard(value: String): Boolean = onMain {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return@onMain false
        clipboard.setPrimaryClip(ClipData.newPlainText("yuanbao_daily_prompt", value))
        Log.d("prompt: clipboard set chars=${value.length}")
        true
    } == true

    private fun commitTextInHost(value: String): Boolean = onMain {
        val root = resumedActivity?.window?.decorView ?: return@onMain false
        val target = focusedEditorView(root) ?: return@onMain false
        val info = EditorInfo()
        val connection = runCatching { target.onCreateInputConnection(info) }.getOrNull()
            ?: return@onMain false
        val committed = runCatching {
            connection.beginBatchEdit()
            val ok = connection.commitText(value, 1)
            connection.endBatchEdit()
            ok
        }.getOrDefault(false)
        Log.d(
            "prompt: input connection view=${target.javaClass.simpleName} " +
                "committed=$committed chars=${value.length}",
        )
        committed
    } == true

    private fun focusedEditorView(root: View): View? {
        val focused = root.findFocus()
        if (focused != null && isEditorView(focused)) return focused
        return firstEditorView(root)
    }

    private fun isEditorView(view: View): Boolean =
        view is EditText ||
            view.javaClass.name.contains("AndroidComposeView") ||
            view.javaClass.name.contains("ComposeView")

    private fun firstEditorView(view: View?): View? {
        if (view == null) return null
        if (isEditorView(view)) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                firstEditorView(view.getChildAt(index))?.let { return it }
            }
        }
        return null
    }

    private fun fillWebText(value: String): Boolean {
        val activity = resumedActivity ?: return false
        val web = onMain { findWebView(activity.window?.decorView) } ?: return false
        val script = "(function(){var v=" + jsQuote(value) + ";var e=document.querySelector('textarea,input[type=text],[contenteditable=\"true\"]');if(!e)return 'no';e.focus();if(e.isContentEditable)e.textContent=v;else e.value=v;e.dispatchEvent(new Event('input',{bubbles:true}));e.dispatchEvent(new Event('change',{bubbles:true}));return 'ok';})()"
        return evaluateJavascript(web, script)?.contains("ok") == true
    }

    /**
     * 点"输入框右侧那颗按钮"。
     *
     * ## ⚠️ 这里曾经是**真机确认的误触源**（1.1.3 及以前的版本）
     *
     * 旧实现按 `editor.right + EDITOR_SEND_OFFSET_PX(90)` 算落点。真机实测
     * （`evidence/trace_c1`，元宝 2.87.20 / 1272×2800）：
     *
     * ```text
     * 输入框 [210,2435][922,2645]  →  922 + 90 = 1012
     * 麦克风 '切换语音' [950,2498][1034,2582]   ← 1012 正落在它内部！
     * TAPTRACE #3 action=hostViewDispatch xy=1012,2540 ok=true
     * 点后 dump：TextView '按住 说话' + ImageView desc='切换键盘'   ← 被切进语音模式 ✗
     * ```
     *
     * 点下去会把界面切到"按住 说话"，而且**语音模式是粘性的**
     * （`am force-stop` 重启都还在）—— 这就是用户说的
     * "输入框还没有文案时点了切换语音的按钮"。
     *
     * 现在**彻底删掉坐标兜底**：只走服务进程那两条**带 ComposerSendGuard 结构闸**
     * 的节点通道（先结构判定，取不到就认定失败）。
     */
    private fun clickButtonRightOfEditor(): Boolean {
        // ① 模块侧无障碍树里"与输入框同排、位于其右侧"的可点节点（宿主拿不到平台 dump）。
        if (AutomationBridge.clickSendRightOfEditor(context)) {
            Log.d("prompt: send key clicked by module near-editor rule")
            return true
        }
        // ② 按无障碍树实测的输入栏真实几何点动作键（同样经过结构闸）。
        if (AutomationBridge.tapSendByGeometry(context)) {
            Log.d("prompt: send key clicked by composer geometry")
            return true
        }
        // **不做任何坐标兜底**：那颗按钮旁边紧挨着 ➕ 和 🎤，按偏移猜必然踩雷。
        Log.w("prompt: 右侧发送键两条结构通道都取不到 → 放弃（不做坐标兜底，避免点到切换语音 ✗）")
        return false
    }

    /**
     * 输入框里是否已经有文字（决定"能不能点发送"）。
     *
     * ## 为什么必须**多通道合并**（本次修复的核心）
     *
     * 用户反馈："**还是找不到问元宝对话页面的输入框**"。真机取证（元宝 2.87.20）确认，
     * 对话栏输入框是一个 **Compose 虚拟节点**：
     *
     * ```text
     * <node NAF="true" text="" resource-id="" class="android.widget.EditText"
     *       package="com.tencent.hunyuan.app.chat" focused="true"
     *       bounds="[210,2435][922,2645]" hint=""/>
     * ```
     *
     * 旧实现只走"View 树里递归找 `EditText`"，而承载它的是 `AndroidComposeView` ——
     * **永远找不到** ⇒ `editorHasText()` **恒为 false** ⇒
     * 「对话页等待文案就绪 ready=false → 不点发送」：文案明明写进去了也永远发不出去。
     *
     * ## 通道优先级（真机 `evidence/trace_c1` 实证）
     *
     * 1. **无障碍服务进程的 [ComposerState]（权威）**：
     *    - `HAS_TEXT` → 有文案；
     *    - `EMPTY` / `VOICE_MODE` → **直接判 false，不看别的通道**。
     *
     *    ⚠️ 这一步必须**压过**宿主的读数。真机慢速扫描抓到过反例：
     *    侧栏展开时 `findPreferredEditText()` 读到一个 **32 字**的原生 EditText
     *    （侧栏搜索框/AI 会话残留），于是 `editorHasText()=true` 放行，
     *    模块接着去点"输入框右侧那颗" → 落到麦克风 `[950,2498][1034,2582]` 上，
     *    元宝被切进 `按住 说话` 语音模式，消息永远发不出（`TAPTRACE #3 xy=1012,2540`
     *    之后的 dump 里出现 `TextView '按住 说话'` + `ImageView desc='切换键盘'`）。
     *    也就是说：**宿主读到的"有文案"可能来自页面上完全另一个输入框**。
     * 2. 无障碍读不到（`UNKNOWN` / 通道不可达）时，才退回宿主的两个通道：
     *    `InputConnection` → 原生 `EditText`。
     *
     * 方向性：只有"确认是空框/语音模式"或"所有通道都读不到"才返回 false。
     * 宁可漏发一次，也绝不误触"切换语音"。
     */
    private fun editorHasText(): Boolean {
        // ① 权威：无障碍服务直接读元宝那颗 Compose 输入框。
        //
        // **先等它可用**（1.1.4 的关键修复）：模块自己的 `ui dump` 走 `uiautomator`，
        // 而在这台 ColorOS 上一启动 uiautomator **系统就把我们自己的无障碍服务销毁**，
        // 约 1 秒后才重连（logcat 实证，见 `AutomationAccessibilityService.perform`）。
        // 这段时间 `composerState` 返回 null —— 旧代码会立刻掉进下面②/③两条
        // **对 Compose 输入框完全无效**的宿主通道，拿到"页面上另一个 EditText"的假读数，
        // 把**空的**输入栏判成"有文案"，接着去点右侧那颗按钮，
        // 而空框时那颗正是 **切换语音** ✗（用户报的误触）。
        var verdict: ComposerState.Text? = null
        awaitCondition(A11Y_STATE_WAIT_MS) {
            verdict = AutomationBridge.composerState(context)
            verdict != null
        }
        when (val state = verdict) {
            ComposerState.Text.HAS_TEXT -> {
                Log.d(
                    "prompt: editorText channel=a11y HAS_TEXT " +
                        "text='${AutomationBridge.composerText(context).orEmpty().take(30)}'",
                )
                return true
            }
            ComposerState.Text.EMPTY -> {
                Log.d("prompt: editorText a11y=EMPTY（权威）→ false")
                return false
            }
            ComposerState.Text.VOICE_MODE -> {
                Log.w("prompt: editorText a11y=VOICE_MODE（输入栏停在按住说话）→ false")
                return false
            }
            null -> {
                // 服务在等待窗口内仍不可用：**保守判为无文案，且不回退宿主通道**。
                // 宿主唯一能找到的 EditText 不是这颗输入栏（实测是侧栏搜索框），
                // 拿它的读数来"证明有文案"会反过来造成误触。
                Log.w(
                    "prompt: editorText 无障碍服务不可用（等了 ${A11Y_STATE_WAIT_MS}ms）" +
                        "→ 保守判无文案，不回退宿主通道",
                )
                return false
            }
            // UNKNOWN：服务是好的，只是这颗节点树判不出来 → 允许退回宿主通道。
            ComposerState.Text.UNKNOWN -> Unit
        }
        // ② 宿主 InputConnection：直接问焦点编辑器要当前文案。
        editorTextViaInputConnection()?.let { text ->
            if (text.isMeaningfulText()) {
                Log.d("prompt: editorText channel=inputConnection len=${text.length}")
                return true
            }
        }
        // ③ 原生 EditText（Compose 页恒为 null）。
        val native = onMain { findPreferredEditText()?.text?.toString() }
        if (native != null && native.isMeaningfulText()) {
            Log.d("prompt: editorText channel=hostEditText len=${native.length}")
            return true
        }
        Log.d("prompt: editorText 三通道均无文案 native=${native != null}")
        return false
    }

    /**
     * 通过宿主焦点编辑器的 `InputConnection` 读回当前文案。
     *
     * Compose 的 `AndroidComposeView` 实现了 `onCreateInputConnection`，会把**当前焦点
     * 文本框**的会话交出来 —— 这是宿主进程里唯一能读到 Compose 文案的办法。
     * 读不到（没有焦点编辑器 / 不是文本框）返回 `null`，与"读到空串"区分开：
     * 前者是"不知道"，后者是"确认空"。
     */
    private fun editorTextViaInputConnection(): String? = onMain {
        val root = resumedActivity?.window?.decorView ?: return@onMain null
        val target = focusedEditorView(root) ?: return@onMain null
        val connection = runCatching { target.onCreateInputConnection(EditorInfo()) }.getOrNull()
            ?: return@onMain null
        val before = runCatching { connection.getTextBeforeCursor(EDITOR_READ_LIMIT, 0) }
            .getOrNull()?.toString()
        val after = runCatching { connection.getTextAfterCursor(EDITOR_READ_LIMIT, 0) }
            .getOrNull()?.toString()
        if (before == null && after == null) null else before.orEmpty() + after.orEmpty()
    }

    /**
     * 把文案写进**元宝对话栏那颗 Compose 输入框**，并回读确认。
     *
     * 只用两条"服务端看得见输入框"的通道，每条写完都回读：
     * 1. `ACTION_SET_TEXT`（服务端直接对那颗 EditText 节点写）；
     * 2. 剪贴板 + `ACTION_PASTE`（插入语义，不会清空已有素材）。
     *
     * **关键区别**：老代码写完就 `return true`，于是"写进侧栏搜索框"也会被当成成功 ——
     * 后面 `editorHasText()` 判 false → 永远不发；或者反过来误判有文案 → 去点麦克风。
     * 现在**没有回读确认就不算成功**。
     */
    private fun writeComposerText(value: String): Boolean {
        // ① 先试"直接写"：`ACTION_SET_TEXT`（服务端直接对那颗 Compose EditText 写）。
        if (AutomationBridge.setText(context, value) && awaitComposerText()) {
            Log.d("prompt: composer 写入成功 channel=a11ySetText")
            return true
        }
        // ② **没有焦点就没有粘贴对象**（1.1.4 真机实证的关键区别）：
        //    同一个 `pasteIntoEditor`，在"输入法已连上（输入框真聚焦）"时返回 `pasted=true`
        //    且文案确实进框（`composerState=HAS_TEXT text='自检写入composer123'`），
        //    而在"输入法没起来"时返回 `pasted=false`、框里始终是空的。
        //    差别只在于**输入框有没有真的被聚焦**，所以写入前必须先把它点开
        //    —— 但只点"输入框本身"的安全落点（`tapEditorCenter` 拿不到安全落点就放弃）。
        val alreadyFocused = AutomationBridge.composerState(context) == ComposerState.Text.HAS_TEXT
        if (!alreadyFocused) {
            val focused = focusChatComposer()
            Log.d("prompt: composer 聚焦 dispatched=$focused")
            if (focused) pause(1_200L)
        }
        copyToClipboard(value)
        repeat(3) { attempt ->
            if (AutomationBridge.pasteIntoEditor(context) && awaitComposerText()) {
                Log.d("prompt: composer 写入成功 channel=clipboard+paste attempt=${attempt + 1}")
                return true
            }
            // 粘贴失败多半是焦点掉了（点击被输入法吃掉 / 页面刚重绘）：补一次聚焦再试。
            if (attempt < 2) {
                focusChatComposer()
                pause(900L)
            }
        }
        Log.w("prompt: composer 写入失败（setText / clipboard+paste 均未通过回读校验）")
        return false
    }

    /** 等元宝输入框真的出现文案（服务端回读）。 */
    private fun awaitComposerText(): Boolean = awaitCondition(COMPOSER_TEXT_WAIT_MS) {
        AutomationBridge.composerState(context) == ComposerState.Text.HAS_TEXT
    }

    /**
     * 发送后是否**真的**清空。
     *
     * 必须"看到输入框 **且** 它是空的"（`EMPTY`）才算数。**不能**用 `!editorHasText()`：
     * 那个在输入栏读不到时会回落到宿主通道并返回 false，于是 `UNKNOWN`
     * （输入栏整块消失 —— 实测是误开了 `＋` 附件面板）也被算成"已清空"，
     * 上层就会把一次**没发出去**的消息当成成功（`evidence/trace_d3_fill_send` 实证）。
     */
    private fun composerCleared(): Boolean =
        AutomationBridge.composerState(context) == ComposerState.Text.EMPTY

    /** 输入栏那颗 `EditText` 还在树上吗；`null` = 服务不可用（未知）。 */
    private fun composerEditorPresent(): Boolean? = AutomationBridge.composerEditorFound(context)

    /**
     * 点击发送**之前**等输入框真的有文案。
     *
     * ## 为什么不是简单的 `awaitCondition(10_000L) { editorHasText() }`
     *
     * 真机 `evidence/trace_d7_ask` 实测：输入框**明确是空的**（`EMPTY`，服务端一遍遍
     * 读回 `editor=[210,2435][922,2645] text=''`）时，旧写法照样要空转满 10 秒 ——
     * 一次发送就白烧 10s，还产生 39 次全树遍历的 binder 往返（每次约 265ms，
     * 日志被打成一片）。空框**等多久都不会长出文案**，所以这里按权威三态分流：
     *
     * - `HAS_TEXT` → 立刻放行（右侧那颗就是发送 ⬆ ✓）；
     * - `EMPTY` / `VOICE_MODE` → **立刻放弃**，不点（空框时右侧是切换语音 ✗）；
     * - `UNKNOWN` 或服务暂不可用 → 才短轮询，因为那是"输入栏正在重绘/服务刚重连"，
     *   等一会儿确实可能变好。
     *
     * 无论哪条路径都只打**一行**汇总日志。
     */
    private fun awaitComposerReadyForSend(where: String): Boolean {
        val deadline = SystemClock.uptimeMillis() + SEND_READY_WAIT_MS
        var unknown = 0
        var last: ComposerState.Text? = null
        while (true) {
            val state = AutomationBridge.composerState(context)
            last = state
            when (state) {
                ComposerState.Text.HAS_TEXT -> {
                    Log.d(
                        "prompt: ${where}文案就绪（a11y HAS_TEXT，重试 $unknown 次）" +
                            " text='${AutomationBridge.composerText(context).orEmpty().take(30)}'",
                    )
                    return true
                }
                ComposerState.Text.EMPTY -> {
                    // 权威读数是"看到输入框且它是空的"→ 确定没文案，等下去也没有。
                    Log.w(
                        "prompt: ${where}输入框明确为空（a11y EMPTY）→ 立刻放弃发送，" +
                            "不空转、不点右侧按钮（空框时那是切换语音 ✗）",
                    )
                    return false
                }
                ComposerState.Text.VOICE_MODE -> {
                    Log.w("prompt: ${where}输入栏停在「按住说话」→ 放弃发送（避免误触）")
                    return false
                }
                else -> Unit // UNKNOWN / null：服务不可用或输入栏正在重绘，值得再等
            }
            unknown++
            if (SystemClock.uptimeMillis() >= deadline) break
            pause(SEND_READY_POLL_MS)
        }
        // ② 无障碍状态**始终**读不出来 → 必须回落到 [editorHasText] 的多通道判据。
        //
        // 真机 `evidence/trace_r1_writing` 实证（**这是本人 1.1.6 引入的回归，勿删**）：
        // 写作页/能力页的无障碍树本来就判不出输入框状态，`composerState` 一路 `UNKNOWN`。
        // 1.1.6 只把这一条读数的轮询从 10s 收紧到 3s 就返回 false，于是**写作直接发不出去**：
        //
        // ```text
        // prompt: 能力页等 3000ms 仍读不到确定状态（最后一次=UNKNOWN，UNKNOWN 9 次）→ 保守放弃发送
        // ```
        //
        // 旧写法用的是 `editorHasText()` —— 它在 `UNKNOWN`（服务活着、只是这棵树读不出）
        // 时会继续试宿主 `InputConnection` / 原生 `EditText`，能力页正是靠那两条才能发送。
        // 收紧的是**性能**（空框不再空转），不能顺手把**能力**也砍掉，所以这里补回落。
        //
        // 注意 [editorHasText] 的语义与这里不冲突：它在"服务不可用（null）"时**不会**回落，
        // 所以对话页那条"空框被误判有文案"的老误触依然被堵住。
        val fallback = editorHasText()
        Log.w(
            "prompt: ${where}无障碍状态始终读不出（UNKNOWN $unknown 次，最后一次=$last）" +
                " → 回落多通道判据 hasText=$fallback",
        )
        return fallback
    }

    /** 输入框里是否已经有文字（用于决定要不要走"相邻按钮"这种有误触风险的兜底）。 */

    private fun clickSendButton(): Boolean {
        // ⓪ 前台若有**对话框**（实测 P图 粘贴后会出现 AlertDialog，把 ⬆ 挡住），先精确点它的确认键。
        dismissBlockingDialog()
        // ⓪' **能力页（P图/写作）**：右侧就是发送 ⬆（用户截图实测 ≈0.865/0.565），
        //     这里没有 "+"/麦克风 的误触风险，直接点 —— 不要用"输入框有文字"做前置判断
        //     （Compose 输入框读不到文字，会把这一步误跳过）。
        val screenText = readScreenText(logSample = false)
        // **必须先排除对话页**：对话页的建议文案里也可能含"图片/创作"等词，误判成能力页会让
        // 模块去点 (0.865,0.565)（对话页那里是消息区），把刚写进去的文案弄空（用户实测）。
        val onChatPage = isChatComposerPresent()
        val capabilityPage = !onChatPage && (
            screenText.contains("添加创作素材") ||
                screenText.contains("智能P图") ||
                screenText.contains("图片1") ||
                screenText.contains("选择写作类型") ||
                screenText.contains("写作")
            )
        if (capabilityPage) {
            // ⓪ **必须先等到输入框里真的有文案再点发送**（用户实测纠正）：
            //   没文案时那个位置是"切换语音" ✗（点下去会变成语音界面，消息永远发不出）；
            //   只有**有文案**时它才是真正的"发送 ⬆" ✓。
            val hasText = awaitComposerReadyForSend("能力页")
            if (!hasText) return false
            // ① **写作页**：发送键同样按**无障碍树真实几何**定位（不用写死坐标）。
            if (WritingPage.looks(screenText)) {
                val w = AutomationBridge.tapSendByGeometry(context)
                Log.d("prompt: 写作页点发送(真实几何) tapped=$w")
                // 同样**必须回读确认清空**（见下面能力页兜底的说明）：`tapped=true`
                // 只代表手势发出去了，写作页那颗 `全屏输入` 就能让这里"看起来点到了"。
                if (w && awaitCondition(SEND_CLEAR_WAIT_MS) { composerCleared() }) {
                    // **发送成功（框被清空）→ 立刻返回！**
                    // 绝不能继续走下面的"备用/重试"✗：此刻框已空，同一个位置
                    // 会变成"🎙 切换语音"，再点一次就把页面切成语音模式 ✗（实测：截图里出现"按住 说话"）。
                    Log.d("prompt: 写作页发送成功（输入框已清空）→ 结束，不再补点")
                    return true
                }
                Log.w("prompt: 写作页点发送后输入框未清空 → 继续走结构性/几何补点")
            }
            // ② 与对话页同一套：**结构性**找"编辑框右侧同一行"的按钮并真实触摸（已验证有效 ✓）。
            //    ⚠️ 只在**框里确实还有文案**时才点：框空时同位置是"切换语音" ✗。
            if (editorHasText()) {
                val structural = AutomationBridge.clickSendRightOfEditor(context)
                Log.d("prompt: 能力页点发送(结构性) tapped=$structural")
                if (structural) {
                    pause(2_000L)
                    if (!editorHasText()) {
                        Log.d("prompt: 能力页发送成功（编辑框已清空）→ 结束，不再补点")
                        return true
                    }
                }
            } else {
                Log.d("prompt: 能力页框已空 → 跳过结构性补点（避免点到切换语音 ✗）")
            }
            // ② 兜底：**按真实几何**点"与输入框同一行、位于其右侧"的按钮。
            //    不再用写死的 (0.865,0.565) —— 该比例在不同机型/键盘状态下会偏出按钮，
            //    落到页面其它控件上（误触）。
            if (editorHasText()) {
                val arrow = AutomationBridge.tapSendByGeometry(context)
                Log.d("prompt: 能力页点发送(真实几何) dispatched=$arrow")
                if (arrow) {
                    // **必须回读确认**（1.1.9，`evidence/trace_w5` 实证）：
                    // `dispatched=true` 只说明"手势派出去了"，**不代表消息真的发出**。
                    // 实测写作页三次点击全打在 `全屏输入` 那颗 70×70 节点上，
                    // 输入框里文案**一字未少**，却被这里直接 `return true` 报成 `submitted=true`
                    // —— 一个纯白跑的任务。改成：只有**输入框真的清空**才算发送成功。
                    if (awaitCondition(SEND_CLEAR_WAIT_MS) { composerCleared() }) {
                        Log.d("prompt: 能力页发送成功（编辑框已清空）→ 结束")
                        return true
                    }
                    Log.w("prompt: 能力页点发送后输入框未清空 → 不算发送成功（避免假成功）")
                }
            }
        }
        // ① 对话页：发送 = **输入框那一行、它右边那个节点的中心**。
        if (onChatPage) {
            // ⓪ 同样**先等文案就绪**再点（没文案时右侧是"切换语音" ✗）。
            val ready = awaitComposerReadyForSend("对话页")
            if (!ready) return false
            // ⚠️ 这里**绝不能**先去点输入框/右侧（用户实测）：空文案时那个位置是
            //   "切换语音" ✗（会把输入框变成"按住 说话"）和 "快速"（弹模型菜单）✗。
            //   文案此时**已经写入** ✓ → 右侧那个节点就是发送 ⬆ ✓。
            //
            // **只用真实几何**：模块侧解析无障碍树里输入框与同行按钮的实测 bounds 并点击，
            // 派发前还会校验落点是否在输入栏安全区内。**不再使用写死的 (0.87,0.973)** ——
            // 实测该比例在本机落在输入框下方 79px，正是"点着点着按到别的东西"的来源。
            var arrow = AutomationBridge.tapSendByGeometry(context)
            Log.d("prompt: 对话页点发送(真实几何) tapped=$arrow")
            // 备用：结构性找"输入框右侧同一行"的节点（仍然是节点驱动，不是坐标）。
            if (!arrow) {
                arrow = AutomationBridge.clickSendRightOfEditor(context)
                Log.d("prompt: 对话页点发送(节点中心备份) tapped=$arrow")
            }
            // ③ 自我判定：点完等输入框变空 = 真的发出去了。
            //    **必须**用 [composerCleared]（看到输入框 + 空），不能用 `!editorHasText()` ——
            //    后者在输入栏被替换（`editor=null`）时也返回 false 变化来的"已清空"，
            //    会把"点了 ＋ 打开附件面板"误判成"消息发送成功"（trace_d3 实证）。
            var cleared = awaitCondition(SEND_CLEAR_WAIT_MS) { composerCleared() }
            val editorPresent = composerEditorPresent()
            Log.d("prompt: 对话页发送结果 arrow=$arrow 输入框已清空=$cleared editorPresent=$editorPresent")
            if (!cleared && editorPresent == false) {
                // 输入栏整块消失了：不是"清空"，而是被替换/页面切走。
                // **绝不能再补点**（补点会落在已经翻转语义的按钮上 ✗），直接判失败交上层。
                Log.w("prompt: 发送后输入栏整块消失（editor=null，多半误开了 ＋ 附件面板）→ 不补点，判失败")
                return false
            }
            if (!cleared) {
                // ④ 只再补一次"节点中心"，不做任何坐标点击（避免点到 ➕ ✗）。
                arrow = AutomationBridge.clickSendRightOfEditor(context)
                Log.d("prompt: 对话页点发送(节点中心重试) tapped=$arrow")
                cleared = awaitCondition(SEND_CLEAR_WAIT_MS) { composerCleared() }
                Log.d("prompt: 对话页发送重试结果 tapped=$arrow 输入框已清空=$cleared")
            }
            if (!cleared) {
                // ⑤ 最后再按真实几何补一次（新解析的落点可能和第一次不同：输入框在有文案后
                //    才展开成 `[70,2435][866,2645]`，第一次点击可能发生在布局切换的那一帧）。
                arrow = AutomationBridge.tapSendByGeometry(context)
                Log.d("prompt: 对话页点发送(几何补点) tapped=$arrow")
                cleared = awaitCondition(SEND_CLEAR_WAIT_MS) { composerCleared() }
            }
            if (cleared) return true
            // ⚠️ **对话页不再用回车兜底**：元宝这颗 Compose 输入框**把回车当换行**。
            //    真机 `evidence/trace_c2` 实测：发送键没点中之后走回车，输入框里变成
            //    `自检写入composer123\n`（多了一个换行）而消息**没有发出**。
            //    宁可报失败让上层重试，也不要把输入框搞脏。
            Log.w("prompt: 对话页发送未成功（不再用回车，避免插入换行）→ 交给上层重试")
        }
        // ② 宿主 View 树找发送键 `performClick()`（真实 id/描述 + 干扰键黑名单 ✓）：
        //    对话页也允许 —— 回车失败时（Compose 有时不认 keyEvent）靠它兜底。
        if (clickSendButtonViaViewTree()) {
            // **必须回读确认**（1.1.6）。`performClick()` 返回 true 只说明"事件派发出去了"，
            // 不等于消息真的发出去了。真机 `evidence/trace_e3_ask` 实证过整条"派发成功但
            // 输入框一直是 HAS_TEXT"的链路被当成成功上报（`submitted=true`），
            // 上层于是不再重试 —— 那一条任务就这么静默丢了。
            if (awaitCondition(SEND_CLEAR_WAIT_MS) { composerCleared() }) {
                Log.d("prompt: send channel=viewTree(verified)")
                return true
            }
            Log.w("prompt: View 树发送键已派发但输入框没清空 → 不算成功，继续走后面的通道")
        }
        // 对话页到此为止：上面已经试过"回车 + View 树发送键"，不再走任何"按 id / 相邻按钮 /
        // 坐标"的兜底 —— 那一排紧挨着就是 ➕/🎤，会误触（用户实测）。
        if (onChatPage) {
            // 兜底：**仍然只走真实几何**（输入框那一行的右侧按钮）。
            // **不再用 (0.87, row) 这类比例坐标** —— 实测会落到输入框下方/底栏，造成误触。
            val byGeometry = AutomationBridge.tapSendByGeometry(context)
            Log.d("prompt: 对话页兜底点发送(真实几何) tapped=$byGeometry")
            // **同样必须回读**（1.1.6）。这一步以前是"tapped 就算成功"，
            // 真机 `trace_e3_ask` 里它正是把"完全没发出去"报成 `submitted=true` 的那一步：
            // 当时侧栏展开、输入框被推到屏幕外，落点 x=2087 超出 1272 宽的屏幕，
            // 手势"派发成功"但什么都没点到 —— 却因为只看返回值被当成发送成功。
            if (byGeometry && awaitCondition(SEND_CLEAR_WAIT_MS) { composerCleared() }) {
                Log.d("prompt: 对话页兜底发送成功（输入框已清空）")
                return true
            }
            Log.w("prompt: 对话页发送未成功（含兜底几何点）→ 不做其它点击（避免误触 ➕/切换语音）")
            return false
        }
        if (clickDirectIds(SEND_IDS)) {
            Log.d("prompt: send channel=hostViewId")
            return true
        }
        if (AutomationBridge.clickIds(context, SEND_IDS)) {
            Log.d("prompt: send channel=accessibilityId")
            return true
        }
        if (clickVisible(SEND_TEXT)) {
            Log.d("prompt: send channel=visibleText")
            return true
        }
        // 关键：**不要反复点**！文案粘贴后右侧才会从 ➕/🎤 变成 ⬆️，
        // 反复重试会连点 "+"（实测导致误触变多）。这里只"等"不"点"，然后点一次。
        pause(2_200L)
        val hasText = editorHasText()
        if (!hasText) {
            Log.d("prompt: 输入框还是空的，先不点发送（避免误触 ➕）")
        } else if (clickButtonRightOfEditor()) {
            Log.d("prompt: send channel=rightOfEditor")
            return true
        }
        // 兜底：仅在输入框确实有文字时才试"相邻按钮"（空输入框旁边就是 ➕，会误触）。
        if (hasText && clickAdjacentActionButton()) {
            Log.d("prompt: send channel=adjacentButton")
            return true
        }
        // 能力页（P图/写作）专用兜底：发送 ⬆ 就在编辑框右侧同一行 —— **用真实几何定位**，
        // 不再用写死的 (0.865,0.565)（比例与真实布局不一致时会偏出按钮 → 误触）。
        if (hasText && AutomationBridge.tapSendByGeometry(context)) {
            Log.d("prompt: send channel=capabilityGeometry")
            return true
        }
        // 注意：**非对话页不能用回车**（用户实测：P图/写作页的回车是"换行"，不会发送）。
        Log.d("prompt: no send channel worked（非对话页禁用回车）${surfaceDiagnostics()}")
        return false
    }

    /**
     * 是否在**对话页**。
     *
     * 判据：屏幕下方（y > 80%）存在输入框 —— 对话页的输入框在最底部；
     * 能力页（P图/写作）的编辑框在中部。
     * 不能用"发消息或按住说话"判定：那是**提示文字**，不在无障碍文本里（实测踩过）。
     */
    /**
     * 是否在**对话页**。
     *
     * 判据：存在输入框，且页面上**没有能力页特征**。
     * （不能再用"输入框靠底部"判定：键盘弹出后输入框会上移到 row≈0.61 ✗，
     * 之前 >0.75 的条件会把对话页判掉 → 走到别的分支 → 发不出去 ✗。）
     */
    private fun isChatComposerPresent(): Boolean {
        if (chatComposerRowFraction() == null) return false
        val text = readScreenText(logSample = false)
        return !text.contains("智能P图") && !text.contains("添加创作素材") &&
            !text.contains("图片1") && !text.contains("选择写作类型")
    }

    /**
     * "Compose 输入栏"类页面：**对话页 或 AI写作页**。
     *
     * 这两处的输入框都是 **Compose 虚拟节点** —— 宿主 View 树里找不到，
     * 只有无障碍服务看得见，所以写入都必须走"聚焦 + 粘贴/直写 + 回读"那条通道。
     *
     * 为什么单独抽一个函数：旧代码用 [isChatComposerPresent] 做这个判断，
     * 而它**故意排除**含 `选择写作类型` 的写作页（那是为了区分"能力页"的发送分支），
     * 于是写作页永远进不了 Compose 通道 → 宿主又找不到那颗 EditText →
     * 用户报的 **"AI写作定位不到输入框"**（`evidence/trace_w2` 之前的实测）。
     *
     * 写作页判据取自真机 dump（`evidence/write_probe.xml` / `write_nokb.xml`）：
     * 文案 `选择写作类型`、输入框占位符 `请输入你要写的主题`。
     * 注意不能用单独的 `AI写作` 判定 —— 主页那一行快捷入口里也有 `AI写作`。
     */
    private fun isComposerLikePage(): Boolean =
        isChatComposerPresent() || isWritingPage() || isPictureEditPage()

    /**
     * P图 编辑页（挂上素材之后的那一屏）。
     *
     * 为什么必须单独判：它的输入框同样是 **Compose 虚拟节点**，
     * 而 [isChatComposerPresent] **故意排除**含「智能P图 / 添加创作素材 / 图片1」的页面
     * （那是为了把"能力页"和"对话页"分开）。结果 P图 编辑页被排除在 Compose 通道之外，
     * `fillText` 退到 `clipboardPaste` —— 真机实证（1.4.12）：粘贴报成功，
     * 但三通道都读不到文案，最后判定"未写入"，文案发给了一个空输入框。
     *
     * 判据都用"挂图之后才会出现的东西"，避免在选图前的 P图 首页误判。
     */
    private fun isPictureEditPage(): Boolean {
        val text = readScreenText(logSample = false)
        if (text.isBlank()) return false
        val hasAttachment = text.contains("图片1") || text.contains("图片2") ||
            text.contains("图片3")
        val editorWords = text.contains("智能P图") || text.contains("描述你想怎么改") ||
            text.contains("发消息或按住说话")
        return hasAttachment && editorWords
    }

    /** 对话页底部输入框中心 y（像素，由模块无障碍服务直接探测；不需要 root）。 */
    private fun chatComposerRowFraction(): Float? = AutomationBridge.bottomEditorRowFraction(context)

    /**
     * 侧栏（抽屉）是否处于展开状态。
     *
     * 判据是 [WelfareNav.isDrawerOpen]（纯函数，有单测）：
     * 必须"看到福利中心行" **且** "入口节点在屏幕右侧"两条同时满足。
     * 只满足第一条不够 —— 首页自带的福利卡片会让它误命中。
     */
    private fun drawerOpen(): Boolean =
        runCatching {
            WelfareNav.isDrawerOpen(AutomationBridge.uiNodes(), screenWidthPx())
        }.getOrDefault(false)

    /**
     * 收起侧栏并**回读确认**。
     *
     * 为什么不能只按一次返回键就走：侧栏展开时主窗口被推到屏幕外，此时所有几何
     * 都不可信，必须确认真的收起来了才允许继续操作输入框
     * （`evidence/trace_e3_ask` 就是因为没确认，在 1272 宽的屏幕上往 x=2087 点了 4 次）。
     */
    private fun closeDrawer(): Boolean {
        repeat(2) { attempt ->
            AutomationBridge.back(context)
            pause(1_300L)
            if (!drawerOpen()) {
                Log.d("prompt: 侧栏已收起（第 ${attempt + 1} 次返回键）")
                return true
            }
        }
        return false
    }

    /**
     * 点击对话页输入框以取得焦点。
     *
     * **必须走模块的无障碍手势**：宿主的 `resumedActivity` 是 ViewGroup（实测），
     * 进程内派发只会"点个空"。
     *
     * 1.1.1 修正：元宝的 Compose 输入框**时有时无**（同一对话页有时解析不出来），
     * 失败时不再盲点写死坐标（那会误触 `+` / 切换语音），而是：
     * 1) 强制刷新一次无障碍树快照，再试一次；
     * 2) 两次都拿不到安全落点就**返回 false**，让调用方走别的输入通道。
     */
    private fun focusChatComposer(): Boolean {
        repeat(2) { attempt ->
            // 1.1.4 修正：第二次**不再调 `uiNodes(force = true)`**。
            // 那条路会跑 `uiautomator dump`，而在这台 ColorOS 上一启动 uiautomator
            // 系统就把我们自己的无障碍服务销毁 1 秒 —— 越"刷新"越读不到，
            // 正好解释了过去"强制刷新一次还是解析不到输入框"。
            // 无障碍树本来就是每次实时读取（`withAllRoots` 无缓存），
            // 只需要给它一点时间让输入法/页面稳定即可。
            if (attempt > 0) pause(700L)
            val tapped = AutomationBridge.tapEditorCenter(context)
            if (tapped) {
                Log.d("prompt: focus chat composer (node geometry) dispatched=true attempt=${attempt + 1}")
                pause(900L)
                return true
            }
            Log.w("prompt: focus chat composer attempt=${attempt + 1} 未拿到安全落点（不盲点）")
        }
        Log.w("prompt: focus chat composer 放弃（解析不到输入框安全落点）")
        return false
    }

    private fun unusedIsChatComposerPresent(): Boolean {
        val height = screenHeightPx()
        if (height <= 0) return false
        val nodes = AutomationBridge.uiNodes()
        return nodes.any { node ->
            node.className.contains("EditText") && node.box.centerY > height * 0.8f
        }
    }

    /** 对话页输入框所在行的纵向比例（从无障碍树里的 EditText 节点实测，取不到则 null）。 */
    private fun chatComposerRow(): Float? {
        val nodes = AutomationBridge.uiNodes()
        val editor = nodes.filter { it.className.contains("EditText") }
            .maxByOrNull { it.box.height } ?: return null
        val center = (editor.bounds.top + editor.bounds.bottom) / 2f
        val height = screenHeightPx().toFloat()
        if (height <= 0f) return null
        Log.d("prompt: chatComposerRow bounds=${editor.bounds} centerY=$center")
        return (center / height).coerceIn(0.05f, 0.98f)
    }

    /**
     * 屏幕**真实宽度**（像素，含导航栏）。
     *
     * 所有"左半屏/右半屏""是不是跨屏大容器"的判据都必须用它，
     * 绝不能用开发机上量出来的绝对像素 —— 那正是"在我机器上正常、
     * 到小米 14 Pro（1080 宽）上就点不中"的根源。
     */
    /** 上次 OCR 时间，用于冷却（uptimeMillis）。 */
    @Volatile
    private var lastOcrAtMs = 0L

    /**
     * 盲区时才跑 OCR，把识别到的文字接回读屏结果。
     *
     * 返回 null 表示**这次没跑 / 跑了但不可用**，调用方用原来的无障碍文本即可。
     */
    private fun ocrFallbackText(accessibilityText: String): String? {
        val now = SystemClock.uptimeMillis()
        if (!OcrFallbackPolicy.shouldRun(accessibilityText, lastOcrAtMs, now)) return null
        lastOcrAtMs = now
        val result = runCatching { AutomationBridge.ocrRead(context) }.getOrNull() ?: return null
        if (!result.ok) {
            Log.w("ocr: 不可用（${result.reason}）→ 继续用无障碍结果")
            return null
        }
        if (result.items.isEmpty()) {
            Log.w("ocr: 截图成功但没识别到文字")
            return null
        }
        Log.w(
            "ocr: 无障碍读不到字 → OCR 兜底 ${result.items.size} 行 " +
                "sample=${result.items.take(6).joinToString("|") { it.text }}",
        )
        return result.asText()
    }

    /**
     * **OCR 能力自检**（`mode=ocr`）：强制截一帧识别，把结果原样打出来。
     *
     * 全程不点任何界面，所以可以随时安全运行 —— 用户要验证 OCR 到底能不能用时，
     * 只需跑这一条，不用等任务跑到盲区。
     */
    private fun selfTestOcr(): String {
        val result = runCatching { AutomationBridge.ocrRead(context) }.getOrNull()
            ?: return "ocr=通道不可达（无障碍服务没起来）"
        if (!result.ok) return "ocr=不可用（${result.reason}）"
        lastOcrAtMs = SystemClock.uptimeMillis()
        val text = result.asText().replace("\n", "|").take(200)
        val report = "ocr lines=${result.items.size} text=$text"
        Log.w("self test $report")
        return report
    }

    private fun screenWidthPx(): Int {
        val fromService = runCatching { AutomationBridge.screenWidth(context) }.getOrDefault(0)
        if (fromService > 0) return fromService
        return onMain {
            resumedActivity?.resources?.displayMetrics?.widthPixels
        } ?: runCatching { context.resources.displayMetrics.widthPixels }.getOrDefault(0)
    }

    /**
     * 屏幕**真实高度**（像素，全屏）。
     *
     * 与 [screenWidthPx] 同理：服务侧的 `getRealSize()` 才是节点坐标的坐标系。
     * 免 root 版拿不到宿主对象，若退回 `context.resources.displayMetrics`，
     * 得到的是**可用窗口**高度（实测 2603 vs 真实 2800），底部的按钮会算偏。
     */
    private fun screenHeightPx(): Int {
        val fromService = runCatching { AutomationBridge.screenHeight(context) }.getOrDefault(0)
        if (fromService > 0) return fromService
        return onMain {
            resumedActivity?.resources?.displayMetrics?.heightPixels
        } ?: runCatching {
            context.resources.displayMetrics.heightPixels
        }.getOrDefault(0)
    }

    /**
     * 关掉挡在前台的对话框。
     *
     * 实测：P图 页粘贴文案后会出现 `androidx.appcompat.app.AlertDialog`，把发送 ⬆ 挡住，
     * 导致"选图+文案都做了但发不出去"。这里只点**精确文字**的确认键（安全），并记录弹窗内容。
     *
     * **判据必须是结构化的**（见 [BlockingDialog]）。旧实现扫全屏文案找关键词，
     * 结果是系统性误报：模块自己写进输入框的提示词（「不要**使用**夸张口号。」）
     * 和 AI 回复正文（「**好的**，下面是…」）都会被当成弹窗 —— 真机日志里
     * 命中 "使用" 3 次、"好的" 3 次。所以现在要求"非输入框 + 文字精确相等 + 可点/按钮"。
     */
    private fun dismissBlockingDialog(): Boolean {
        val nodes = AutomationBridge.uiNodes()
        val key = BlockingDialog.pickKey(
            nodes.map { BlockingDialog.Node(it.className, it.text, it.clickable) },
        ) ?: run {
            Log.d("prompt: 没有结构性弹窗证据（非输入框 + 精确文案 + 可点），不点")
            return false
        }
        val source = nodes.firstOrNull { it.text.trim() == key }
        Log.d(
            "prompt: 检测到弹窗，尝试精确点“$key”" +
                " node=${source?.className}@${source?.bounds?.toShortString()} " +
                "clickable=${source?.clickable}",
        )
        val clicked = AutomationBridge.clickText(context, listOf(key), allowContains = false) ||
            clickDirectText(listOf(key)) ||
            clickWebTextExact(listOf(key))
        Log.d("prompt: 弹窗确认键 clicked=$clicked")
        if (clicked) pause(1_500L)
        return clicked
    }

    /**
     * 在宿主 View 树里直接找**发送键**并 `performClick()`。
     *
     * 无障碍树里"发送键"和"➕/🎤"都是无文字 View ✗，只能猜 ✗；宿主进程里能读到
     * **真实 id 名与 contentDescription**，于是能精确排除干扰键（更多/语音/相册/拍摄/文件…）。
     */
    private fun clickSendButtonViaViewTree(): Boolean = onMain {
        val root = resumedActivity?.window?.decorView ?: return@onMain false
        val editors = mutableListOf<View>()
        collectViews(root, editors) { view ->
            view is EditText && view.visibility == View.VISIBLE && view.width > 0 && view.height > 0
        }
        val editor = editors.maxByOrNull { it.width * it.height } ?: return@onMain false
        val editorRect = Rect().also { editor.getGlobalVisibleRect(it) }
        if (editorRect.height() <= 0) return@onMain false
        val buttons = mutableListOf<View>()
        collectViews(root, buttons) { view ->
            view.isClickable && view.visibility == View.VISIBLE && view.width > 0 && view.height > 0
        }
        val measured = buttons.map { it to Rect().also { rect -> it.getGlobalVisibleRect(rect) } }
        // 诊断：把输入框右侧的候选都打出来（含被黑名单剔除的），一次就能定位发送键。
        val nearby = measured.filter { (_, rect) ->
            rect.height() > 0 &&
                Math.abs(rect.centerY() - editorRect.centerY()) <= editorRect.height() * 2 &&
                rect.left >= editorRect.left
        }
        nearby.forEach { (view, rect) ->
            Log.d(
                "prompt: viewTree candidate id=${viewIdName(view)} desc=${view.contentDescription} " +
                    "text=${(view as? TextView)?.text} rect=$rect distractor=${isDistractorButton(view)}",
            )
        }
        val candidates = nearby
            .filter { (_, rect) ->
                Math.abs(rect.centerY() - editorRect.centerY()) <= editorRect.height() * 2 &&
                    rect.left >= editorRect.right - 24
            }
            .filter { (view, _) -> !isDistractorButton(view) }
            .sortedByDescending { (_, rect) -> rect.left }
        val target = candidates.firstOrNull()?.first
        if (target == null) {
            Log.d("prompt: viewTree 没找到发送键（候选 ${candidates.size}）")
            return@onMain false
        }
        // **闸门**：这颗"输入框右侧最近的控件"也没有 id/描述（`isDistractorButton` 认不出它），
        // 而元宝那颗容器是**语义随内容翻转**的：输入框为空时它是"切换语音" ✗、
        // 有文案时才是"发送" ✓。所以**输入框没文案就绝不点它** —— 这正是用户报的
        // "没文案时误触切换语音"。输入框文案由**同一颗控件**读出，不存在读错对象的问题。
        val editorText = (editor as? TextView)?.text?.toString().orEmpty()
        if (editorText.isBlank()) {
            Log.w(
                "prompt: viewTree 输入框无文案 → 不点右侧容器（避免点到切换语音 ✗） " +
                    "targetId=${viewIdName(target)} targetDesc=${target.contentDescription}",
            )
            return@onMain false
        }
        Log.d(
            "prompt: viewTree 发送键 id=${viewIdName(target)} desc=${target.contentDescription} " +
                "rect=${Rect().also { target.getGlobalVisibleRect(it) }} editorText=${editorText.take(24)}",
        )
        val clicked = target.performClick()
        TapTrace.recordAction(
            "hostViewTreeSend",
            "id=${viewIdName(target)} desc=${target.contentDescription} ok=$clicked",
        )
        TapTrace.pace()
        clicked
    } == true

    /** 是否为"干扰键"（➕/🎤/相册…）：按真实 id 名与描述排除，绝不点。 */
    private fun isDistractorButton(view: View): Boolean {
        val idName = viewIdName(view)
        val desc = view.contentDescription?.toString().orEmpty()
        val text = (view as? TextView)?.text?.toString().orEmpty()
        val haystack = "$idName|$desc|$text".lowercase()
        return listOf(
            "更多", "语音", "相册", "拍摄", "拍照", "文件", "表情", "图片", "素材", "添加",
            "more", "voice", "album", "camera", "photo", "file", "emoji", "image", "add", "plus",
            "clear", "删除", "关闭", "close", "back", "返回",
        ).any { haystack.contains(it) }
    }

    private fun viewIdName(view: View): String =
        runCatching { view.resources.getResourceEntryName(view.id) }.getOrDefault("")

    private fun collectViews(root: View, output: MutableList<View>, predicate: (View) -> Boolean) {
        if (predicate(root)) output += root
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) {
                collectViews(root.getChildAt(index), output, predicate)
            }
        }
    }

    /**
     * 在输入框里按回车发送；输入框是 Compose（无 EditText/无 id）时退回受限的 root 通道。
     * 这是**零误触**通道（不会碰到旁边的 ➕/🎤）。
     */
    private fun pressEnterInEditor(): Boolean {
        val enter = onMain {
            val input = findPreferredEditText() ?: return@onMain false
            val now = SystemClock.uptimeMillis()
            val down = input.dispatchKeyEvent(
                KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, 0),
            )
            val up = input.dispatchKeyEvent(
                KeyEvent(now, now + 30L, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER, 0),
            )
            down || up
        } == true
        if (enter) {
            Log.d("prompt: send channel=hostEnterKey")
            return true
        }
        if (AutomationBridge.rootEnter(context)) {
            Log.d("prompt: send channel=rootEnterKey")
            return true
        }
        return false
    }

    /** P图 exposes an unlabeled android.widget.Button beside the image prompt. */
    private fun clickAdjacentActionButton(): Boolean = onMain {
        val input = findPreferredEditText() ?: return@onMain false
        val inputBounds = Rect().also { input.getGlobalVisibleRect(it) }
        var parent = input.parent as? ViewGroup
        repeat(3) {
            val group = parent ?: return@repeat
            for (index in 0 until group.childCount) {
                val child = group.getChildAt(index)
                if (child === input || !child.isShown || !child.isEnabled) continue
                val bounds = Rect().also { child.getGlobalVisibleRect(it) }
                val buttonLike = child is Button ||
                    child.javaClass.simpleName.contains("Button", ignoreCase = true)
                val besideInput = bounds.left >= inputBounds.right - 32 &&
                    bounds.centerY() in (inputBounds.top - 40)..(inputBounds.bottom + 40)
                if (buttonLike && besideInput && (child.performClick() || child.callOnClick())) {
                    Log.d("prompt: clicked unlabeled adjacent action button")
                    return@onMain true
                }
            }
            parent = group.parent as? ViewGroup
        }
        false
    } == true

    private fun findPreferredEditText(): EditText? {
        val root = resumedActivity?.window?.decorView ?: return null
        findViewByIdNames(root, listOf("edConversationInput"))
            .filterIsInstance<EditText>()
            .firstOrNull { it.isShown && it.isEnabled }
            ?.let { return it }
        return findEditText(root)
    }

    private fun findEditText(view: View?): EditText? {
        if (view == null) return null
        if (view is EditText && view.isShown && view.isEnabled) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                findEditText(view.getChildAt(index))?.let { return it }
            }
        }
        return null
    }

    private fun clickDirectIds(ids: List<String>): Boolean = onMain {
        val root = resumedActivity?.window?.decorView ?: return@onMain false
        findViewByIdNames(root, ids).firstOrNull { view ->
            view.isShown && view.isEnabled && (view.performClick() || view.callOnClick())
        } != null
    } == true

    @SuppressLint("DiscouragedApi")
    private fun findViewByIdNames(root: View, ids: List<String>): Sequence<View> = sequence {
        for (name in ids) {
            val id = context.resources.getIdentifier(name, "id", TARGET_PACKAGE)
            if (id == 0) continue
            root.findViewById<View>(id)?.let { yield(it) }
        }
    }

    private fun readProgress(task: DailyTaskKind): Int? =
        readProgress(task, readScreenText())

    private fun readProgress(task: DailyTaskKind, text: String): Int? {
        val titleStart = text.indexOf(task.title, ignoreCase = true)
        val start = if (titleStart >= 0) {
            titleStart
        } else {
            task.aliases.mapNotNull { alias ->
                text.indexOf(alias, ignoreCase = true).takeIf { it >= 0 }
            }.minOrNull() ?: return null
        }
        val nextTaskStart = DailyTaskKind.entries
            .filter { it != task }
            .mapNotNull { other ->
                text.indexOf(other.title, startIndex = start + task.title.length, ignoreCase = true)
                    .takeIf { it > start }
            }
            .minOrNull() ?: text.length
        // **做同款必须按版块边界截断**（2026-11 真机）：
        // 这一块的无障碍文本里只有卡片标题、「做同款」按钮和「已体验」，
        // **没有 2/3 这种进度数字**。版块一挪到页面中部，后面紧跟着「趣问元宝 3/3」，
        // 不截断就会把趣问的 3/3 当成做同款的进度 → 误判已完成 → 整项被跳过。
        val nextHeaderStart = if (task == DailyTaskKind.TEMPLATE) {
            SECTION_HEADERS.mapNotNull { header ->
                text.indexOf(header, startIndex = start + 1, ignoreCase = true)
                    .takeIf { it > start }
            }.minOrNull() ?: text.length
        } else {
            text.length
        }
        val section = text.substring(
            start,
            minOf(nextTaskStart, nextHeaderStart).coerceAtMost(text.length),
        )
        // 做同款这一块**不显示 x/3**，做完只会多一个「已领取」徽标（2026-11 真机实测）。
        // 徽标比数字可靠，直接当作已完成；没徽标就返回 null（读不到 ≠ 没做完），
        // 由上层按"最多 3 轮"兜底，第一轮发完徽标就会出现，随即收手。
        if (task == DailyTaskKind.TEMPLATE && section.contains("已领取")) return 3
        return PROGRESS_REGEX.findAll(section).mapNotNull { match ->
            val current = match.groupValues[1].toIntOrNull() ?: return@mapNotNull null
            val required = match.groupValues[2].toIntOrNull() ?: return@mapNotNull null
            current.takeIf { required in 1..20 && current in 0..required }
        }.firstOrNull()
    }

    /**
     * One-line description of the surface the accessibility bridge currently
     * sees, so a stuck click is diagnosable from logcat alone.
     */
    private fun surfaceDiagnostics(): String {
        val snapshot = AutomationBridge.snapshot(context)
        return "package=${snapshot.packageName} activity=${snapshot.activity} " +
            "windows=${snapshot.windows}"
    }

    private fun readScreenText(logSample: Boolean = true): String {
        val activity = resumedActivity
        val rootText = if (activity == null) "" else onMain {
            buildString { collectViewText(activity.window?.decorView, this) }
        }.orEmpty()
        val accessibilityText = AutomationBridge.readText(context)
        val webView = if (activity == null) null else onMain { findWebView(activity.window?.decorView) }
        val webText = if (webView == null) "" else evaluateJavascript(
            webView,
            "encodeURIComponent((document.body&&document.body.innerText)||'')",
        )?.let(::decodeJavascriptString).orEmpty()
        val merged = listOf(rootText, accessibilityText, webText)
            .filter(String::isNotBlank)
            .distinct()
            .joinToString("\n")
        // **盲区兜底**：无障碍一个字都没读到（或只剩几个字）时，截一帧走 OCR。
        // 判据很严（见 OcrFallbackPolicy）：正常页面文案几十上百字，这条分支根本不会进，
        // 所以不会拖慢点击节奏。真进了盲区，日志会明确打出来。
        val ocrText = ocrFallbackText(merged)
        val text = if (ocrText.isNullOrBlank()) merged else merged + "\n" + ocrText
        if (logSample) Log.d("screen text sample=${sample(text, 800)}")
        return text
    }

    private fun clickVisible(aliases: List<String>, allowContains: Boolean = false): Boolean =
        clickWebTextExact(aliases) ||
            (allowContains && clickWebTextContains(aliases)) ||
            AutomationBridge.clickText(context, aliases, allowContains = allowContains) ||
            clickDirectText(aliases)

    private fun clickDirectText(aliases: List<String>): Boolean = onMain {
        val root = resumedActivity?.window?.decorView ?: return@onMain false
        clickMatching(root, aliases, exact = true) || clickMatching(root, aliases, exact = false)
    } == true

    private fun clickMatching(view: View?, aliases: List<String>, exact: Boolean): Boolean {
        if (view == null || !view.isShown) return false
        val text = readableText(view).trim()
        val matched = aliases.any { alias ->
            if (exact) text.equals(alias, ignoreCase = true) else text.contains(alias, ignoreCase = true)
        }
        if (text.isNotBlank() && matched) {
            val target = clickableAncestor(view)
            if (target != null && target.isEnabled && (target.performClick() || target.callOnClick())) return true
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                if (clickMatching(view.getChildAt(index), aliases, exact)) return true
            }
        }
        return false
    }

    private fun clickableAncestor(view: View): View? {
        var current: View? = view
        repeat(8) {
            if (current?.isClickable == true) return current
            current = current?.parent as? View
        }
        return null
    }

    private fun collectViewText(view: View?, output: StringBuilder) {
        if (view == null || !view.isShown || output.length >= MAX_TEXT) return
        readableText(view).takeIf(String::isNotBlank)?.let { output.append(it).append('\n') }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) collectViewText(view.getChildAt(index), output)
        }
    }

    private fun readableText(view: View): String = when (view) {
        is TextView -> view.text?.toString().orEmpty()
        else -> view.contentDescription?.toString().orEmpty()
    }

    private fun clickWebTextExact(aliases: List<String>): Boolean {
        val activity = resumedActivity ?: return false
        val webView = onMain { findWebView(activity.window?.decorView) } ?: return false
        val names = aliases.joinToString(",") { jsQuote(it) }
        val result = evaluateJavascript(
            webView,
            """
            (function(){
              var names=[$names].map(function(v){return v.replace(/\s+/g,'').toLowerCase();});
              var all=Array.prototype.slice.call(document.querySelectorAll('*'));
              for(var i=0;i<all.length;i++){
                var e=all[i], text=(e.innerText||e.textContent||'').trim();
                var normalized=text.replace(/\s+/g,'').toLowerCase();
                if(names.indexOf(normalized)<0 || e.getClientRects().length===0) continue;
                var target=e.closest('button,a,[role="button"],[onclick]')||e;
                target.click();
                return 'clicked:'+text;
              }
              return 'not-found';
            })()
            """.trimIndent(),
        )
        val clicked = result?.contains("clicked:") == true
        if (clicked) Log.d("web exact click result=${result?.take(160)}")
        return clicked
    }

    private fun clickWebTextContains(aliases: List<String>): Boolean {
        val activity = resumedActivity ?: return false
        val webView = onMain { findWebView(activity.window?.decorView) } ?: return false
        val names = aliases.joinToString(",") { jsQuote(it) }
        val result = evaluateJavascript(
            webView,
            """
            (function(){
              var names=[$names].map(function(v){return v.replace(/\\s+/g,'').toLowerCase();});
              var best=null, all=Array.prototype.slice.call(document.querySelectorAll('*'));
              for(var i=0;i<all.length;i++){
                var e=all[i], text=(e.innerText||e.textContent||'').trim();
                var normalized=text.replace(/\\s+/g,'').toLowerCase();
                if(!text || e.getClientRects().length===0) continue;
                if(names.some(function(n){return normalized.indexOf(n)>=0;})){
                  if(!best || text.length < (best.innerText||best.textContent||'').trim().length) best=e;
                }
              }
              if(!best) return 'not-found';
              (best.closest('button,a,[role=\"button\"],[onclick]')||best).click();
              return 'clicked:'+((best.innerText||best.textContent||'').trim());
            })()
            """.trimIndent(),
        )
        val clicked = result?.contains("clicked:") == true
        if (clicked) Log.d("web contains click result=${result?.take(160)}")
        return clicked
    }

    private fun findWebView(view: View?): View? {
        if (view == null) return null
        if (view is WebView) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) findWebView(view.getChildAt(index))?.let { return it }
        }
        if (view.javaClass.methods.any { it.name == "evaluateJavascript" && it.parameterTypes.size == 2 } ||
            view.javaClass.methods.any {
                it.name == "loadUrl" && it.parameterTypes.contentEquals(arrayOf(String::class.java))
            }
        ) return view
        return null
    }

    private fun evaluateJavascript(view: View, script: String): String? {
        val latch = CountDownLatch(1)
        var result: String? = null
        main.post {
            try {
                if (view is WebView) {
                    view.evaluateJavascript(script, ValueCallback {
                        result = it
                        latch.countDown()
                    })
                } else {
                    val method = view.javaClass.methods.firstOrNull {
                        it.name == "evaluateJavascript" && it.parameterTypes.size == 2
                    }
                    if (method == null) {
                        view.javaClass.methods.firstOrNull {
                            it.name == "loadUrl" &&
                                it.parameterTypes.contentEquals(arrayOf(String::class.java))
                        }?.invoke(view, "javascript:$script")
                        latch.countDown()
                    } else {
                        val callbackType = method.parameterTypes[1]
                        val callback = Proxy.newProxyInstance(
                            callbackType.classLoader,
                            arrayOf(callbackType),
                        ) { _, called, args ->
                            if (called.name == "onReceiveValue") {
                                result = args?.firstOrNull()?.toString()
                                latch.countDown()
                            }
                            null
                        }
                        method.invoke(view, script, callback)
                    }
                }
            } catch (t: Throwable) {
                Log.e("WebView JavaScript failed", t)
                latch.countDown()
            }
        }
        latch.await(4L, TimeUnit.SECONDS)
        return result
    }

    private fun decodeJavascriptString(raw: String): String {
        val unquoted = raw.trim().removeSurrounding("\"")
            .replace("\\n", "\n")
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")
        return runCatching {
            URLDecoder.decode(unquoted, StandardCharsets.UTF_8.name())
        }.getOrDefault(unquoted)
    }

    private fun jsQuote(value: String): String = "'" + value
        .replace("\\", "\\\\")
        .replace("'", "\\'")
        .replace("\n", "\\n") + "'"

    private fun rootTapRelative(xFraction: Float, yFraction: Float): Boolean {
        // `input tap` 用全屏坐标系，比例基准也必须是全屏尺寸（见 AutomationBridge.tap）。
        val width = AutomationBridge.screenWidth(context)
        val height = AutomationBridge.screenHeight(context)
        if (width <= 0 || height <= 0) return false
        return AutomationBridge.rootTap(
            context,
            (width * xFraction).toInt(),
            (height * yFraction).toInt(),
        )
    }

    private fun awaitCondition(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        do {
            if (runCatching(condition).getOrDefault(false)) return true
            pause(250L)
        } while (SystemClock.uptimeMillis() < deadline)
        return runCatching(condition).getOrDefault(false)
    }

    private fun sample(value: String, max: Int): String =
        value.take(max).replace('\n', '|').replace('\r', '|')

    private fun pause(milliseconds: Long) = try {
        Thread.sleep(milliseconds)
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
    }

    private fun <T> onMain(block: () -> T): T? {
        if (Looper.myLooper() == Looper.getMainLooper()) return runCatching(block).getOrNull()
        val latch = CountDownLatch(1)
        var value: T? = null
        main.post {
            value = runCatching(block)
                .onFailure { Log.e("main-thread UI action failed", it) }
                .getOrNull()
            latch.countDown()
        }
        latch.await(5L, TimeUnit.SECONDS)
        return value
    }

    private companion object {
        const val TARGET_PACKAGE = "com.tencent.hunyuan.app.chat"
        const val MAX_TEXT = 50_000
        const val MINE_X = 0.842f
        const val MINE_Y = 0.936f
        const val HOME_WELFARE_X = 0.756f
        const val HOME_WELFARE_Y = 0.543f
        const val WELFARE_X = 0.80f
        const val WELFARE_Y = 0.44f
        const val COLOROS_GALLERY_PACKAGE = "com.coloros.gallery3d"
        const val COLOROS_PREVIEW_CONFIRM_X = 0.923f
        const val COLOROS_PREVIEW_CONFIRM_Y = 0.083f
        const val MAX_MEDIA_SELECTION_ATTEMPTS = 8
        const val MEDIA_REFRESH_SCANS = 3

        /** 连续几次扫不到照片格子就放弃（原地重扫，不按返回）。 */
        const val MEDIA_EMPTY_SCAN_LIMIT = 2
        const val MEDIA_RETRY_PAUSE_MS = 900L
        const val MEDIA_GESTURE_VERIFY_MS = 2_500L
        const val MEDIA_ROOT_VERIFY_MS = 3_500L
        const val MEDIA_CONFIRM_VERIFY_MS = 4_000L
        const val REQUIRED_TEMPLATE_ROUNDS = 3
        const val MAX_TEMPLATE_SCROLLS = 4
        const val TEMPLATE_SCROLL_PAUSE_MS = 1_100L
        /**
         * 点「做同款」后等模板详情页渲染出来。
         *
         * 实测 1~3s 才出现「图1 / 分享」这些特征；骨架屏阶段页面只有几百字，
         * 直接判定会误认为"没进详情页"，又退回模板列表那条死路。
         */
        const val TEMPLATE_DETAIL_WAIT_MS = 7_000L

        val WELFARE_ALIASES = listOf("福利中心", "[icon] 福利中心")
        /** 福利中心里「每日任务」区的特征文本，用于判断是否需要继续下滑。 */
        val WELFARE_TASK_MARKERS = listOf(
            "每日任务",
            "当日凌晨0点更新任务",
            "去提问",
            "去写作",
            "去P图",
            "去拍题",
            "做同款",
        )
        /** 标题附近 400 字内的 `x/y`。 */
        val PROGRESS_NEAR_TITLE = Regex("(\\d+)\\s*/\\s*(\\d+)")
        /** 接口 JSON 的字段形态，例如 "complete":1,"total":3。 */
        val COUNT_FIELD_REGEX = Regex(
            "(?:complete|completed|current|finished|finish|count|num|progress)\\w*\"?\\s*[:=]\\s*(\\d+)" +
                "[\\s\\S]{0,80}?(?:total|target|required|need|max)\\w*\"?\\s*[:=]\\s*(\\d+)",
            RegexOption.IGNORE_CASE,
        )
        const val MAX_WELFARE_SCROLLS = 3
        /**
         * 读进度时最多下滑几屏。
         *
         * 2026-10 福利中心改版后页面明显变长：顶部活动图 + 签到 + 百宝箱 + 趣问 +
         * 每日任务 + 「做同款模版得积分」。「做同款」被挪到最底部，4 屏够不到，
         * 于是它的进度永远读不到。放宽到 6 屏，并在读到五项齐全时提前收手。
         */
        const val MAX_PROGRESS_SWIPES = 6
        /** 找 CTA 时最多下滑几屏（与进度补齐同量级；做同款在最底部，需要多滚两屏）。 */
        const val MAX_CTA_SWIPES = 6
        const val PROGRESS_SWIPE_PAUSE_MS = 900L
        const val PROGRESS_RESTORE_PAUSE_MS = 450L
        /** 点掉领积分弹窗后等它淡出，再读页面/找 CTA。 */
        const val REWARD_DISMISS_PAUSE_MS = 1_200L
        /** 点完任务 CTA 后，等福利中心这页真正切走的上限。 */
        const val WELFARE_LEAVE_WAIT_MS = 6_000L
        /** 福利中心顶部（任务区之上）才有的文本，用于判断"已经滚回顶了"。 */
        val WELFARE_TOP_MARKERS = listOf("兑换商城", "每日问元宝得积分")
        /**
         * 福利中心版块标题。**顺序会随完成状态变化**：没做完的排在前面，
         * 做完了的沉到页面最下面，所以不能按位置找，只能按标题找。
         */
        val SECTION_HEADERS = listOf(
            "每日问元宝", "元宝百宝箱", "做同款模版", "做同款模板",
            "趣问元宝", "每日任务", "兑换商城",
        )
        /** 页面上出现这些词就说明还在福利中心，不是模板详情页。 */
        val TEMPLATE_DETAIL_EXCLUDE = listOf(
            "每日任务", "元宝百宝箱", "换一波", "做同款模版", "做同款模板",
            "当日凌晨", "去邀请", "已领取",
        )
        /** 模板列表特征：列表滚出可视区时这些字都会消失。 */
        val TEMPLATE_LIST_MARKERS = listOf("做同款", "万人用过", "推荐模板", "原图")
        /**
         * **发送键一律不再使用写死比例坐标。**
         *
         * 历史教训（真机 dump 实测 @1272x2800）：
         * - `TEMPLATE_SEND (0.866,0.928)` → y=2598，落在输入框行右侧区域；
         * - `CHAT_SEND (0.87,0.973)` → y=2724，落在输入框（2435–2645）**下方 79px**；
         * - `COMPOSER (0.60,0.915)` / `tapEditorCenter 回退 (0.445,0.976)` → y=2732，框外 87px。
         *
         * 也就是说"比例 × 屏高"算出来的 y 全部偏低，正是"点着点着按到别的东西"的来源。
         * 现在发送键只走 `AutomationBridge.tapSendByGeometry()`：由无障碍树实测
         * 输入框与同行按钮的真实 bounds，并在派发前校验落点安全；取不到就判定失败。
         */
        const val EDITOR_SEND_OFFSET_PX = 90
        /**
         * 通过 `InputConnection` 读输入框文案时单侧最多取多少个字符。
         *
         * 取大值没意义（提示词只有几十字），够覆盖即可；太大在输入连接上是无谓开销。
         */
        const val EDITOR_READ_LIMIT = 4096
        /** 写入对话栏后回读确认的等待上限（Compose 收到 setText 到 a11y 更新有延迟）。 */
        const val COMPOSER_TEXT_WAIT_MS = 2_500L

        /**
         * 判空时等待无障碍服务可用的上限。
         *
         * 真机实测：模块自己的 `ui dump` 在这台 ColorOS 上会把**我们自己的无障碍服务**
         * 踢下线约 1 秒（见 `AutomationAccessibilityService.perform` 的说明）。
         * 这段时间 `composerState` 返回 null，如果不在这里等，就会掉进两条
         * **对 Compose 输入框完全无效**的宿主通道 → 把空的输入栏判成"有文案"
         * → 去点右侧那颗按钮，而空框时那颗正是"切换语音" ✗。
         */
        const val A11Y_STATE_WAIT_MS = 1_200L

        /**
         * 发送后等待"输入框被清空"的上限。
         *
         * 原来是 `pause(2_000L)` 硬等，改成轮询：发送成功的清空可能早于 2 秒，
         * 也可能稍晚；硬等既浪费也可能刚好错过，轮询两头都稳。
         */
        const val SEND_CLEAR_WAIT_MS = 4_000L

        /**
         * 开工前自愈后，最多再等无障碍服务多久算"起来了"。
         *
         * `AccessibilityRepair` 自己最坏要 ~7 秒（900ms 沉降 + 6s 等回调），
         * 这里给 10 秒是留给"它报成功但树还没来得及刷新"的余量。
         */
        const val A11Y_PREFLIGHT_WAIT_MS = 10_000L

        /** 自愈尝试次数（每轮之间隔 [A11Y_PREFLIGHT_RETRY_GAP_MS]）。 */
        const val A11Y_PREFLIGHT_ATTEMPTS = 3

        /** 两次自愈之间的间隔。留给模块的 linkPump 重新投递 binder。 */
        const val A11Y_PREFLIGHT_RETRY_GAP_MS = 3_000L

        /**
         * 免 root 版拉起元宝后，最多等它出现在最上层的时限。
         *
         * 原来是 20 秒，真机实测**不够**：ColorOS 上元宝冷启动 + 后台限制解除
         * 经常超过 20 秒（1.4.4 自检里 20 秒超时放弃，随后元宝自己起来了）。
         * 放长到 35 秒；期间一个点击都不发，所以放长没有误触风险。
         */
        const val HOST_LAUNCH_WAIT_MS = 35_000L

        /** 等待元宝期间，多久尝试一次清理厂商安全提示。 */
        const val GUARD_RETRY_GAP_MS = 4_000L

        /** 回首页最多尝试几步（关侧栏 / 按返回各算一步）。 */
        const val HOME_SURFACE_ATTEMPTS = 4

        /** Activity 已是首页后，等页面特征渲染出来的上限（冷启动时节点会晚几秒）。 */
        const val HOME_READY_WAIT_MS = 6_000L

        /** 进创作页后等它渲染出标签栏/卡片的上限（转场动画期间点击会落到旧页面）。 */
        const val CREATE_PAGE_READY_WAIT_MS = 8_000L

        /** 找"底部 P图"节点的最多尝试次数（节点树在页面切换后会滞后几秒）。 */
        const val PICTURE_ENTRY_ATTEMPTS = 3

        /**
         * 发送**之前**等"输入框真的有文案"的上限。
         *
         * 只在状态是 `UNKNOWN`（输入栏正在重绘 / 无障碍服务刚重连）时才耗这个预算；
         * 明确读到 `EMPTY` 会**立刻**放弃，不再空转 10 秒。
         * `evidence/trace_d7_ask` 实证：旧写法在空框上白等 10s + 39 次全树 binder 往返。
         */
        const val SEND_READY_WAIT_MS = 3_000L

        /**
         * 上面那个等待的轮询间隔。
         *
         * 一次 `composerState` 要跨进程走一遍无障碍树（实测约 265ms），
         * 250ms 的轮询间隔会让进程几乎没有空档 —— 本机实测就是每 265ms 一次。
         * 放宽到 400ms，样本更少但足够及时。
         */
        const val SEND_READY_POLL_MS = 400L

        /** `mode=fill` 探针写进去的文案（用固定前缀便于在 dump 里一眼认出来）。 */
        const val SELF_TEST_FILL_TEXT = "ABC"

        /** 能力页（P图等）编辑框中心：用户截图实测 y≈0.564。 */
        const val CAPABILITY_EDITOR_X = 0.44f
        const val CAPABILITY_EDITOR_Y = 0.564f
        /**
         * "图片生成完毕"卡片上那个关闭 × 相对文字中心的**水平偏移比例**。
         *
         * 原来写的是 `460`（实测 460px @1272 宽）。这是绝对像素，换到 1080 宽
         * 的手机上就相当于偏了 42% 屏宽（而不是 36%），会点偏 —— 比如点到卡片
         * 主体上，于是卡片不关、继续挡着发送键。
         * 460 / 1272 ≈ 0.362，改成比例后任何分辨率都对得上。
         */
        const val GENERATION_CARD_CLOSE_FRACTION = 0.362f
        /** 拍题相机页"相册"缩略图位置（实测左下角 ≈ (292,2528) @1272x2800）。 */
        const val CAMERA_ALBUM_X = 0.23f
        const val CAMERA_ALBUM_Y = 0.903f
        /** 底部栏右端"三横杠"圆图标（打开全部任务面板）：从录屏逐帧量得 ≈ (1132,2242) @1272x2800。 */
        const val TASK_PANEL_X = 0.89f
        const val TASK_PANEL_Y = 0.80f

        /**
         * 底栏快捷入口的文字标签 —— 用来**从无障碍树实测**"这一行"的真实 y。
         *
         * 只要其中任意一个在屏幕下半部出现，它的节点中心 y 就是底栏的准确位置，
         * 与分辨率、导航栏模式（手势/三键）、屏幕比例都无关。
         */
        val BOTTOM_BAR_ENTRY_LABELS = listOf(
            "快速",
            "AI创作",
            "拍题答疑",
            "AI录音笔",
            "AI写作",
            "翻译",
            "PPT生成",
        )

        /**
         * 一个入口节点都读不到时的兜底：**底栏行中心距屏幕底部的像素偏移**（@1272/480dpi）。
         *
         * 与旧的百分比列表一一等价（0.845/0.805/0.885/0.76/0.92 @2800 高），
         * 但底层基准换成"距底 px"，再用 [ScreenScale.bottomAnchoredFraction] 按各机屏高
         * 反算比例 —— 贴底布局的位置只取决于距底多少 dp，跟距顶百分比无关。
         *
         * 覆盖：手势导航 434、三键导航 546、16:9 老机 322、折叠展开/分屏 672 与 224。
         */
        val BOTTOM_BAR_FALLBACK_INSETS_REF = listOf(434, 546, 322, 672, 224)

        /** 连屏高都读不到时的最后兜底（开发机实测命中值；此时 [ScreenScale] 退化为参考机型）。 */
        const val DEFAULT_BOTTOM_BAR_Y = 0.845f
        /** 底栏左滑后 ≡ 圆图标的实际位置（从用户录屏逐帧量得 ≈ (1150,2365) @1272x2800）。 */
        const val PANEL_ICON_X = 0.905f
        /** @deprecated 硬编码比例，跨宽高比会偏；底栏定位统一走 [bottomBarRowFractions]。 */
        const val PANEL_ICON_Y = 0.845f
        /** 底栏**按钮之间空白缝隙**的位置（用户提示：从缝隙处按住拖动，别拖在按钮上）。 */
        const val BAR_GAP_FROM_X = 0.53f
        const val BAR_GAP_TO_X = 0.14f
        /** @deprecated 同上，改用 [bottomBarRowFractions]。 */
        const val BAR_GAP_Y = 0.872f
        /** 能力页（P图/写作）发送 ⬆ 的位置：用户截图实测 ≈ (1091,1580) @1272x2800。 */
        const val CAPABILITY_SEND_X = 0.865f
        const val CAPABILITY_SEND_Y = 0.565f
        /** 选择器里"最新那张照片"的格子：第 1 行第 2 格（用户截图实测 ≈ (488,641) @1272x2800）。 */
        const val PICKER_NEWEST_X = 0.384f
        const val PICKER_NEWEST_Y = 0.229f
        /**
         * 左上角"两条杠"圆圈按钮（打开侧栏）。
         *
         * 只保留**语义标签**：真实坐标由无障碍树里节点的 bounds 决定
         * （实测 `content-desc='抽屉页入口'`，内层不可点，外层是可点的
         * `ic_navigation_show`）。**不再写死 0.09/0.093 这类比例坐标。**
         *
         * 判定策略本身在 [WelfareNav] 里（纯函数，有单测）。
         */
        const val DRAWER_ENTRY_LABEL = WelfareNav.DRAWER_ENTRY_LABEL
        /** 侧栏里"福利中心"行的语义标签（图标节点带这个 content-desc）。 */
        const val WELFARE_ROW_LABEL = WelfareNav.WELFARE_ROW_LABEL
        /** 点汉堡常被吞（真机实测第 3 次才开）⇒ 允许多试几次。 */
        const val DRAWER_OPEN_ATTEMPTS = 5
        /** 侧栏展开动画的等待时长。 */
        const val DRAWER_SETTLE_MS = 2_500L
    /** 能力页的“添加素材”占位文案：先点它，页面才会接收选择器返回的图片。 */
        val MATERIAL_SLOT_TEXT = listOf(
            "点击添加创作素材",
            "添加创作素材",
            "点击添加图片",
            "添加图片",
            "上传图片",
            "选择图片",
            "添加素材",
        )
        /** 已挂上素材的信号：页面把素材标成“图片1/图片2…”。 */
        val ATTACHED_MEDIA_REGEX = Regex("图片\\s*\\d")
        /** 预览页“确定/完成”的常见落点（无文字无 ID 的按钮）：右下角与底部居中。 */
        val MEDIA_CONFIRM_POINTS = listOf(0.87f to 0.93f, 0.5f to 0.93f, 0.87f to 0.86f)

        /** 页面里抓接口数据的 JS 钩子（装一次即可，刷新后仍然有效）。 */
        val SNIFFER_INSTALL_SCRIPT = """
            (function(){
              try {
                if (window.__ybdSniffer) return 'already';
                window.__ybdHooked = [];
                function keep(url, body){
                  try{
                    if (!url) return;
                    if (!/task|campaign|welfare|vip|award|sign|score|credit|activity/i.test(String(url))) return;
                    window.__ybdHooked.push({url:String(url).slice(0,200), body:String(body||'').slice(0,6000)});
                    if (window.__ybdHooked.length > 12) window.__ybdHooked.shift();
                  }catch(e){}
                }
                var of = window.fetch;
                if (of) {
                  window.fetch = function(){
                    return of.apply(this, arguments).then(function(res){
                      try{ res.clone().text().then(function(t){ keep(res.url, t); }); }catch(e){}
                      return res;
                    });
                  };
                }
                var ox = XMLHttpRequest.prototype.open;
                XMLHttpRequest.prototype.open = function(m, u){ this.__ybdUrl = u; return ox.apply(this, arguments); };
                var os = XMLHttpRequest.prototype.send;
                XMLHttpRequest.prototype.send = function(){
                  var xhr = this;
                  xhr.addEventListener('load', function(){ try{ keep(xhr.__ybdUrl, xhr.responseText); }catch(e){} });
                  return os.apply(this, arguments);
                };
                window.__ybdSniffer = true;
                return 'installed';
              } catch(e) { return 'err:' + (e && e.message ? e.message : e); }
            })()
        """.trimIndent()

        val SNIFFER_READ_SCRIPT = """
            (function(){
              try { return encodeURIComponent(JSON.stringify((window.__ybdHooked||[]).slice(-8))); }
              catch(e) { return ''; }
            })()
        """.trimIndent()
        val PICKER_TEXT_MARKERS = listOf(
            "本地相册",
            "ip角色",
            "最近项目",
            "相机胶卷",
            "选择照片",
            "所有照片",
            "选择项目",
        )
        val CAMERA_ALBUM_TEXT = listOf("相册", "照片", "从相册选择", "图库", "Gallery", "Photos")
        val PROGRESS_REGEX = Regex("(?:已完成\\s*)?(\\d+)\\s*/\\s*(\\d+)")

        /** 接口读进度的整体超时：页面在该时间拿不到就退回文字解析。 */
        const val BRIDGE_PROGRESS_TIMEOUT_MS = 2_500L
        /** 单任务计数上限，用于过滤异常值。 */
        const val MAX_TASK_COUNTER = 20

        /**
         * 在页面里发一次 get-current-tasks，把结果 base64 塞进隐藏节点。
         * base64 是为了避开 evaluateJavascript 回传字符串的转义地狱。
         */
        val BRIDGE_PROGRESS_FETCH_JS = """
            (function(){
              try {
                var id = '__yb_progress';
                var el = document.getElementById(id);
                if (!el) {
                  el = document.createElement('div');
                  el.id = id;
                  el.style.display = 'none';
                  (document.body || document.documentElement).appendChild(el);
                }
                if (!window.HYJSBridge || !HYJSBridge.request) { el.textContent = 'NOBRIDGE'; return; }
                el.textContent = 'PENDING';
                HYJSBridge.request({
                  path: '/api/v1/activity_center/get-current-tasks',
                  method: 'POST',
                  data: {},
                  success: function(r){
                    try {
                      var t = (typeof r === 'string') ? r : JSON.stringify(r);
                      el.textContent = 'B64:' + btoa(unescape(encodeURIComponent(t)));
                    } catch(e) { el.textContent = 'ERR'; }
                  },
                  fail: function(){ el.textContent = 'FAIL'; }
                });
              } catch(e) { }
            })()
        """.trimIndent()

        val BRIDGE_PROGRESS_READ_JS = """
            (function(){
              var el = document.getElementById('__yb_progress');
              return el ? el.textContent : '';
            })()
        """.trimIndent()

        val SEND_IDS = listOf(
            "ivConversationInputSend",
            "flSend",
            "fl_send_stop",
            "iv_send",
            "btn_send",
        )
        val SEND_TEXT = listOf("发送", "提交", "Send")
        val GENERATE_IDS = listOf("btn_generate", "tv_generate", "generate", "submit")
        val GENERATE_TEXT = listOf("开始写作", "生成文章", "立即生成", "开始生成", "生成", "提交")
        val ALBUM_IDS = listOf(
            "im_album_btn",
            "im_album_container",
            "iv_album",
            "btn_album",
        )
        val ALBUM_TEXT = listOf("相册", "从相册选择", "选择图片", "上传图片", "添加图片")
        val MEDIA_CONFIRM_IDS = listOf(
            "action_preview_selected",
            "ps_tv_complete",
            "ps_complete_select",
            "btn_done",
            "button_add",
            "button_confirm",
            "confirm_button",
            "menu_done",
            "confirm",
        )
        val MEDIA_CONFIRM_TEXT = listOf(
            "确认",
            "确定",
            "框选完成",
            "选择",
            "选择此项",
            "选取",
            "完成",
            "添加",
            "下一步",
            "使用照片",
            "使用此照片",
            "Done",
            "Use photo",
        )
        val PROBLEM_SEND_IDS = listOf("confirm", "confirm_send", "send_question")
        val PROBLEM_SEND_TEXT = listOf("发送并解题", "开始解题", "立即解题", "解题", "发送", "确认")
        val TEMPLATE_USE_TEXT = listOf(
            "做同款",
            "使用此模板",
            "使用该模板",
            "立即使用",
            "使用模板",
            "立即制作",
            "套用模板",
            "用此模板",
        )
        val TEMPLATE_SEND_TEXT = listOf(
            "发送",
            "提交",
            "完成",
            "确认",
            "立即生成",
            "开始创作",
            "开始制作",
            "生成同款",
            "确认制作",
            "完成创作",
            "下一步",
        )
        val TEMPLATE_SEND_IDS = listOf(
            "ivConversationInputSend",
            "flSend",
            "fl_send_stop",
            "iv_send",
            "btn_send",
            "btn_submit",
            "submit",
            "confirm",
        )
    }
}
