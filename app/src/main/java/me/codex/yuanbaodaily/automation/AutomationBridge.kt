package me.codex.yuanbaodaily.automation

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.SystemClock
import me.codex.yuanbaodaily.BuildConfig
import me.codex.yuanbaodaily.config.YuanbaoConfigProvider
import me.codex.yuanbaodaily.hook.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal object AutomationBridge {
    private const val METHOD = "automation"
    private const val ROOT_METHOD = "root_input"

    /**
     * dump 走**无障碍服务进程**代跑，所以需要 context 才能发起 IPC。
     *
     * 桥接的其余 API 都是"按需传 context"，但 [uiNodes] 被大量无 context 的
     * 私有方法调用（`dumpSnapshot` 等），逐个改签名收益很低。这里在每次
     * 带 context 的公开入口自动记一份 applicationContext，供 dump 使用。
     * 只存 applicationContext，不会泄漏 Activity。
     */
    private var appContext: Context? = null
    private const val KEY_ACTION = "action"
    private const val KEY_ALIASES = "aliases"
    private const val KEY_BLOCKED_KEYS = "blocked_keys"
    private const val KEY_SELECTED_KEY = "selected_key"
    private const val KEY_IDS = "ids"
    private const val KEY_VALUE = "value"
    private const val KEY_OUTPUT = "output"
    private const val KEY_MARKER = "marker"
    private const val KEY_X_FRACTION = "x_fraction"
    private const val KEY_Y_FRACTION = "y_fraction"
    private const val KEY_X = "x"
    private const val KEY_Y = "y"
    /** 输入栏几何里"发送键"的绝对坐标（区别于 KEY_X/KEY_Y 的编辑框中心）。 */
    private const val KEY_SEND_X = "send_x"
    private const val KEY_SEND_Y = "send_y"
    private const val KEY_SEND_SAFE = "send_safe"
    private const val KEY_EDITOR_LEFT = "editor_left"
    private const val KEY_EDITOR_TOP = "editor_top"
    private const val KEY_EDITOR_RIGHT = "editor_right"
    private const val KEY_EDITOR_BOTTOM = "editor_bottom"
    private const val KEY_OK = "ok"
    private const val KEY_TEXT = "text"
    private const val KEY_PACKAGE = "package"
    private const val KEY_SIGNATURE = "signature"
    private const val KEY_CONFIRM_VISIBLE = "confirm_visible"
    private const val KEY_PICKER_VISIBLE = AutomationAccessibilityService.KEY_PICKER_VISIBLE
    private const val KEY_WINDOWS = AutomationAccessibilityService.KEY_WINDOWS
    private const val KEY_ACTIVITY = AutomationAccessibilityService.KEY_ACTIVITY
    private const val KEY_CANDIDATE_COUNT = "candidate_count"
    private const val KEY_ALLOW_CONTAINS = "allow_contains"
    private const val KEY_CODE = "code"
    private const val ENTER_KEY_CODE = 66
    private const val MAX_PROVIDER_ATTEMPTS = 3

    /** 节拍同步的重试次数与间隔（见 [syncTapPaceInBackground]）。 */
    private const val SYNC_TAP_PACE_ATTEMPTS = 12
    private const val TAP_PACE_SYNC_RETRY_MS = 500L

    data class Snapshot(
        val text: String,
        val packageName: String,
        val signature: String,
        val confirmVisible: Boolean,
        /** A picker window is present even when the host window is still active. */
        val pickerVisible: Boolean,
        /** Diagnostic: which windows the accessibility service could see. */
        val windows: String,
        /** Diagnostic: activity class of the window that last changed. */
        val activity: String,
    )
    data class TextClickResult(val clicked: Boolean, val key: String)
    data class MediaTapResult(
        val dispatched: Boolean,
        val candidateKey: String,
        val x: Int,
        val y: Int,
        val candidateCount: Int,
        val packageName: String,
    )

    @Volatile
    private var cachedScreenWidth = 0
    @Volatile
    private var cachedScreenHeight = 0
    @Volatile
    private var cachedScreenSizeAt = 0L

    /**
     * 当前屏幕的**真实宽度**（像素，含导航栏）。
     *
     * 所有"在左半屏/右半屏""节点是不是跨屏容器"的判据都必须基于它，
     * 不能再用开发机上量出来的绝对像素 —— 那会在别的分辨率上直接失效。
     *
     * 取不到时返回 0，由调用方退化成 [WelfareNav] 内部的参考宽度。
     * 带 5 秒缓存：这些判据在导航循环里会被连续调用，没必要每次都走一次 binder。
     */
    /**
     * 当前屏幕的**真实高度**（像素，含状态栏与导航栏）。
     *
     * 为什么必须走服务侧而不是 `context.resources.displayMetrics`：
     * 应用自己的 `displayMetrics` 给的是**可用窗口**尺寸（实测 2603，屏幕是 2800），
     * 而 `nodeCenter` 返回的节点坐标是 `getBoundsInScreen()` 的**全屏绝对坐标**。
     * 两者混用会让"绝对坐标 → 比例"的换算整体偏移（底部的按钮会被算到更靠下），
     * 免 root 版尤其容易踩 —— 它没有 `resumedActivity`，拿不到宿主的真实尺寸。
     */
    fun screenHeight(context: Context): Int {
        val now = SystemClock.uptimeMillis()
        if (cachedScreenHeight > 0 && now - cachedScreenSizeAt < SCREEN_SIZE_CACHE_MS) {
            return cachedScreenHeight
        }
        val height = action(context, AutomationAccessibilityService.ACTION_SCREEN_SIZE)
            ?.takeIf { it.getBoolean(KEY_OK, false) }
            ?.getInt(AutomationAccessibilityService.KEY_SCREEN_HEIGHT, 0)
            ?: 0
        if (height > 0) {
            cachedScreenHeight = height
            cachedScreenSizeAt = now
            return height
        }
        return runCatching { context.resources.displayMetrics.heightPixels }.getOrDefault(0)
    }

    fun screenWidth(context: Context): Int {
        val now = SystemClock.uptimeMillis()
        if (cachedScreenWidth > 0 && now - cachedScreenSizeAt < SCREEN_SIZE_CACHE_MS) {
            return cachedScreenWidth
        }
        val width = action(context, AutomationAccessibilityService.ACTION_SCREEN_SIZE)
            ?.takeIf { it.getBoolean(KEY_OK, false) }
            ?.getInt(AutomationAccessibilityService.KEY_SCREEN_WIDTH, 0)
            ?: 0
        if (width > 0) {
            cachedScreenWidth = width
            cachedScreenSizeAt = now
            return width
        }
        // 无障碍通道不可用时退回 App 自己的 displayMetrics（免 root 版足够准）。
        return runCatching { context.resources.displayMetrics.widthPixels }.getOrDefault(0)
    }

    /**
     * 当前设备的屏幕尺度：**全屏**像素 + dpi，供 [ScreenScale] 换算用。
     *
     * 三件事必须一起成立，否则换算出来的落点会整体偏移：
     * 1. 宽高取 `Display.getRealSize()`（经无障碍服务转发），**不是** `displayMetrics` ——
     *    后者是"可用窗口"，实测 2603 而全屏是 2800；
     * 2. dpi 取本进程 `displayMetrics.densityDpi`（同一台设备上各进程一致）；
     * 3. 面板分辨率与实际渲染档可能不同（一加 Ace 6：面板 2800×1272，默认渲染 2378×1080），
     *    所以这里一律用**运行时实测值**，绝不按型号查表。
     */
    fun screenScale(context: Context): ScreenScale {
        val width = screenWidth(context)
        val height = screenHeight(context)
        val dpi = runCatching { context.resources.displayMetrics.densityDpi }.getOrDefault(0)
        return ScreenScale.of(width, height, dpi)
    }

    /**
     * OCR 兜底读屏。**只在无障碍读不到字时调用**（判据见 [OcrFallbackPolicy]）。
     *
     * 返回 null 表示通道本身不可达（无障碍服务没起来）；`ok=false` 表示系统拒绝了截图。
     */
    fun ocrRead(context: Context): OcrReadResult? {
        val output = action(context, AutomationAccessibilityService.ACTION_OCR_TEXT) ?: return null
        val ok = output.getBoolean(KEY_OK, false)
        if (!ok) {
            return OcrReadResult(
                ok = false,
                reason = output.getString(AutomationAccessibilityService.KEY_OCR_REASON).orEmpty(),
            )
        }
        val raw = output.getParcelableArrayList<Bundle>(AutomationAccessibilityService.KEY_OCR_ITEMS)
        val items = raw.orEmpty().mapNotNull { b ->
            val label = b.getString(KEY_TEXT).orEmpty()
            if (label.isBlank()) return@mapNotNull null
            OcrItem(
                text = label,
                left = b.getInt(KEY_X, -1),
                top = b.getInt(KEY_Y, -1),
                right = b.getInt("right", -1),
                bottom = b.getInt("bottom", -1),
            )
        }
        return OcrReadResult(ok = true, items = items)
    }

    /** 一次"点掉厂商安全提示"的结果。 */
    data class GuardResult(
        /** 是否真的点成功。 */
        val clicked: Boolean,
        /** 是否看到了安全中心窗口（false 表示根本没这个弹窗）。 */
        val seen: Boolean,
        /** 点到的按钮文案。 */
        val label: String,
    )

    /**
     * 点掉厂商安全中心弹的"是否保留无障碍权限"。
     *
     * 只点**精确等于**「保持开启 / 保持使用 / 保持允许 / 继续使用」的节点，
     * 白名单包名见 [SystemGuardPolicy]。找不到就返回 `clicked=false` ——
     * 调用方据此**什么都不做**，而不是退化成按坐标点（那会点到「关闭无障碍」）。
     */
    fun dismissSystemGuard(context: Context): GuardResult? {
        val out = action(context, AutomationAccessibilityService.ACTION_DISMISS_GUARD)
            ?: return null
        return GuardResult(
            clicked = out.getBoolean(KEY_OK, false),
            seen = out.getBoolean(AutomationAccessibilityService.KEY_GUARD_SEEN, false),
            label = out.getString(KEY_TEXT).orEmpty(),
        )
    }

    fun available(context: Context): Boolean = call(
        context,
        METHOD,
        Bundle().apply { putString(KEY_ACTION, "status") },
    )?.getBoolean(KEY_OK, false) == true

    /**
     * 让**模块进程**强制重绑自己的无障碍服务（自愈）。
     *
     * 返回人可读报告，形如 `repaired bound=true listed=true ...`；
     * 失败时以 `failed ` 开头并说明原因（最常见的是没有
     * `WRITE_SECURE_SETTINGS`）。
     *
     * 这条链路刻意**不依赖无障碍服务是否活着**：它走的是模块进程里的静态对象，
     * 服务掉线时调用它反而能顺手把模块进程拉起来。见
     * [AutomationCommandDispatcher.METHOD_A11Y_REPAIR]。
     *
     * 最坏耗时约 7 秒（unbind 沉降 0.9s + 重绑等待上限 6s），
     * 所以必须在**后台线程**调用，不要放主线程。
     */
    fun repairAccessibility(context: Context): String {
        val output = call(context, A11Y_REPAIR_METHOD, Bundle()) ?: return "failed 模块通道不可达（transport=none）"
        return output.getString(AutomationCommandDispatcher.KEY_REPAIR_REPORT)
            ?: if (output.getBoolean(KEY_OK, false)) "repaired（模块未回传细节）" else "failed 模块未回传报告"
    }

    fun snapshot(context: Context): Snapshot {
        val output = action(context, AutomationAccessibilityService.ACTION_READ_TEXT)
        // The accessibility tree is authoritative whenever the service could
        // actually see something.
        if (output?.getBoolean(KEY_OK, false) == true) {
            return Snapshot(
                text = output.getString(KEY_TEXT).orEmpty(),
                packageName = output.getString(KEY_PACKAGE).orEmpty(),
                signature = output.getString(KEY_SIGNATURE).orEmpty(),
                confirmVisible = output.getBoolean(KEY_CONFIRM_VISIBLE, false),
                pickerVisible = output.getBoolean(KEY_PICKER_VISIBLE, false),
                windows = output.getString(KEY_WINDOWS).orEmpty(),
                activity = output.getString(KEY_ACTIVITY).orEmpty(),
            )
        }
        // `ok=false` means no allowed window was visible at all: the usual state
        // when the host is a cloned ("双开") app in another Android user. The
        // platform dump is then the only readable description of the screen.
        dumpSnapshot()?.let { return it }
        return Snapshot(
            text = output?.getString(KEY_TEXT).orEmpty(),
            packageName = output?.getString(KEY_PACKAGE).orEmpty(),
            signature = output?.getString(KEY_SIGNATURE).orEmpty(),
            confirmVisible = output?.getBoolean(KEY_CONFIRM_VISIBLE, false) == true,
            pickerVisible = output?.getBoolean(KEY_PICKER_VISIBLE, false) == true,
            windows = output?.getString(KEY_WINDOWS).orEmpty(),
            activity = output?.getString(KEY_ACTIVITY).orEmpty(),
        )
    }

    /**
     * Nodes of the platform UI dump, read through the *local* root channel.
     *
     * Local on purpose: in the cloned case the module process belongs to another
     * user, and a full dump must not travel over Binder. Cached briefly because
     * the runner polls the screen several times per second while a dump costs
     * about a second.
     */
    fun uiNodes(force: Boolean = false): List<ScreenScanner.DumpNode> {
        val now = SystemClock.uptimeMillis()
        if (!force && dumpNodes.isNotEmpty() && now - dumpAt < DUMP_TTL_MS) return dumpNodes
        if (!force && now < dumpBlockedUntil) return dumpNodes
        val fresh = readUiDump() ?: return dumpNodes
        dumpNodes = fresh
        dumpAt = SystemClock.uptimeMillis()
        return fresh
    }

    private fun readUiDump(): List<ScreenScanner.DumpNode>? {
        val extras = Bundle().apply {
            putString(KEY_ACTION, AutomationAccessibilityService.ACTION_DUMP)
        }
        // 走**无障碍服务进程**代跑 su（见 AutomationCommandDispatcher.METHOD_UI_DUMP）：
        // KernelSU 没授权元宝的 uid，宿主进程直接 su 会静默挂起。
        // 只有服务端不可达（双开/未启用）时才退回本进程直调。
        val result = appContext?.let { call(it, DUMP_METHOD, extras) }
            ?: runCatching { RootInputController.perform(extras) }.getOrNull()
        val raw = result?.getString(KEY_OUTPUT).orEmpty()
        if (!raw.contains("<hierarchy")) {
            // Report *why*: a denied `su` and a broken uiautomator look identical
            // unless the exit code and the shell text are shown. Back off so a
            // permanently denied channel is not retried once per poll.
            dumpBlockedUntil = SystemClock.uptimeMillis() + DUMP_RETRY_BACKOFF_MS
            Log.w(
                "ui dump failed exit=${result?.getInt(KEY_EXIT, Int.MIN_VALUE)} " +
                    "chars=${raw.length} out=${raw.take(160).replace('\n', '|')}",
            )
            return null
        }
        val nodes = ScreenScanner.parseDump(raw)
        if (nodes.isEmpty()) return null
        Log.d("ui dump nodes=${nodes.size} package=${nodes.firstOrNull()?.packageName.orEmpty()}")
        return nodes
    }

    private fun dumpSnapshot(): Snapshot? {
        val nodes = uiNodes()
        if (nodes.isEmpty()) return null
        val text = nodes.mapNotNull { it.label.takeIf(String::isNotBlank) }.joinToString("\n")
        if (text.isBlank()) return null
        val packageName = nodes.firstOrNull()?.packageName.orEmpty()
        val normalized = text.lowercase()
        val pickerVisible = AccessibilityPackagePolicy.isMediaPackage(packageName) ||
            (PICKER_TEXT_MARKERS.any(normalized::contains) && !normalized.contains("福利中心"))
        val confirmVisible = nodes.any { node ->
            val label = node.label.lowercase().trim()
            CONFIRM_TEXT.any { it.equals(label, ignoreCase = true) } ||
                CONFIRM_ID_HINTS.any { node.resourceId.lowercase().contains(it) }
        }
        return Snapshot(
            text = text,
            packageName = packageName,
            signature = Integer.toHexString("$packageName|$text".hashCode()),
            confirmVisible = confirmVisible,
            pickerVisible = pickerVisible,
            windows = "dump:${nodes.size}",
            activity = "",
        )
    }

    fun readText(context: Context): String = snapshot(context).text

    fun clickText(
        context: Context,
        aliases: List<String>,
        allowContains: Boolean = false,
    ): Boolean {
        val accessible = action(
            context,
            AutomationAccessibilityService.ACTION_CLICK_TEXT,
            Bundle().apply {
                putStringArrayList(KEY_ALIASES, ArrayList(aliases))
                putBoolean(KEY_ALLOW_CONTAINS, allowContains)
            },
        ).ok()
        if (accessible) return true
        return dumpClickText(context, aliases, allowContains)
    }

    private fun dumpClickText(
        context: Context,
        aliases: List<String>,
        allowContains: Boolean,
    ): Boolean {
        val nodes = uiNodes()
        if (nodes.isEmpty()) return false
        val exact = ScreenScanner.findExact(nodes, aliases)
        val hit = exact ?: ScreenScanner.findToken(nodes, aliases)
        if (hit == null) {
            Log.d("clickText dump found no target aliases=$aliases nodes=${nodes.size}")
            return false
        }
        val tapped = rootTap(context, hit.box.centerX, hit.box.centerY)
        Log.d(
            "clickText dump label=${hit.label.take(40)} exact=${exact != null} " +
                "containsAllowed=$allowContains tapped=$tapped",
        )
        return tapped
    }

    fun clickNextText(
        context: Context,
        aliases: List<String>,
        blockedKeys: Set<String>,
        allowContains: Boolean = false,
    ): TextClickResult {
        val output = action(
            context,
            AutomationAccessibilityService.ACTION_CLICK_TEXT_NEXT,
            Bundle().apply {
                putStringArrayList(KEY_ALIASES, ArrayList(aliases))
                putStringArrayList(KEY_BLOCKED_KEYS, ArrayList(blockedKeys))
                putBoolean(KEY_ALLOW_CONTAINS, allowContains)
            },
        )
        val accessible = TextClickResult(
            clicked = output.ok(),
            key = output?.getString(KEY_SELECTED_KEY).orEmpty(),
        )
        if (accessible.clicked) return accessible
        return dumpClickNextText(context, aliases, blockedKeys)
    }

    private fun dumpClickNextText(
        context: Context,
        aliases: List<String>,
        blockedKeys: Set<String>,
    ): TextClickResult {
        val nodes = uiNodes()
        if (nodes.isEmpty()) return TextClickResult(false, "")
        val matches = ScreenScanner.findAllTokens(nodes, aliases)
        if (matches.isEmpty()) {
            Log.d("clickNextText dump found no target aliases=$aliases nodes=${nodes.size}")
            return TextClickResult(false, "")
        }
        val keyCounts = mutableMapOf<String, Int>()
        val indexed = matches.map { node ->
            val base = "${node.resourceId}|${node.label}|" +
                "${node.box.width}x${node.box.height}"
            val ordinal = keyCounts.getOrDefault(base, 0)
            keyCounts[base] = ordinal + 1
            node to "$base#dump$ordinal"
        }
        val candidate = indexed.firstOrNull { (_, key) -> key !in blockedKeys }
            ?: return TextClickResult(false, "")
        val (node, key) = candidate
        val tapped = rootTap(context, node.box.centerX, node.box.centerY)
        Log.d("clickNextText dump label=${node.label.take(40)} key=$key tapped=$tapped")
        return TextClickResult(tapped, if (tapped) key else "")
    }

    fun setText(context: Context, value: String): Boolean = action(
        context,
        AutomationAccessibilityService.ACTION_SET_TEXT,
        Bundle().apply { putString(KEY_VALUE, value) },
    ).ok()

    /** 把输入框清空（用于清掉上一轮"写成功但没发出去"的残留文案）。 */
    fun clearComposer(context: Context): Boolean =
        action(context, AutomationAccessibilityService.ACTION_CLEAR_COMPOSER).ok()

    /**
     * Taps the editable field the platform dump exposes.
     *
     * A cloned host has no accessibility here, so this is what focuses the
     * composer before the host's own input connection (or the root channel)
     * delivers the text.
     */
    fun focusDumpEditor(context: Context): Boolean {
        val nodes = uiNodes()
        if (nodes.isEmpty()) return false
        val editable = ScreenScanner.findEditable(nodes)
        if (editable == null) {
            Log.d("focus editor dump found no editable nodes=${nodes.size}")
            return false
        }
        val tapped = rootTap(context, editable.box.centerX, editable.box.centerY)
        Log.d("focus editor dump bounds=${editable.box} tapped=$tapped")
        if (tapped) SystemClock.sleep(240L)
        return tapped
    }

    fun clickIds(context: Context, ids: List<String>): Boolean {
        val accessible = action(
            context,
            AutomationAccessibilityService.ACTION_CLICK_ID,
            Bundle().apply { putStringArrayList(KEY_IDS, ArrayList(ids)) },
        ).ok()
        if (accessible) return true
        val nodes = uiNodes()
        if (nodes.isEmpty()) return false
        val hit = ScreenScanner.findByResourceId(nodes, ids)
        if (hit == null) {
            Log.d("clickIds dump found no target ids=$ids nodes=${nodes.size}")
            return false
        }
        val tapped = rootTap(context, hit.box.centerX, hit.box.centerY)
        Log.d("clickIds dump id=${hit.resourceId} tapped=$tapped")
        return tapped
    }

    fun clickLatestMedia(
        context: Context,
        marker: String,
        blockedKeys: Set<String>,
    ): MediaTapResult {
        val output = action(
            context,
            AutomationAccessibilityService.ACTION_CLICK_LATEST_MEDIA,
            Bundle().apply {
                putString(KEY_MARKER, marker)
                putStringArrayList(KEY_BLOCKED_KEYS, ArrayList(blockedKeys))
            },
        )
        val accessible = MediaTapResult(
            dispatched = output.ok(),
            candidateKey = output?.getString(KEY_SELECTED_KEY).orEmpty(),
            x = output?.getInt(KEY_X, -1) ?: -1,
            y = output?.getInt(KEY_Y, -1) ?: -1,
            candidateCount = output?.getInt(KEY_CANDIDATE_COUNT, 0) ?: 0,
            packageName = output?.getString(KEY_PACKAGE).orEmpty(),
        )
        // A real candidate means the accessibility tree described the picker;
        // otherwise (cloned host in another user) fall back to the dump.
        if (accessible.x >= 0 && accessible.y >= 0 && accessible.candidateCount > 0) {
            return accessible
        }
        return dumpClickLatestMedia(context, marker, blockedKeys) ?: accessible
    }

    private fun dumpClickLatestMedia(
        context: Context,
        marker: String,
        blockedKeys: Set<String>,
    ): MediaTapResult? {
        val nodes = uiNodes()
        if (nodes.isEmpty()) return null
        val packageName = nodes.firstOrNull()?.packageName.orEmpty()
        if (AccessibilityPackagePolicy.isCameraPackage(packageName)) return null
        val screenText = nodes.mapNotNull { it.label.takeIf(String::isNotBlank) }.joinToString("\n")
        val candidates = DumpMediaCandidates.build(nodes, screenText, screenWidth(context))
        val candidate = MediaCandidatePolicy.choose(
            candidates = candidates,
            marker = marker,
            screenText = screenText.lowercase(),
            blockedKeys = blockedKeys,
        )
        if (candidate == null) {
            Log.d("media dump candidates=${candidates.size} package=$packageName blocked=${blockedKeys.size}")
            return MediaTapResult(false, "", -1, -1, candidates.size, packageName)
        }
        val tapped = rootTap(context, candidate.centerX, candidate.centerY)
        Log.d(
            "media dump tap=$tapped key=${candidate.key} ordinal=${candidate.ordinal} " +
                "score=${candidate.score} xy=${candidate.centerX},${candidate.centerY} " +
                "candidates=${candidates.size}",
        )
        return MediaTapResult(
            dispatched = tapped,
            candidateKey = candidate.key,
            x = candidate.centerX,
            y = candidate.centerY,
            candidateCount = candidates.size,
            packageName = candidate.key.substringBefore('|').ifBlank { packageName },
        )
    }

    /** 把剪贴板内容粘贴进输入框（插入语义，不清空已有图片附件）。 */
    fun pasteIntoEditor(context: Context): Boolean =
        action(context, AutomationAccessibilityService.ACTION_PASTE_EDITOR).ok()

    /** 按文字找节点并派发**真实触摸**（Compose 列表项不吃 a11y click ✗）。 */
    fun tapText(context: Context, label: String): Boolean =
        action(
            context,
            AutomationAccessibilityService.ACTION_TAP_TEXT,
            Bundle().apply { putString("text", label) },
        ).ok()

    /**
     * 按**文字或 content-desc** 找节点，用它自己的真实 bounds 中心点一下。
     *
     * 用于"只有 content-desc、内层不可点"的入口（例如元宝首页左上角的
     * `抽屉页入口` / `ic_navigation_show`）。无障碍侧会优先取可点祖先的矩形，
     * 再取面积最小的候选，避免点到整屏容器。找不到就返回 false，不做坐标兜底。
     */
    fun tapNodeByLabel(context: Context, label: String): Boolean =
        action(
            context,
            AutomationAccessibilityService.ACTION_TAP_NODE_BY_LABEL,
            Bundle().apply { putString(KEY_TEXT, label) },
        ).ok()

    /** 点输入框节点自己的中心（纯节点驱动，不用比例坐标）。 */
    fun tapEditorCenter(context: Context): Boolean =
        action(context, AutomationAccessibilityService.ACTION_TAP_EDITOR_CENTER).ok()

    /** 点"写作页"的发送 ⬆（写作页布局与对话页不同，坐标单独核对过 ✓）。 */
    fun tapWritingSend(context: Context): Boolean =
        action(context, AutomationAccessibilityService.ACTION_TAP_WRITING_SEND).ok()

    /** 找"输入框右侧同一行"的按钮并点击（结构性定位，不猜坐标）。 */
    fun clickSendRightOfEditor(context: Context): Boolean =
        action(context, AutomationAccessibilityService.ACTION_CLICK_SEND_RIGHT_OF_EDITOR).ok()

    /** 点对话页的发送 ⬆（模块按**绝对像素**算，避开比例换算偏差）。 */
    fun tapChatSend(context: Context): Boolean =
        action(context, AutomationAccessibilityService.ACTION_TAP_CHAT_SEND).ok()

    /**
     * 屏幕底部输入框所在行的**纵向比例**（0~1，按模块自己的屏幕高度算）；
     * null = 当前没有"底部输入框"（即不在对话页）。
     */
    fun bottomEditorRowFraction(context: Context): Float? {
        val output = action(context, AutomationAccessibilityService.ACTION_BOTTOM_EDITOR_ROW) ?: return null
        if (!output.ok()) return null
        val scaled = output.getInt(KEY_Y, -1)
        if (scaled <= 0) return null
        return scaled / 10_000f
    }

    /**
     * 按**无障碍树里的真实几何**点发送键。
     *
     * 这是发送动作的首选通道：模块自己在服务端算出输入框和同行按钮的真实 bounds，
     * 派发前还会做"点是否落在输入栏安全区"的校验。找不到节点或校验不过就返回 false，
     * 调用方必须**放弃**而不是退回到写死的比例坐标 —— 那正是误触的来源。
     */
    fun tapSendByGeometry(context: Context): Boolean =
        action(context, AutomationAccessibilityService.ACTION_TAP_SEND_SAFE).ok()

    /** 输入栏真实几何（编辑框中心 + 发送键坐标），null = 当前不在对话页/能力页。 */
    data class ComposerGeometryInfo(
        val editorCenterX: Int,
        val editorCenterY: Int,
        val sendX: Int,
        val sendY: Int,
        val editorLeft: Int = -1,
        val editorTop: Int = -1,
        val editorRight: Int = -1,
        val editorBottom: Int = -1,
        /** 最终落点是否通过了 [ComposerGeometry.isSafeActionPoint] 校验。 */
        val safe: Boolean = false,
    )

    fun composerGeometry(context: Context): ComposerGeometryInfo? {
        val output = action(context, AutomationAccessibilityService.ACTION_COMPOSER_GEOMETRY) ?: return null
        if (!output.ok()) return null
        val x = output.getInt(KEY_X, -1)
        val y = output.getInt(KEY_Y, -1)
        if (x < 0 || y < 0) return null
        return ComposerGeometryInfo(
            editorCenterX = x,
            editorCenterY = y,
            sendX = output.getInt(KEY_SEND_X, -1),
            sendY = output.getInt(KEY_SEND_Y, -1),
            editorLeft = output.getInt(KEY_EDITOR_LEFT, -1),
            editorTop = output.getInt(KEY_EDITOR_TOP, -1),
            editorRight = output.getInt(KEY_EDITOR_RIGHT, -1),
            editorBottom = output.getInt(KEY_EDITOR_BOTTOM, -1),
            safe = output.getBoolean(KEY_SEND_SAFE, false),
        )
    }

    /** 点输入框的**真实中心**（纯节点驱动，不做比例换算）。 */
    fun tapEditorByGeometry(context: Context): Boolean =
        action(context, AutomationAccessibilityService.ACTION_TAP_EDITOR_CENTER).ok()

    /**
     * 问服务进程要输入框的**文案状态**（[ComposerState.Text]）。
     *
     * 宿主读不到元宝的 Compose 输入框，这是唯一能判断"框里到底有没有字"的通道。
     * `null` = 通道不可达（服务未连接 / 双开宿主），调用方按"读不到"处理。
     */
    fun composerState(context: Context): ComposerState.Text? {
        val output = action(context, AutomationAccessibilityService.ACTION_COMPOSER_STATE)
            ?: return null
        val key = AutomationAccessibilityService.KEY_COMPOSER_STATE
        if (!output.getBoolean(KEY_OK, false) && output.getString(key) == null) {
            return null
        }
        val raw = output.getString(key).orEmpty()
        return ComposerState.Text.entries.firstOrNull { it.name == raw }
    }

    /** 服务进程看到的输入框原始文案（诊断用）。 */
    fun composerText(context: Context): String? = action(
        context,
        AutomationAccessibilityService.ACTION_COMPOSER_STATE,
    )?.getString(AutomationAccessibilityService.KEY_COMPOSER_TEXT)

    /**
     * 输入栏那颗 Compose `EditText` 现在还在无障碍树上吗。
     *
     * 用来区分两种"读不到文案"——**发送结果校验必须分清**（真机踩坑）：
     * - `EMPTY` + `editorFound=true`：看到输入框、它是空的 → **真的发出去了** ✓；
     * - `UNKNOWN` + `editorFound=false`：输入栏整块被替换 → 实测那是误开了 `＋` 附件面板，
     *   消息**根本没发出去**。旧代码把它当成"已清空"，于是 `clickSendButton` 返回 true，
     *   上层以为成功、不再重试，消息就这么丢了（`evidence/trace_d3_fill_send`）。
     *
     * `null` = 无障碍服务当前不可用（未知），调用方不要据此下结论。
     */
    fun composerEditorFound(context: Context): Boolean? {
        val output = action(context, AutomationAccessibilityService.ACTION_COMPOSER_STATE)
            ?: return null
        if (!output.getBoolean(KEY_OK, false) &&
            output.getString(AutomationAccessibilityService.KEY_COMPOSER_STATE) == null
        ) {
            return null
        }
        return output.getBoolean(AutomationAccessibilityService.KEY_COMPOSER_EDITOR_FOUND, false)
    }

    /** 点底栏最右侧的小圆图标（"全部任务"菜单按钮，位置随滚动而变）。 */
    fun tapBottomRightIcon(context: Context): Boolean =
        action(context, AutomationAccessibilityService.ACTION_TAP_BOTTOM_RIGHT_ICON).ok()

    /** 慢速水平拖动（拖底栏露出"全部任务"圆图标）。 */
    fun dragBottomBar(context: Context, from: Float = 0.85f, to: Float = 0.60f, y: Float = 0.838f): Boolean =
        action(
            context,
            AutomationAccessibilityService.ACTION_DRAG_HORIZONTAL,
            Bundle().apply {
                putFloat("from_fraction", from)
                putFloat("to_fraction", to)
                putFloat("y_fraction", y)
            },
        ).ok()

    /** 点底部导航第 index 个（按屏幕底部一排可点节点从左到右排序）。 */
    /**
     * 点底部导航栏。
     *
     * @param label 期望的标签（如"我们"）。**建议总是传**：底栏换版后同一个 index
     *   可能指向完全不同的东西（实测 2.87.20 的 index=0 是"模型"选择器，一点就弹窗），
     *   传了标签服务端会先核对，对不上就不点。
     */
    fun tapBottomTab(context: Context, index: Int, label: String = ""): Boolean = action(
        context,
        AutomationAccessibilityService.ACTION_TAB_TAP,
        Bundle().apply {
            putInt("index", index)
            putString(KEY_TEXT, label)
        },
    ).ok()

    /** 取某个文字节点的真实屏幕中心（换版本后导航/入口位置会变，用它代替写死坐标）。 */
    fun nodeCenter(context: Context, label: String, bottomOnly: Boolean = false): Pair<Int, Int>? {
        val output = action(
            context,
            AutomationAccessibilityService.ACTION_NODE_BOUNDS,
            Bundle().apply { putString(KEY_TEXT, label); putBoolean("bottom_only", bottomOnly) },
        ) ?: return null
        if (!output.ok()) return null
        val x = output.getInt(KEY_X, -1)
        val y = output.getInt(KEY_Y, -1)
        if (x < 0 || y < 0) return null
        Log.d("nodeCenter label=$label xy=$x,$y")
        return x to y
    }

    /** 点窗口底部最靠下的可点按钮（选择器的“确定/完成”常无文字无 ID）。 */
    fun clickBottom(context: Context): Boolean =
        action(context, AutomationAccessibilityService.ACTION_CLICK_BOTTOM).ok()

    /** 点开并聚焦输入框（之后可以用 commitText 追加文案，不会清掉已选图片）。 */
    fun focusEditor(context: Context): Boolean =
        action(context, AutomationAccessibilityService.ACTION_FOCUS_EDITOR).ok()

    /**
     * 点击"输入框右侧"的发送键。
     *
     * 智能P图这类 Compose 页面的 ⬆️ 没有文字/ID，且宿主拿不到平台 dump，
     * 所以由模块侧在无障碍树里找"与输入框同排、位于其右侧"的可点节点并点击。
     */
    fun clickButtonNearEditor(context: Context): Boolean =
        action(context, AutomationAccessibilityService.ACTION_CLICK_NEAR_EDITOR).ok()

    /**
     * 在窗口相对坐标处点击（先找最深可点节点，失败再手势）。
     *
     * Compose 页面上"没有文字/ID 的按钮"用这条通道，比裸手势可靠。
     */
    fun clickPointFraction(context: Context, xFraction: Float, yFraction: Float): Boolean {
        val accessible = action(
            context,
            AutomationAccessibilityService.ACTION_CLICK_POINT,
            Bundle().apply {
                putFloat(KEY_X_FRACTION, xFraction)
                putFloat(KEY_Y_FRACTION, yFraction)
            },
        ).ok()
        if (accessible) return true
        return tap(context, xFraction, yFraction)
    }

    fun tap(context: Context, xFraction: Float, yFraction: Float): Boolean {
        val accessible = action(
            context,
            AutomationAccessibilityService.ACTION_TAP,
            Bundle().apply {
                putFloat(KEY_X_FRACTION, xFraction)
                putFloat(KEY_Y_FRACTION, yFraction)
            },
        ).ok()
        if (accessible) return true
        // Cloned host: only the root channel can reach that user's screen.
        //
        // **基准必须是全屏尺寸**：`resources.displayMetrics` 是可用窗口（实测
        // 1272×2603，屏幕是 1272×2800），用它换算比例再交给 `input tap`，
        // 落点会整体上移一个导航栏的高度；三键导航机型（导航栏更高）偏得更多。
        // 这也是"同一个比例在开发机能点中、在别的机型点空"的典型原因。
        val width = screenWidth(context)
        val height = screenHeight(context)
        if (width <= 0 || height <= 0) return false
        val x = (width * xFraction.coerceIn(0.02f, 0.98f)).toInt()
        val y = (height * yFraction.coerceIn(0.02f, 0.98f)).toInt()
        return rootTap(context, x, y)
    }

    fun scrollForward(context: Context): Boolean =
        action(context, AutomationAccessibilityService.ACTION_SCROLL_FORWARD).ok() ||
            rootSwipe(context, forward = true)

    fun scrollBackward(context: Context): Boolean =
        action(context, AutomationAccessibilityService.ACTION_SCROLL_BACKWARD).ok() ||
            rootSwipe(context, forward = false)

    /**
     * Vertical swipe through the bounded root channel.
     *
     * The only way to scroll a cloned ("双开") host: its windows are in another
     * Android user, so `ACTION_SCROLL_*` and accessibility gestures never apply.
     */
    private fun rootSwipe(context: Context, forward: Boolean): Boolean {
        // 同 [tap]：必须用全屏尺寸，否则滑动起点/终点整体偏移一个导航栏。
        val width = screenWidth(context)
        val height = screenHeight(context)
        if (width <= 0 || height <= 0) return false
        val x = width / 2
        val fromY = if (forward) (height * 0.78f).toInt() else (height * 0.30f).toInt()
        val toY = if (forward) (height * 0.30f).toInt() else (height * 0.78f).toInt()
        return rootAction(
            context = context,
            action = "swipe",
            extras = Bundle().apply {
                putInt(KEY_X, x)
                putInt("from_y", fromY)
                putInt("to_y", toY)
                putInt("duration", 320)
            },
            label = if (forward) "swipe forward" else "swipe backward",
        )
    }

    fun back(context: Context): Boolean = action(
        context,
        AutomationAccessibilityService.ACTION_BACK,
    ).ok()

    /**
     * 把点击节拍同步给**模块进程**的无障碍服务。
     *
     * `TapTrace` 是普通 object，宿主进程与服务进程各有一份实例；宿主侧
     * `TapTrace.configure()` 只影响自己那份。不显式同步的话，无障碍层的
     * 点击（`tapAt` / 节点 `ACTION_CLICK`）永远是追踪盲区。
     */
    fun setTapPace(context: Context, paceMs: Long): Boolean = action(
        context,
        AutomationAccessibilityService.ACTION_SET_TAP_PACE,
        Bundle().apply { putLong("tap_pace_ms", paceMs) },
    ).ok()

    /**
     * 把节拍同步到无障碍服务进程，带重试，**阻塞**到成功或超时。
     *
     * 直接调 [setTapPace] 在自检广播里会失败：`onReceive` 跑在**宿主主线程**，
     * 而 `transactBinder` / `serviceCall` 都显式拒绝在主线程阻塞（避免死锁），
     * 只剩 `providerCall`；实测宿主与模块不在同一个 Android user 时 provider
     * 抛 `Unknown authority`，于是 `synced_to_service=false` ——
     * 无障碍层的每一次 `tapAt`/节点点击既没有节拍也没有 `TAPTRACE` 日志，
     * 慢速调试等于白跑（实测踩过）。
     *
     * 所以调用方必须在**后台线程**上调用本方法；同时容忍传输正在重建
     * （`module link` 会在无障碍服务重启后重新投递，通常 1~2 秒内恢复）。
     *
     * @return 是否同步成功。
     */
    fun syncTapPace(context: Context, paceMs: Long): Boolean {
        val app = context.applicationContext
        for (attempt in 1..SYNC_TAP_PACE_ATTEMPTS) {
            if (setTapPace(app, paceMs)) {
                Log.w("tap pace=$paceMs synced_to_service=true attempt=$attempt")
                return true
            }
            SystemClock.sleep(TAP_PACE_SYNC_RETRY_MS)
        }
        Log.w("tap pace=$paceMs synced_to_service=false（$SYNC_TAP_PACE_ATTEMPTS 次重试均失败）")
        return false
    }

    fun rootTap(context: Context, x: Int, y: Int): Boolean = rootAction(
        context = context,
        action = "tap",
        extras = Bundle().apply {
            putInt(KEY_X, x)
            putInt(KEY_Y, y)
        },
        label = "tap x=$x y=$y",
    )

    fun rootBack(context: Context): Boolean = rootAction(context, action = "back", label = "back")

    /**
     * Types text through the bounded root channel.
     *
     * Both the accessibility service and the module Provider belong to one
     * Android user. In a cloned ("双开") host neither of them is reachable, so
     * the injected host process falls back to `su`, which is not tied to a user.
     */
    fun rootSetText(context: Context, value: String): Boolean = rootAction(
        context = context,
        action = "text",
        extras = Bundle().apply { putString(KEY_VALUE, value) },
        label = "text ${value.length} chars",
    )

    /** Sends the current editor content with a real KEYCODE_ENTER. */
    fun rootEnter(context: Context): Boolean = rootAction(
        context = context,
        action = "key",
        extras = Bundle().apply { putInt(KEY_CODE, ENTER_KEY_CODE) },
        label = "enter",
    )

    /** True when `su` is granted; used by the module UI to explain the fallback. */
    fun rootAvailable(context: Context): Boolean =
        rootAction(context, action = "probe", label = "probe")

    private fun rootAction(
        context: Context,
        action: String,
        extras: Bundle = Bundle(),
        label: String = action,
    ): Boolean {
        // The injected host process already contains the module classes. Try the
        // bounded root operation locally first so an unreachable module Provider
        // (different user, missing clone) does not make navigation a no-op.
        extras.putString(KEY_ACTION, action)
        val direct = runCatching { RootInputController.perform(extras).ok() }.getOrDefault(false)
        if (direct) {
            Log.d("root direct $label dispatched")
            return true
        }
        return call(context, ROOT_METHOD, extras).ok()
    }

    private fun action(context: Context, action: String, extras: Bundle = Bundle()): Bundle? {
        extras.putString(KEY_ACTION, action)
        return call(context, METHOD, extras)
    }

    private fun call(context: Context, method: String, extras: Bundle): Bundle? {
        // dump 需要 context 才能走服务进程代跑 su；这里顺手记住一份。
        appContext = context.applicationContext
        // 免 root 版：调用方和被调方**在同一个进程**里，没有任何跨进程理由。
        // 直接同步分发，省掉 provider / binder 两跳，也就没有"包可见性"这一层风险
        // （免 root 版没有注入宿主，provider 那条路本来也走不通）。
        if (BuildConfig.STANDALONE && context.packageName == BuildConfig.APPLICATION_ID) {
            val direct = runCatching {
                AutomationCommandDispatcher.dispatch(context.applicationContext, method, extras)
            }.onFailure { Log.e("direct dispatch failed method=$method", it) }.getOrNull()
            if (direct != null) {
                noteTransport("direct")
                return direct
            }
        }
        // The Binder the module handed over is the only transport that survives
        // package visibility filtering, so try it first.
        handedBinder?.let { binder ->
            if (binder.isBinderAlive) {
                var result = transactBinder(binder, method, extras)
                if (result == null) {
                    // 宿主可能缓存了一个"看起来活着但已经不能用"的 binder（换版本的元宝上实测过）：
                    // 短暂等待后重试一次；仍失败就**丢弃它**，等模块的 pump 重新投递新的 binder。
                    SystemClock.sleep(250L)
                    result = transactBinder(binder, method, extras)
                }
                if (result != null) {
                    noteTransport("link")
                    return result
                }
                handedBinder = null
                Log.w("module link produced no result method=$method → 丢弃旧 binder，等待重新投递")
            } else {
                handedBinder = null
                Log.w("module link binder died; falling back to lookup transports")
            }
        }
        providerCall(context, method, extras)?.let {
            noteTransport("provider")
            return it
        }
        serviceCall(context, method, extras)?.let {
            noteTransport("service")
            return it
        }
        noteTransport("none")
        return null
    }

    /** Stores the command Binder the module process sent us. */
    fun acceptModuleLink(binder: IBinder) {
        if (handedBinder !== binder) Log.w("module link accepted from the module process")
        handedBinder = binder
    }

    /** One bounded binder transaction against the module's command endpoint. */
    private fun transactBinder(binder: IBinder, method: String, extras: Bundle): Bundle? {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            // The module performs accessibility work synchronously; never block
            // the host main thread on it.
            Log.w("module link skipped on the main thread method=$method")
            return null
        }
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(AutomationCommandBinder.DESCRIPTOR)
            data.writeString(method)
            data.writeBundle(extras)
            if (!binder.transact(AutomationCommandBinder.TRANSACTION_CALL, data, reply, 0)) {
                Log.w("module link transact rejected method=$method")
                null
            } else {
                reply.readException()
                runCatching {
                    reply.readBundle(AutomationBridge::class.java.classLoader)
                }.getOrNull()
            }
        } catch (throwable: Throwable) {
            Log.w(
                "module link call failed method=$method " +
                    "${throwable.javaClass.simpleName}: ${throwable.message}",
            )
            null
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    /** Says out loud which transport the host is actually using. */
    private fun noteTransport(current: String) {
        if (current == lastTransport) return
        lastTransport = current
        when (current) {
            "none" -> Log.w(
                "bridge transport=none: host cannot reach the module " +
                    "(provider and bound service both failed) → in-host channels only",
            )
            else -> Log.w("bridge transport=$current")
        }
    }

    private fun providerCall(context: Context, method: String, extras: Bundle): Bundle? {
        // The authority only exists in the user the module is installed in, and a
        // host that cannot see the module package cannot resolve it at all. In a
        // cloned ("双开") host every single call throws "Unknown authority", and
        // retrying it per poll produced one stack trace after another until the
        // logcat ring buffer held nothing else. Fail fast, explain once.
        if (SystemClock.uptimeMillis() < providerBlockedUntil) return null
        var lastError: Throwable? = null
        for (attempt in 1..MAX_PROVIDER_ATTEMPTS) {
            try {
                val result = context.contentResolver.call(
                    YuanbaoConfigProvider.URI,
                    method,
                    null,
                    extras,
                )
                if (result != null) {
                    if (providerBlockedUntil > 0L) providerBlockedUntil = 0L
                    return result
                }
                Log.d(
                    "provider call returned null method=$method attempt=$attempt " +
                        "caller=${context.packageName}",
                )
            } catch (throwable: Throwable) {
                lastError = throwable
            }
            if (attempt < MAX_PROVIDER_ATTEMPTS) {
                SystemClock.sleep(attempt * 80L)
            }
        }
        providerBlockedUntil = SystemClock.uptimeMillis() + PROVIDER_BACKOFF_MS
        Log.w(
            "provider unreachable in this user " +
                "(${lastError?.javaClass?.simpleName ?: "null"}: ${lastError?.message ?: "no result"}) " +
                "authority=${YuanbaoConfigProvider.URI.authority} caller=${context.packageName} " +
                "→ binding ${SERVICE_COMPONENT.flattenToShortString()} instead",
        )
        return null
    }

    /**
     * Bounded binder transport over an explicit component.
     *
     * Explicit-component binding is neither package-visibility filtered nor
     * blocked by the module app being in the stopped state, so this reaches the
     * accessibility service (and the module's root channel) in exactly the cases
     * where the exported provider answers "Unknown authority".
     */
    private fun serviceCall(context: Context, method: String, extras: Bundle): Bundle? {
        if (SystemClock.uptimeMillis() < serviceBlockedUntil) return null
        if (Looper.myLooper() == Looper.getMainLooper()) {
            // bindService completes on the main thread; blocking it here would
            // deadlock the host.
            return null
        }
        val binder = ensureBound(context)
        if (binder == null) {
            serviceBlockedUntil = SystemClock.uptimeMillis() + SERVICE_BACKOFF_MS
            Log.w(
                "bind ${SERVICE_COMPONENT.flattenToShortString()} failed; " +
                    "in-host channels only for ${SERVICE_BACKOFF_MS / 1_000}s",
            )
            return null
        }
        transactBinder(binder, method, extras)?.let { return it }
        serviceBlockedUntil = SystemClock.uptimeMillis() + SERVICE_BACKOFF_MS
        return null
    }

    private fun ensureBound(context: Context): IBinder? {
        boundService?.let { if (it.isBinderAlive) return it else boundService = null }
        synchronized(bindLock) {
            boundService?.let { if (it.isBinderAlive) return it }
            val latch = CountDownLatch(1)
            val connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                    boundService = service
                    latch.countDown()
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    boundService = null
                }
            }
            val bound = runCatching {
                context.bindService(
                    Intent().setComponent(SERVICE_COMPONENT),
                    connection,
                    Context.BIND_AUTO_CREATE,
                )
            }.getOrDefault(false)
            if (!bound) return null
            serviceConnection = connection
            latch.await(BIND_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            return boundService
        }
    }

    private fun Bundle?.ok(): Boolean = this?.getBoolean(KEY_OK, false) == true

    /** Last platform UI dump, reused while it is still fresh. */
    @Volatile
    private var dumpAt = 0L

    @Volatile
    private var dumpNodes: List<ScreenScanner.DumpNode> = emptyList()

    @Volatile
    private var dumpBlockedUntil = 0L

    @Volatile
    private var providerBlockedUntil = 0L

    @Volatile
    private var serviceBlockedUntil = 0L

    @Volatile
    private var lastTransport = ""

    @Volatile
    private var boundService: IBinder? = null

    @Volatile
    private var handedBinder: IBinder? = null

    @Volatile
    private var serviceConnection: ServiceConnection? = null

    private val bindLock = Any()

    private const val DUMP_TTL_MS = 1_000L
    private const val DUMP_RETRY_BACKOFF_MS = 5_000L
    private const val PROVIDER_BACKOFF_MS = 30_000L
    private const val SERVICE_BACKOFF_MS = 30_000L
    private const val BIND_TIMEOUT_MS = 3_000L
    private const val KEY_EXIT = "exit"

    private val SERVICE_COMPONENT = ComponentName(
        BuildConfig.APPLICATION_ID,
        "me.codex.yuanbaodaily.automation.AutomationCommandService",
    )

    /** 见 [AutomationCommandDispatcher.METHOD_UI_DUMP]：dump 必须由服务进程代跑。 */
    private const val DUMP_METHOD = AutomationCommandDispatcher.METHOD_UI_DUMP

    /** 屏幕尺寸缓存时长。 */
    private const val SCREEN_SIZE_CACHE_MS = 5_000L

    /** 见 [AutomationCommandDispatcher.METHOD_A11Y_REPAIR]：强制重绑无障碍服务（自愈）。 */
    private const val A11Y_REPAIR_METHOD = AutomationCommandDispatcher.METHOD_A11Y_REPAIR

    private val PICKER_TEXT_MARKERS = listOf(
        "本地相册",
        "ip角色",
        "最近项目",
        "相机胶卷",
        "选择照片",
        "所有照片",
        "选择项目",
    )

    private val CONFIRM_TEXT = listOf(
        "选择",
        "选择此项",
        "选取",
        "完成",
        "添加",
        "确定",
        "下一步",
        "使用照片",
        "使用此照片",
        "done",
        "use photo",
    )

    private val CONFIRM_ID_HINTS = listOf(
        "action_preview_selected",
        "ps_tv_complete",
        "ps_complete_select",
        "button_add",
        "button_confirm",
        "confirm_button",
        "menu_done",
    )
}
