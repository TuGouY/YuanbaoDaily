package me.codex.yuanbaodaily.automation

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Point
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log as AndroidLog
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class AutomationAccessibilityService : AccessibilityService() {
    private val main = Handler(Looper.getMainLooper())

    /** Background thread that keeps offering the command binder to the host. */
    @Volatile
    private var linkPump: Thread? = null

    /** Last window that really showed content, tracked from accessibility events. */
    @Volatile
    private var lastActivePackage: String = ""

    @Volatile
    private var lastActiveActivity: String = ""

    /** 最近一次 `composerBar` 拍平出来的节点快照，仅用于排查日志。 */
    @Volatile
    private var prevCandidates: List<ScreenScanner.DumpNode> = emptyList()

    /** OCR 引擎（懒加载：只有真正用到时才初始化，正常路径不付这个代价）。 */
    private val ocrEngine: OcrEngine by lazy { MlKitOcrEngine() }

    /** 上次 OCR 时间（uptimeMillis），用于冷却判定（诊断用，冷却本身在调用方）。 */
    @Volatile
    private var lastOcrAtMs = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        AndroidLog.d(TAG, "accessibility service connected")
        // The host cannot look this package up (package visibility) and it also
        // loses the binder whenever its process restarts, so offer it again and
        // again while the service is alive.
        runCatching { AutomationHostLink.publishOnce(applicationContext, "service connected") }
            .onFailure { AndroidLog.w(TAG, "module link publish failed", it) }
        startLinkPump()
        // 免 root 版没有"注入进元宝的运行时"，调度器必须在自己的进程里起。
        // 无障碍服务连上就是最可靠的信号：没有它，任何自动化都干不了活。
        runCatching { me.codex.yuanbaodaily.task.StandaloneRuntime.ensureStarted(applicationContext) }
            .onFailure { AndroidLog.w(TAG, "standalone runtime start failed", it) }
    }

    private fun startLinkPump() {
        if (linkPump != null) return
        val appContext = applicationContext
        linkPump = Thread({
            while (!Thread.currentThread().isInterrupted) {
                SystemClock.sleep(LINK_PUMP_INTERVAL_MS)
                runCatching { AutomationHostLink.publishOnce(appContext, "pump") }
            }
        }, "YuanbaoDailyLinkPump").apply {
            isDaemon = true
            start()
        }
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        AndroidLog.d(TAG, "accessibility service destroyed")
        linkPump?.interrupt()
        linkPump = null
        super.onDestroy()
    }

    /**
     * Remembers the window that is really in front.
     *
     * Only `rootInActiveWindow` used to be read. While an input method window is
     * in front that root is the IME, which the package policy rejects, so every
     * text click reported "no target" even though the host page was right there.
     * Tracking window-state events keeps a usable answer for that case.
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val current = event ?: return
        val packageName = current.packageName?.toString().orEmpty()
        if (packageName.isNotBlank() && !isTransientWindowPackage(packageName)) {
            lastActivePackage = packageName
        }
        if (current.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val className = current.className?.toString().orEmpty()
            if (className.isNotBlank()) lastActiveActivity = className
        }
    }

    override fun onInterrupt() = Unit

    private fun execute(input: Bundle): Bundle {
        val action = input.getString(KEY_ACTION).orEmpty()
        currentAction = action
        if (action == ACTION_STATUS) return result(true)
        // **屏幕真实尺寸**：给"右侧/左半屏"这类判据用。
        //
        // 以前这些判据是写死的绝对像素（`RIGHT_HALF_X = 1100`），在开发机
        // （1272 宽）上正确，换到 1080 宽的手机上就**永远不成立** ——
        // 侧栏明明开了，判据说没开，于是反复开/关最后乱点。
        // 这里直接回`getRealSize()`（含导航栏），坐标空间和节点 bounds 一致。
        if (action == ACTION_SCREEN_SIZE) {
            val (width, height) = displaySize()
            return Bundle().apply {
                putBoolean(KEY_OK, width > 0 && height > 0)
                putInt(KEY_SCREEN_WIDTH, width)
                putInt(KEY_SCREEN_HEIGHT, height)
            }
        }
        // **OCR 兜底**：只有调用方判定"无障碍一个字都没读到"时才会走到这里。
        // 这条分支不做任何界面操作，只截图 + 识别 + 回结果。
        if (action == ACTION_OCR_TEXT) return ocrResult()
        // **厂商安全提示**：无障碍重绑后 ROM 会弹全屏"是否保留无障碍权限"，
        // 默认主按钮是「关闭无障碍」。这条分支只点**精确的保留文案**，
        // 见 [SystemGuardPolicy] —— 它宁可失效也绝不误点。
        if (action == ACTION_DISMISS_GUARD) return dismissGuardResult()
        // 1.1.4 起 dump **不再跑 uiautomator**（见 [dumpResult]）：直接序列化无障碍树，
        // 毫秒级完成，且不会把模块自己的无障碍服务踢下线。因此可以安全地走 onMain。
        if (action == ACTION_DUMP) return dumpResult()
        // ⓪ **语音模式自愈**（真机实测的"永远找不到输入框"就是这个）。
        //
        // 元宝的输入栏会被切成"按住 说话"（语音模式），此时树里**没有任何 EditText**：
        // 写文案、聚焦、发送、量输入栏几何 —— 每一条都会失败并打 "no editor"。
        // 更糟的是这个状态**重启 App 也不会恢复**（实测：force-stop 后重开仍是
        // "按住 说话" + "切换键盘"），所以一次误切就把模块永久卡死。
        //
        // 恢复逻辑 [ensureKeyboardMode] 原先只挂在 `tapEditorCenter` 一条通道上，
        // 其余通道遇到语音模式只能干等。这里统一在**入口处**自愈一次，让所有
        // 输入栏相关动作都具备恢复能力。
        if (action in COMPOSER_ACTIONS) {
            val switched = withAllRoots { roots -> ensureKeyboardMode(roots) }
            if (switched) {
                AndroidLog.w(TAG, "输入栏处于语音模式 → 已自动切回键盘 action=$action")
            }
        }
        return onMain(onMainTimeout()) {
            when (action) {
                ACTION_READ_TEXT -> readTextResult()
                ACTION_CLICK_TEXT -> withRoots { roots ->
                    result(
                        clickText(
                            roots = roots,
                            aliases = input.getStringArrayList(KEY_ALIASES).orEmpty(),
                            allowContains = input.getBoolean(KEY_ALLOW_CONTAINS, false),
                        ),
                    )
                }
                ACTION_CLICK_TEXT_NEXT -> withRoots { roots ->
                    clickTextNextResult(
                        roots = roots,
                        aliases = input.getStringArrayList(KEY_ALIASES).orEmpty(),
                        allowContains = input.getBoolean(KEY_ALLOW_CONTAINS, false),
                        blockedKeys = input.getStringArrayList(KEY_BLOCKED_KEYS).orEmpty().toSet(),
                    )
                }
                ACTION_SET_TEXT -> withRoots { roots ->
                    result(setText(roots, input.getString(KEY_VALUE).orEmpty()))
                }
                ACTION_CLICK_ID -> withRoots { roots ->
                    result(clickIds(roots, input.getStringArrayList(KEY_IDS).orEmpty()))
                }
                ACTION_CLICK_LATEST_MEDIA -> withRoots { roots ->
                    clickLatestMedia(
                        roots = roots,
                        marker = input.getString(KEY_MARKER).orEmpty(),
                        blockedKeys = input.getStringArrayList(KEY_BLOCKED_KEYS).orEmpty().toSet(),
                    )
                }
                ACTION_TAP -> withRoots { roots ->
                    result(
                        tapRelative(
                            root = roots.firstOrNull(),
                            xFraction = input.getFloat(KEY_X_FRACTION, 0.5f),
                            yFraction = input.getFloat(KEY_Y_FRACTION, 0.5f),
                        ),
                    )
                }
                // Compose 页面里常见"没有文字/ID 的可点区域"（例如模板详情页右下角的生成键）：
                // 按坐标找最深的可点节点直接 ACTION_CLICK，比裸手势可靠。
                ACTION_CLICK_POINT -> withRoots { roots ->
                    result(
                        clickPointAt(
                            roots = roots,
                            xFraction = input.getFloat(KEY_X_FRACTION, 0.5f),
                            yFraction = input.getFloat(KEY_Y_FRACTION, 0.5f),
                        ),
                    )
                }
                // Compose 页面的发送键：与输入框同排、在输入框右侧、没有文字/ID。
                ACTION_CLICK_NEAR_EDITOR -> withRoots { roots ->
                    result(clickButtonNearEditor(roots))
                }
                // 把输入框点开/聚焦：之后宿主才能拿到输入连接，用"追加"方式写文案。
                ACTION_FOCUS_EDITOR -> withRoots { roots -> result(focusEditor(roots)) }
                // 点窗口底部最靠下的可点按钮（选择器的“确定/完成”常无文字无 ID）。
                ACTION_CLICK_BOTTOM -> withRoots { roots -> result(clickBottomButton(roots)) }
                // 把剪贴板内容**粘贴**进输入框：插入而不清空，能保留已附加的图片。
                ACTION_PASTE_EDITOR -> withRoots { roots -> result(pasteIntoEditor(roots)) }
                // 把输入框清空（`ACTION_SET_TEXT` 空串）。用于发出去之前清掉**残留文案**：
                // 上一轮"写成功但没发出去"时，新文案会被**追加**到残留后面，
                // 两段提示词拼成一条发出去（真机 `evidence/trace_d5_ask` 实测）。
                ACTION_CLEAR_COMPOSER -> withRoots { roots ->
                    result(setText(roots, "", allowEmpty = true))
                }
                // 取某个文字节点的**真实屏幕坐标**（换版本后底部导航位置会变，不能写死坐标）。
                ACTION_NODE_BOUNDS -> withAllRoots { roots ->
                    val label = input.getString(KEY_TEXT).orEmpty()
                    val bottomOnly = input.getBoolean(KEY_BOTTOM_ONLY, false)
                    val center = findNodeCenter(roots, label, bottomOnly)
                    if (center == null) {
                        AndroidLog.d(TAG, "nodeBounds not found label=$label bottomOnly=$bottomOnly")
                        result(false)
                    } else {
                        AndroidLog.d(TAG, "nodeBounds label=$label xy=${center[0]},${center[1]}")
                        result(true, center[0], center[1])
                    }
                }
                // 按文字或 content-desc 找节点，用它自己的**真实 bounds 中心**点一下。
                // 福利中心入口只有 content-desc 且内层不可点，必须走这条通道。
                ACTION_TAP_NODE_BY_LABEL -> withAllRoots { roots ->
                    val label = input.getString(KEY_TEXT).orEmpty()
                    result(tapNodeByLabel(roots, label))
                }
                // 慢速水平拖动（拖底栏露出"全部任务"圆图标）。
                ACTION_DRAG_HORIZONTAL -> result(
                    dispatchHorizontalDrag(
                        input.getFloat(KEY_FROM_FRACTION, 0.85f),
                        input.getFloat(KEY_TO_FRACTION, 0.60f),
                        input.getFloat(KEY_Y_FRACTION, 0.838f),
                    ),
                )
                // 点"底栏最右侧的小圆图标"（= 全部任务菜单按钮，位置随底栏滚动而变）。
                ACTION_TAP_BOTTOM_RIGHT_ICON -> withAllRoots { roots -> result(tapBottomRightIcon(roots)) }
                // 取"屏幕底部输入框"所在行的纵向比例（用于识别对话页并定位发送 ⬆，不需要 root）。
                // 直接点"对话页发送 ⬆"：按**绝对像素**（输入框右侧 + 下方 50px，用户截图实测）。
                // 不走比例换算 —— 模块的屏幕高度(2603)与触摸坐标空间(2800)不一致，换算必偏。
                // **纯节点驱动：点输入框自己的中心**（不用任何比例/估算坐标）。
                //
                // 1.1.1 修正：原先这里在 `editor == null` 时按写死的 (0.445, 0.976) 盲点，
                // 但实测输入框会随键盘弹起/抽屉展开上下移动（同一页见过 y=1438 与 y=2435 两种），
                // 写死坐标必然落在输入框外 —— 空输入框时正好踩到 `+` / 切换语音，这就是"误触"。
                // 现在解析不出安全落点就**直接放弃**（返回 false），绝不做坐标兜底。
                ACTION_TAP_EDITOR_CENTER -> withAllRoots { roots ->
                    // ⓪ 先确认不是"语音模式"：语音模式下输入框是"按住 说话"，没有 EditText，
                    //    直接去找输入框必然失败（旧代码就在这里盲点写死坐标 → 误触切换语音键）。
                    if (ensureKeyboardMode(roots)) {
                        AndroidLog.d(TAG, "tapEditorCenter 已从语音模式切回键盘，重新解析")
                    }
                    val bar = composerBar(roots)
                    if (bar == null) {
                        AndroidLog.w(TAG, "tapEditorCenter 解析不出输入栏 → 放弃（不再盲点写死坐标）")
                        return@withAllRoots result(false)
                    }
                    val point = bar.editorFocusPoint()
                    if (point == null) {
                        AndroidLog.w(
                            TAG,
                            "tapEditorCenter 输入框内找不到安全落点 editor=${bar.editor.toShortString()} " +
                                "overlays=${bar.overlays.size} → 放弃",
                        )
                        return@withAllRoots result(false)
                    }
                    val (x, y) = point
                    if (!ComposerGeometry.isSafeFocusPoint(bar, x, y)) {
                        AndroidLog.w(TAG, "tapEditorCenter 落点未通过安全校验 xy=$x,$y → 放弃")
                        return@withAllRoots result(false)
                    }
                    val ok = tapAt(x.toFloat(), y.toFloat())
                    AndroidLog.d(
                        TAG,
                        "tapEditorCenter 按真实几何点输入框 xy=$x,$y " +
                            "editor=${bar.editor.toShortString()} ok=$ok",
                    )
                    result(ok)
                }
                // **写作页/对话页发送已统一走 ACTION_TAP_SEND_SAFE（真实几何）**：
                // 原先这里按"屏宽-172 / 屏高×0.997"、"屏宽-159 / 屏高×0.973"写死像素，
                // 实测在 1272x2800 上 y 偏低 79~192px，会点空或误触输入栏外的控件，故删除。
                ACTION_TAP_CHAT_SEND, ACTION_TAP_WRITING_SEND -> withAllRoots { roots ->
                    AndroidLog.w(TAG, "action=$action 已废弃（写死像素坐标偏低）→ 改用真实几何")
                    result(tapSendByGeometry(roots))
                }
                // **结构性找发送键**：与输入框同一行、位于输入框右侧的那个可点节点（不猜坐标）。
                ACTION_CLICK_SEND_RIGHT_OF_EDITOR -> withAllRoots { roots ->
                    val editors = mutableListOf<AccessibilityNodeInfo>()
                    roots.forEach { collectEditableByClassRelaxed(it, editors) }
                    val editor = editors.maxByOrNull { Rect().also(it::getBoundsInScreen).height() }
                    if (editor == null) {
                        editors.forEach { it.recycleCompat() }
                        AndroidLog.d(TAG, "sendRightOfEditor no editor")
                        result(false)
                    } else {
                        val editorBounds = Rect().also(editor::getBoundsInScreen)
                        // **结构闸**：空文案时右侧那颗容器是"切换语音"，点下去会把页面
                        // 切进语音模式、消息永远发不出（用户报的误触）。先判定再动手。
                        val editorBox = Bounds(
                            editorBounds.left,
                            editorBounds.top,
                            editorBounds.right,
                            editorBounds.bottom,
                        )
                        val flat = flatNodes(roots)
                        val scale = screenScale()
                        val guard = ComposerSendGuard.decide(
                            nodes = flat,
                            editor = editorBox,
                            hostEditorText = composerTextVerdict(flat),
                            // 以前这里硬传 null，于是"确认有文案但没有发送节点"时也被判成
                            // 证据不足 → 永远拒点（真机日志：`hostText=true` 却仍然拒点）。
                            // 现在把无障碍树解析出的真实动作点喂进去。
                            geometryPoint = composerBar(roots)?.let { it.actionX to it.actionY },
                            screenWidth = displaySize().first,
                            scale = scale,
                        )
                        if (!guard.allowed) {
                            editors.forEach { it.recycleCompat() }
                            AndroidLog.w(TAG, "sendRightOfEditor 拒点：${guard.reason}")
                            return@withAllRoots result(false)
                        }
                        if (guard.verdict == ComposerSendGuard.Verdict.SEND_BY_NODE) {
                            editors.forEach { it.recycleCompat() }
                            AndroidLog.d(
                                TAG,
                                "sendRightOfEditor 点发送节点 xy=${guard.x},${guard.y} (${guard.reason})",
                            )
                            return@withAllRoots result(tapAt(guard.x.toFloat(), guard.y.toFloat()))
                        }
                        // 1.1.4 删除：原先这里有一条"输入框展开成全屏编辑面板"的兜底 ——
                        //     `x = 屏宽 - 136`、`y = 输入框底部 - 105`，**全是写死坐标**。
                        //     真机 `evidence/trace_d5_ask` 证明它是被**误触发**的：
                        //     元宝在问答结果页渲染了一颗高 2016px 的可编辑文本节点
                        //     `[70,588][1202,2604]`，旧的选择逻辑（取最高的 EditText）
                        //     把它当成了输入栏 → `height > 400` 成立 → 打出一串写死坐标的点击，
                        //     而这些点击没有一次是真的发送 ✗（captures 004~007 全是同一坐标）。
                        //     现在：输入栏改成"最靠下的 EditText"（见 ComposerGeometry.resolve），
                        //     这里彻底删掉坐标兜底 —— 解析不出发送节点就**放弃**，交给上层。
                        val clickables = mutableListOf<AccessibilityNodeInfo>()
                        roots.forEach { collectClickableNodesRelaxed(it, clickables) }
                        // 放宽：能力页的 ⬆ 可能**不是 clickable 节点**（实测写作页 candidate=null ✗），
                        // 所以再收集一遍"任何有屏幕区域的节点"，同行取最右者，直接真实触摸。
                        val anyNodes = mutableListOf<AccessibilityNodeInfo>()
                        roots.forEach { collectAllNodesRelaxed(it, anyNodes) }
                        val pool = clickables + anyNodes
                        val candidate = pool
                            .map { it to Rect().also { rect -> it.getBoundsInScreen(rect) } }
                            // **必须是无文字、无描述的节点**（1.1.6 新增）。
                            //
                            // 真机 `evidence/trace_e4_ask` 实证：这一带的干扰节点都是**有文字/描述**的
                            // —— `全屏输入`（70×70，desc='全屏输入'）、`AI创作`、`更多`、`切换语音`、
                            // `收起`。它们尺寸/位置全都落在筛选框内，而排序用的是
                            // `maxByOrNull { centerX }`，于是 `全屏输入`（centerX=1139）
                            // **压过了真正的发送键** `[1027,2456][1195,2624]`（centerX=1111）→
                            // `sendRightOfEditor` 去点"全屏输入"，消息当然发不出去。
                            //
                            // 真正的发送 ⬆ 在所有真机 dump 里都是 `text='' desc=''`（它是一颗
                            // 纯图标 Compose 按钮），所以"标签必须为空"是一条可靠的判别条件。
                            .filter { (node, _) ->
                                node.text.isNullOrBlank() && node.contentDescription.isNullOrBlank()
                            }
                            .filter { (_, bounds) ->
                                // 尺寸/间距全部按当前屏幕换算：40..320 在 720 宽机型上
                                // 会把真正的发送键（约 100px）放过、却把 400px 的整行容器收进来。
                                val minSide = screenScale().controlMinSide()
                                val maxSide = screenScale().widthScaled(SEND_NEAR_EDITOR_MAX_SIDE_REF)
                                bounds.width() in minSide..maxSide && bounds.height() in minSide..maxSide &&
                                    bounds.top >= editorBounds.top - screenScale().dpScaled(10) &&
                                    bounds.bottom <= editorBounds.bottom + screenScale().dpScaled(10) &&
                                    bounds.left >= editorBounds.right - screenScale().dpScaled(20)
                            }
                            .maxByOrNull { (_, bounds) -> bounds.centerX() }
                        val clicked = clickNode("sendRightOfEditor", candidate?.first, candidate?.second)
                        val bounds = candidate?.second
                        AndroidLog.d(
                            TAG,
                            "sendRightOfEditor editor=$editorBounds candidate=$bounds clicked=$clicked " +
                                "clickables=${clickables.size}",
                        )
                        clickables.forEach { it.recycleCompat() }
                        editors.forEach { it.recycleCompat() }
                        // 元宝的发送键是 Compose 按钮：`ACTION_CLICK` 不生效 ✗，
                        // 必须在该节点中心派发**真实触摸**（用户建议：点一下再截图核对 ✓）。
                        if (bounds != null) {
                            val touch = tapAt(bounds.centerX().toFloat(), bounds.centerY().toFloat())
                            AndroidLog.d(TAG, "sendRightOfEditor touch at ${bounds.centerX()},${bounds.centerY()} ok=$touch")
                            result(touch)
                        } else {
                            result(false)
                        }
                    }
                }
                // **按文字找节点 → 派发真实触摸**（Compose 的列表项不吃 a11y click ✗）。
                ACTION_TAP_TEXT -> withAllRoots { roots ->
                    val label = input.getString(KEY_TEXT).orEmpty()
                    val matches = mutableListOf<Rect>()
                    roots.forEach { collectTextBounds(it, label, matches) }
                    val best = matches.minByOrNull { it.width().toLong() * it.height() }
                    if (best == null) {
                        AndroidLog.d(TAG, "tapText not found label=$label")
                        result(false)
                    } else {
                        val ok = tapAt(best.centerX().toFloat(), best.centerY().toFloat())
                        AndroidLog.d(TAG, "tapText label=$label rect=$best tap=${best.centerX()},${best.centerY()} ok=$ok")
                        result(ok)
                    }
                }
                ACTION_BOTTOM_EDITOR_ROW -> withAllRoots { roots ->
                    // 元宝的输入框上报 `isVisibleToUser=false`（与底部导航条同一个坑），
                    // 所以只按**类名 + 屏幕区域**判定，不依赖可见性/可编辑性。
                    val editors = mutableListOf<AccessibilityNodeInfo>()
                    roots.forEach { collectEditableByClassRelaxed(it, editors) }
                    val editor = editors.maxByOrNull { Rect().also(it::getBoundsInScreen).height() }
                    if (editor == null) {
                        AndroidLog.d(TAG, "bottomEditorRow no editor windows=${windowPackages()}")
                        result(false)
                    } else {
                        val bounds = Rect().also(editor::getBoundsInScreen)
                        // 同上：只批量回收一次（editor 已在 editors 中）。
                        editors.forEach { it.recycleCompat() }
                        // **基准必须是全屏高度**（`getRealSize()`），不是 `displayMetrics`。
                        // 两者差一个导航栏（实测 2800 vs 2603，约 7%），而节点 bounds 与
                        // 手势坐标都在全屏坐标系里。用可用窗口当分母，回传的比例整体偏大，
                        // 适配层再乘全屏高度就会点到底部导航/桌面图标上 —— 换到三键导航
                        // 的机型（导航栏更高）偏移更大。这跟 `clickPointAt` 是同一类坑。
                        val height = displaySize().second.toFloat()
                        val row = if (height > 0) bounds.centerY() / height else 0f
                        AndroidLog.d(TAG, "bottomEditorRow row=$row bounds=$bounds height=$height")
                        // 把比例按 10000 放大后回传：适配层用**同一个（全屏）高度基准**还原。
                        // 不再要求 row>0.75：键盘弹出后输入框会上移到 0.61 一带（对话页判定交给适配层）。
                        result(true, 0, (row * 10_000f).toInt())
                    }
                }
                ACTION_SCROLL_FORWARD -> withRoots { roots -> result(scroll(roots, forward = true)) }
                // 点底部导航第 index 个（按屏幕底部一排可点节点从左到右排序）。
                ACTION_TAB_TAP -> withAllRoots { roots ->
                    val index = input.getInt(KEY_INDEX, 0)
                    // 期望标签：给了就先核对再点（见 tapBottomTab 的说明）。
                    val hint = input.getString(KEY_TEXT).orEmpty()
                    result(tapBottomTab(roots, index, hint))
                }
                // **按真实几何点发送键**：只用无障碍树实测出的输入栏 bounds，
                // 找不到/不安全就返回 false（宁可不点，也不点错）。
                ACTION_TAP_SEND_SAFE -> withAllRoots { roots ->
                    result(tapSendByGeometry(roots))
                }
                // 调试节拍：本服务跑在**模块进程**，与注入的宿主进程各自持有一份
                // TapTrace 实例。宿主侧 configure 只影响它自己那一份，所以必须由
                // 宿主显式把节拍同步过来，否则无障碍层（tapAt / 节点点击）永远是盲区
                // —— 实测踩过：底栏那次把人带崩的错误点击在日志里完全没留下痕迹。
                ACTION_SET_TAP_PACE -> {
                    TapTrace.configure(input.getLong(KEY_TAP_PACE_MS, 0L))
                    result(true)
                }
                // 输入框文案状态：宿主读不到 Compose 输入框，由服务进程从无障碍树给出。
                ACTION_COMPOSER_STATE -> withAllRoots { roots ->
                    composerStateResult(roots)
                }
                // 取当前输入栏的真实几何（输入框/发送键中心），供宿主侧决策使用。
                ACTION_COMPOSER_GEOMETRY -> withAllRoots { roots ->
                    composerGeometryResult(roots)
                }
                ACTION_SCROLL_BACKWARD -> withRoots { roots -> result(scroll(roots, forward = false)) }
                ACTION_BACK -> result(performGlobalAction(GLOBAL_ACTION_BACK))
                else -> result(false)
            }
        } ?: result(false)
    }

    /**
     * Roots of every window this service may act on, the active window first.
     *
     * The album picker, vendor galleries and the input method live in their own
     * windows. Looking at `rootInActiveWindow` alone missed the photo grid
     * whenever the picker was not the window the framework called active, and
     * dropped text clicks entirely while the keyboard was up.
     */
    private fun candidateRoots(): List<AccessibilityNodeInfo> {
        val roots = mutableListOf<AccessibilityNodeInfo>()
        val seen = mutableSetOf<String>()
        for (window in runCatching { windows }.getOrNull().orEmpty()) {
            val root = runCatching { window.root }.getOrNull() ?: continue
            if (!rememberRoot(roots, seen, root)) root.recycleCompat()
        }
        val active = runCatching { rootInActiveWindow }.getOrNull()
        if (active != null) {
            val packageName = active.packageName?.toString().orEmpty()
            if (packageName.isNotBlank() &&
                AccessibilityPackagePolicy.isAllowed(packageName) &&
                seen.add(rootKey(active))
            ) {
                roots.add(0, active)
            } else {
                active.recycleCompat()
            }
        }
        return roots
    }

    private fun rememberRoot(
        roots: MutableList<AccessibilityNodeInfo>,
        seen: MutableSet<String>,
        root: AccessibilityNodeInfo,
    ): Boolean {
        val packageName = root.packageName?.toString().orEmpty()
        if (packageName.isBlank() || !AccessibilityPackagePolicy.isAllowed(packageName)) return false
        if (!seen.add(rootKey(root))) return false
        roots += root
        return true
    }

    private fun <T> sequenceOfNotNull(vararg values: T?): Sequence<T> = values.asSequence().filterNotNull()

    private fun rootKey(root: AccessibilityNodeInfo): String =
        "${root.packageName}|${root.windowId}"

    /** Runs the action on every allowed window and always releases the roots. */
    private fun <T> withRoots(block: (List<AccessibilityNodeInfo>) -> T): T {
        val roots = candidateRoots()
        if (roots.isEmpty()) {
            AndroidLog.d(TAG, "no allowed accessibility window windows=${windowPackages()}")
        }
        return try {
            block(roots)
        } finally {
            roots.forEach { it.recycleCompat() }
        }
    }

    /**
     * 所有窗口的根，**只保留允许的包**。
     *
     * 早期版本这里刻意"不做包名过滤"，因为 2.87.20 的底部导航落在策略不认的窗口里。
     * 但那等于把**任何前台 App 的节点**都收进候选池：一旦模块的坐标点击跑偏（比例坐标
     * 与真实布局不一致时必然跑偏），就会点到别的应用上 —— 这正是用户反馈的"误触其他按键"。
     *
     * 现在的取舍：过滤收紧到"元宝 + 媒体/相机选择器"这类已知且必要的窗口，
     * 拿不到导航节点时**宁可判定失败**，也不跨应用乱点。
     */
    private fun allRoots(): List<AccessibilityNodeInfo> {
        val roots = mutableListOf<AccessibilityNodeInfo>()
        val seen = mutableSetOf<String>()
        for (window in runCatching { windows }.getOrNull().orEmpty()) {
            val root = runCatching { window.root }.getOrNull() ?: continue
            val packageName = root.packageName?.toString().orEmpty()
            if (!AccessibilityPackagePolicy.isAllowed(packageName) &&
                !AccessibilityPackagePolicy.isMediaPackage(packageName)
            ) {
                AndroidLog.d(TAG, "allRoots 跳过非目标窗口 package=$packageName")
                root.recycleCompat()
                continue
            }
            val key = "$packageName|${window.id}"
            if (!seen.add(key)) {
                root.recycleCompat()
                continue
            }
            roots += root
        }
        return roots
    }

    private fun <T> withAllRoots(block: (List<AccessibilityNodeInfo>) -> T): T {
        val roots = allRoots()
        AndroidLog.d(TAG, "allRoots count=${roots.size} windows=${windowPackages()}")
        return try {
            block(roots)
        } finally {
            roots.forEach { it.recycleCompat() }
        }
    }

    /** Package names of every window the framework currently reports. */
    private fun windowPackages(): String = runCatching {
        windows.joinToString(",") { window ->
            val root = runCatching { window.root }.getOrNull()
            val name = root?.packageName?.toString().orEmpty()
            root?.recycleCompat()
            "${window.id}:${name.ifBlank { "?" }}"
        }
    }.getOrDefault("unavailable")

    private fun isTransientWindowPackage(packageName: String): Boolean {
        val normalized = packageName.lowercase()
        return normalized == "android" ||
            normalized == "com.android.systemui" ||
            normalized.contains("inputmethod") ||
            normalized.endsWith(".input") ||
            normalized.contains(".ime") ||
            normalized.contains("keyboard")
    }

    /**
     * Package of the page that is really in front.
     *
     * Input method and system windows are skipped so a keyboard never hides the
     * fact that the host activity is still the visible page.
     */
    private fun frontPackageName(roots: List<AccessibilityNodeInfo>): String {
        val active = runCatching { rootInActiveWindow }.getOrNull()
        val activePackage = active?.packageName?.toString().orEmpty()
        active?.recycleCompat()
        if (activePackage.isNotBlank() && !isTransientWindowPackage(activePackage)) {
            return activePackage
        }
        if (lastActivePackage.isNotBlank()) return lastActivePackage
        val allowed = roots.mapNotNull { it.packageName?.toString()?.takeIf(String::isNotBlank) }
        return allowed.firstOrNull { it == TARGET_PACKAGE } ?: allowed.firstOrNull().orEmpty()
    }

    private fun readTextResult(): Bundle {
        val roots = candidateRoots()
        return try {
            val text = StringBuilder()
            var confirmVisible = false
            var pickerVisible = false
            for (root in roots) {
                val rootText = buildString { collectText(root, this) }
                if (rootText.isNotBlank()) text.append(rootText).append('\n')
                if (hasMediaConfirmNode(root)) confirmVisible = true
                if (looksLikePicker(root, rootText)) pickerVisible = true
            }
            result(true).apply {
                putString(KEY_TEXT, text.toString().take(MAX_TEXT))
                putString(KEY_PACKAGE, frontPackageName(roots))
                putString(KEY_SIGNATURE, roots.firstOrNull()?.let(::nodeTreeSignature).orEmpty())
                putBoolean(KEY_CONFIRM_VISIBLE, confirmVisible)
                putBoolean(KEY_PICKER_VISIBLE, pickerVisible)
                putString(KEY_WINDOWS, windowPackages())
                putString(KEY_ACTIVITY, lastActiveActivity)
            }
        } finally {
            roots.forEach { it.recycleCompat() }
        }
    }

    /** True when this window is a photo picker rather than the host page. */
    private fun looksLikePicker(root: AccessibilityNodeInfo, rootText: String): Boolean {
        val packageName = root.packageName?.toString().orEmpty()
        if (AccessibilityPackagePolicy.isCameraPackage(packageName)) return false
        if (AccessibilityPackagePolicy.isMediaPackage(packageName)) return true
        val normalized = rootText.lowercase()
        return PICKER_WINDOW_MARKERS.any(normalized::contains)
    }

    private fun clickText(
        roots: List<AccessibilityNodeInfo>,
        aliases: List<String>,
        allowContains: Boolean = false,
    ): Boolean {
        val clean = aliases.map(String::trim).filter(String::isNotEmpty)
        if (clean.isEmpty()) return false
        for (root in roots) {
            if (clickTextNode(root, clean, exact = true)) return true
        }
        if (allowContains) {
            for (root in roots) {
                if (clickTextNode(root, clean, exact = false)) return true
            }
        }
        AndroidLog.d(TAG, "clickText no target aliases=$clean windows=${windowPackages()}")
        return false
    }

    private fun clickTextNode(
        node: AccessibilityNodeInfo,
        aliases: List<String>,
        exact: Boolean,
    ): Boolean {
        val text = nodeText(node)
        val matched = aliases.any { alias ->
            if (exact) text.equals(alias, ignoreCase = true)
            else text.contains(alias, ignoreCase = true)
        }
        if (
            node.isVisibleToUser && node.isEnabled && matched &&
            (clickNodeOrParent(node) || tapNodeCenter(node))
        ) return true
        for (index in 0 until node.childCount) {
            val child = runCatching { node.getChild(index) }.getOrNull() ?: continue
            try {
                if (clickTextNode(child, aliases, exact)) return true
            } finally {
                child.recycleCompat()
            }
        }
        return false
    }

    /**
     * Clicks the first matching text target whose card key is not blocked.
     * The key is based on the surrounding card text instead of absolute screen
     * coordinates, so it remains stable after the recommendation list scrolls.
     */
    private fun clickTextNextResult(
        roots: List<AccessibilityNodeInfo>,
        aliases: List<String>,
        allowContains: Boolean,
        blockedKeys: Set<String>,
    ): Bundle {
        val clean = aliases.map(String::trim).filter(String::isNotEmpty)
        if (clean.isEmpty()) return result(false)
        val candidates = mutableListOf<TextCandidate>()
        for (root in roots) collectTextCandidates(root, clean, allowContains, candidates)
        if (candidates.isEmpty()) {
            AndroidLog.d(TAG, "clickTextNext no target aliases=$clean windows=${windowPackages()}")
        }
        val ordered = candidates
            .distinctBy { "${it.key}|${it.bounds.left}|${it.bounds.top}|${it.bounds.right}|${it.bounds.bottom}" }
            .sortedWith(compareBy<TextCandidate> { it.bounds.top }.thenBy { it.bounds.left })
        // Compose can expose several identical "做同款" nodes when the card
        // title is not part of the accessibility subtree. Add a deterministic
        // ordinal so those nodes remain independently selectable.
        val keyCounts = mutableMapOf<String, Int>()
        val indexed = ordered.map { item ->
            val ordinal = keyCounts.getOrDefault(item.key, 0)
            keyCounts[item.key] = ordinal + 1
            item.copy(key = "${item.key}#$ordinal")
        }
        val candidate = indexed.firstOrNull { it.key !in blockedKeys }
        if (candidate == null) {
            indexed.forEach { it.node.recycleCompat() }
            return result(false)
        }
        val clicked = clickNodeOrParent(candidate.node) || tapNodeCenter(candidate.node)
        indexed.forEach { it.node.recycleCompat() }
        return result(clicked).apply {
            if (clicked) putString(KEY_SELECTED_KEY, candidate.key)
        }
    }

    private fun collectTextCandidates(
        node: AccessibilityNodeInfo,
        aliases: List<String>,
        allowContains: Boolean,
        output: MutableList<TextCandidate>,
    ) {
        if (node.isVisibleToUser && node.isEnabled) {
            val text = nodeText(node)
            val matched = aliases.any { alias ->
                text.equals(alias, ignoreCase = true) ||
                    allowContains && text.contains(alias, ignoreCase = true)
            }
            if (matched) {
                val bounds = Rect().also(node::getBoundsInScreen)
                if (bounds.width() > 0 && bounds.height() > 0) {
                    output += TextCandidate(
                        node = AccessibilityNodeInfo.obtain(node),
                        bounds = bounds,
                        key = textCandidateKey(node, bounds),
                    )
                }
            }
        }
        for (index in 0 until node.childCount) {
            val child = runCatching { node.getChild(index) }.getOrNull() ?: continue
            try {
                collectTextCandidates(child, aliases, allowContains, output)
            } finally {
                child.recycleCompat()
            }
        }
    }

    private fun textCandidateKey(node: AccessibilityNodeInfo, bounds: Rect): String {
        var best = nodeText(node)
        var ancestor = runCatching { node.parent }.getOrNull()
        repeat(6) {
            val current = ancestor ?: return@repeat
            val surrounding = subtreeText(current).trim()
            if (surrounding.length in (best.length + 1)..400) best = surrounding
            val next = runCatching { current.parent }.getOrNull()
            current.recycleCompat()
            ancestor = next
        }
        ancestor?.recycleCompat()
        val normalized = best.lowercase().replace(Regex("\\s+"), " ").trim()
        val id = node.viewIdResourceName.orEmpty()
        return "$id|$normalized|${bounds.width()}x${bounds.height()}"
    }

    private fun scroll(roots: List<AccessibilityNodeInfo>, forward: Boolean): Boolean {
        // **真实滑动优先**：WebView（福利中心）和 Compose 页面经常"ACTION_SCROLL_FORWARD
        // 返回成功但页面根本没动"，只有真正的手指滑动才可靠（用户实测反馈）。
        if (dispatchSwipe(forward)) return true

        val scrollables = mutableListOf<AccessibilityNodeInfo>()
        for (root in roots) collectScrollableNodes(root, scrollables)
        val action = if (forward) {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        } else {
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        }
        val target = scrollables
            .sortedByDescending { node ->
                val bounds = Rect().also(node::getBoundsInScreen)
                bounds.width().toLong() * bounds.height().toLong()
            }
            .firstOrNull()
        if (target == null) {
            scrollables.forEach { it.recycleCompat() }
            return false
        }
        val performed = runCatching { target.performAction(action) }.getOrDefault(false)
        scrollables.forEach { it.recycleCompat() }
        return performed
    }

    /**
     * 在底部栏做一次**慢速水平拖动**（用于拖出"全部任务"圆图标；用户实测必须"按住往左拖"）。
     *
     * 走系统手势 API（等同 adb 的 `input swipe`），比"进程内合成触摸"更接近真实拖动。
     */
    private fun dispatchHorizontalDrag(fromFraction: Float, toFraction: Float, yFraction: Float): Boolean {
        // 用全屏尺寸（见 [clickPointAt] 的说明）：displayMetrics 是可用窗口，会导致整体偏移。
        val (screenWidth, screenHeight) = displaySize()
        if (screenWidth <= 0 || screenHeight <= 0) return false
        val y = screenHeight * yFraction
        val fromX = screenWidth * fromFraction
        val toX = screenWidth * toFraction
        val path = Path().apply {
            // 先"按住"（极短的一小段，让手势以长按开始），再慢速左拖。
            moveTo(fromX, y)
            lineTo(fromX + 3f, y)
            lineTo(toX, y)
        }
        return dispatchGestureLogged(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0L, 1200L))
                .build(),
            label = "drag-horizontal $fromFraction->$toFraction y=$yFraction",
        )
    }

    /** 在屏幕中间垂直滑一次（WebView/Compose 都吃这一下）。 */
    private fun dispatchSwipe(forward: Boolean): Boolean {
        val (screenWidth, screenHeight) = displaySize()
        if (screenWidth <= 0 || screenHeight <= 0) return false
        val x = screenWidth / 2f
        val height = screenHeight.toFloat()
        val startY = if (forward) height * 0.78f else height * 0.30f
        val endY = if (forward) height * 0.30f else height * 0.78f
        val path = Path().apply {
            moveTo(x, startY)
            lineTo(x, endY)
        }
        return dispatchGestureLogged(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0L, 420L))
                .build(),
            label = if (forward) "swipe-forward" else "swipe-backward",
        )
    }

    private fun collectScrollableNodes(
        node: AccessibilityNodeInfo,
        output: MutableList<AccessibilityNodeInfo>,
    ) {
        if (node.isVisibleToUser && node.isEnabled && node.isScrollable) {
            output += AccessibilityNodeInfo.obtain(node)
        }
        for (index in 0 until node.childCount) {
            val child = runCatching { node.getChild(index) }.getOrNull() ?: continue
            try {
                collectScrollableNodes(child, output)
            } finally {
                child.recycleCompat()
            }
        }
    }

    private fun clickIds(roots: List<AccessibilityNodeInfo>, ids: List<String>): Boolean {
        val clean = ids.map(String::trim).filter(String::isNotEmpty)
        for (root in roots) {
            val packages = listOf(root.packageName?.toString().orEmpty(), TARGET_PACKAGE).distinct()
            for (id in clean) {
                for (packageName in packages) {
                    if (packageName.isBlank()) continue
                    val nodes = runCatching {
                        root.findAccessibilityNodeInfosByViewId("$packageName:id/$id")
                    }.getOrNull().orEmpty()
                    try {
                        for (node in nodes) {
                            if (
                                node.isVisibleToUser && node.isEnabled &&
                                (clickNodeOrParent(node) || tapNodeCenter(node))
                            ) return true
                        }
                    } finally {
                        nodes.forEach { it.recycleCompat() }
                    }
                }
            }
        }
        AndroidLog.d(TAG, "clickIds no target ids=$clean windows=${windowPackages()}")
        return false
    }

    private fun setText(
        roots: List<AccessibilityNodeInfo>,
        value: String,
        allowEmpty: Boolean = false,
    ): Boolean {
        if (value.isBlank() && !allowEmpty) return false
        // ① 用**和 [composerStateResult] 完全一样的收集方式**找输入框。
        //
        // 真机踩坑（`mode=fill` 探针）：老的 `findEditable` 递归在元宝的 Compose 窗口上
        // 报 `setText found no editable windows=3:com.android.systemui,884:com.tencent...` ——
        // 同一时刻 `composerStateResult` 用 `collectEditableByClassRelaxed` 却**看得到**
        // 那颗 `[210,2435][922,2645]` 的 EditText。两条路径的区别只在"怎么认这个节点"，
        // 所以这里直接复用已被证明能看到它的那一条（宽松类名收集），并且不要求
        // `isEditable`（Compose 上报为 false）。
        val relaxEditors = mutableListOf<AccessibilityNodeInfo>()
        roots.forEach { collectEditableByClassRelaxed(it, relaxEditors) }
        val target = relaxEditors.maxByOrNull { Rect().also(it::getBoundsInScreen).height() }
        if (target == null) {
            // ② 兜底：老的两条路径（原生 EditText 页仍有）。
            for (root in roots) {
                val editable = findEditable(root) ?: findEditableByClass(root) ?: continue
                if (applyText(editable, value, root)) return true
            }
            AndroidLog.d(
                TAG,
                "setText found no editable windows=${windowPackages()} " +
                    "classes=${classesOf(roots)}",
            )
            return false
        }
        return try {
            applyText(target, value, root = null)
        } finally {
            relaxEditors.forEach { it.recycleCompat() }
        }
    }

    /** 真正把文案写进节点（聚焦 + ACTION_SET_TEXT）。 */
    private fun applyText(
        editable: AccessibilityNodeInfo,
        value: String,
        root: AccessibilityNodeInfo?,
    ): Boolean {
        val applied = try {
            // 只做 ACTION_FOCUS：**不要**在这里 ACTION_CLICK —— 能力页上无障碍树里的
            // “可编辑节点”实际是输入区/添加素材入口，点一下就会把素材选择器重新打开，
            // 已选素材也会被顶掉（用户看到的就是“选完素材又点了一次选择素材”）。
            editable.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            editable.performAction(
                AccessibilityNodeInfo.ACTION_SET_TEXT,
                Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
                },
            )
        } finally {
            if (root != null && editable !== root) editable.recycleCompat()
        }
        AndroidLog.d(TAG, "setText applied=$applied chars=${value.length}")
        return applied
    }

    /** 诊断用：当前 roots 子树里出现过的 class 直方图（找输入框失败时打一条）。 */
    private fun classesOf(roots: List<AccessibilityNodeInfo>): String {
        val all = mutableListOf<AccessibilityNodeInfo>()
        roots.forEach { collectAllNodesRelaxed(it, all) }
        val counts = all.groupingBy { it.className?.toString()?.substringAfterLast('.') ?: "?" }
            .eachCount()
        return counts.entries.sortedByDescending { it.value }
            .take(8)
            .joinToString(",") { "${it.key}=${it.value}" }
    }

    /** 类名兜底：不要求 `isEditable`，只要看起来是输入框。 */
    private fun findEditableByClass(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val className = node.className?.toString().orEmpty()
        if (node.isVisibleToUser && node.isEnabled &&
            (className.contains("EditText") || className.contains("TextInput"))
        ) {
            @Suppress("DEPRECATION")
            return AccessibilityNodeInfo.obtain(node)
        }
        for (index in 0 until node.childCount) {
            val child = runCatching { node.getChild(index) }.getOrNull() ?: continue
            try {
                findEditableByClass(child)?.let { return it }
            } finally {
                child.recycleCompat()
            }
        }
        return null
    }

    private fun findEditable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isVisibleToUser && node.isEnabled && node.isEditable) {
            @Suppress("DEPRECATION")
            return AccessibilityNodeInfo.obtain(node)
        }
        // 元宝的输入框上报 `isVisibleToUser=false`（实测），严格条件永远不成立 →
        // 这里补一条"类名是 EditText 且有有效屏幕区域"的宽松判定，否则写入通道全部失败。
        val bounds = Rect().also(node::getBoundsInScreen)
        if (node.isEnabled &&
            bounds.width() > 0 && bounds.height() > 0 &&
            node.className?.toString()?.contains("EditText") == true
        ) {
            @Suppress("DEPRECATION")
            return AccessibilityNodeInfo.obtain(node)
        }
        for (index in 0 until node.childCount) {
            val child = runCatching { node.getChild(index) }.getOrNull() ?: continue
            try {
                findEditable(child)?.let { return it }
            } finally {
                child.recycleCompat()
            }
        }
        return null
    }

    private fun clickLatestMedia(
        roots: List<AccessibilityNodeInfo>,
        marker: String,
        blockedKeys: Set<String>,
    ): Bundle {
        val activeRoot = roots.firstOrNull()
        val activePackage = activeRoot?.packageName?.toString().orEmpty()
        val visibleText = buildString { roots.forEach { collectText(it, this) } }.lowercase()
        val cameraOnly = MEDIA_CAMERA_WORDS.any(visibleText::contains) &&
            MEDIA_PICKER_WORDS.none(visibleText::contains)
        if (AccessibilityPackagePolicy.isCameraPackage(activePackage) || cameraOnly) {
            AndroidLog.d(
                TAG,
                "skip media selection on camera surface package=$activePackage " +
                    "windows=${windowPackages()}",
            )
            return mediaTapResult(false, activePackage, candidateCount = 0)
        }

        // 只扫描**真的是相册**的窗口。判据见 [MediaPickerWindowPolicy]：
        // 同包窗口必须有"最近项目/相机胶卷/所有照片"这类强标记，
        // **不能**用泛词「相册/照片」—— P图 能力页上就有这两个词，会被误当成相册扫，
        // 结果扫不到任何照片格子（候选 0）→ 上层以为相册是空的 → 按返回 → 重开 → 循环。
        // 另外这里不再要求"必须是活动窗口"：元宝的相册可能不是 activity 的 activeRoot。
        val pickerRoots = roots.filter { root ->
            val packageName = root.packageName?.toString().orEmpty()
            val rootText = buildString { collectText(root, this) }
            MediaPickerWindowPolicy.shouldScan(
                packageName = packageName,
                windowText = rootText,
                isCameraPackage = AccessibilityPackagePolicy.isCameraPackage(packageName),
                isMediaPackage = AccessibilityPackagePolicy.isMediaPackage(packageName),
            ) || MediaPickerWindowPolicy.mentionsMarker(rootText, marker)
        }.ifEmpty { listOfNotNull(activeRoot) }

        val candidates = mutableListOf<MediaCandidateDescriptor>()
        var scannedRoots = 0
        // 格子尺寸判据一律按当前屏幕换算（参考机型 1272 宽），否则换分辨率后
        // "多大算缩略图"的阈值全错：1080 宽上 96px 门槛偏高、1440 宽上 900px 上限偏低。
        val scale = screenScale()
        for (root in pickerRoots) {
            val rootBounds = Rect().also(root::getBoundsInScreen)
            if (rootBounds.width() <= 0 || rootBounds.height() <= 0) continue
            scannedRoots++
            collectMediaCandidates(
                node = root,
                packageName = root.packageName?.toString().orEmpty(),
                rootBounds = rootBounds,
                output = candidates,
                scale = scale,
            )
        }
        val candidate = MediaCandidatePolicy.choose(
            candidates = candidates,
            marker = marker,
            screenText = visibleText,
            blockedKeys = blockedKeys,
        )
        if (candidate == null) {
            AndroidLog.d(
                TAG,
                "no media candidate package=$activePackage candidates=${candidates.size} " +
                    "roots=$scannedRoots blocked=${blockedKeys.size} windows=${windowPackages()} " +
                    "text=${visibleText.take(240).replace('\n', '|')}",
            )
            return mediaTapResult(false, activePackage, candidateCount = candidates.size)
        }
        val dispatched = tapAt(candidate.centerX.toFloat(), candidate.centerY.toFloat())
        AndroidLog.d(
            TAG,
            "media candidate gesture=$dispatched key=${candidate.key} ordinal=${candidate.ordinal} " +
                "score=${candidate.score} xy=${candidate.centerX},${candidate.centerY} " +
                "candidates=${candidates.size} roots=$scannedRoots",
        )
        return mediaTapResult(
            dispatched = dispatched,
            packageName = candidate.key.substringBefore('|').ifBlank { activePackage },
            candidate = candidate,
            candidateCount = candidates.size,
        )
    }

    private fun collectMediaCandidates(
        node: AccessibilityNodeInfo,
        packageName: String,
        rootBounds: Rect,
        output: MutableList<MediaCandidateDescriptor>,
        scale: ScreenScale = ScreenScale.FALLBACK,
    ) {
        if (node.isVisibleToUser && node.isEnabled) {
            val bounds = Rect().also(node::getBoundsInScreen)
            val className = node.className?.toString().orEmpty()
            val id = node.viewIdResourceName.orEmpty().lowercase()
            val ownDescription = nodeText(node).lowercase()
            val description = subtreeText(node).lowercase()
            // Ancestor subtree text often contains toolbar words such as
            // “选择/完成”; using it as an exclusion rejects the whole gallery.
            // Restrict exclusion/camera checks to the node's own label and id.
            val ownSemantic = "$ownDescription $id"
            val excluded = MEDIA_EXCLUDED_WORDS.any(ownSemantic::contains)
            // 相机的格子本身没有文字，"相机"在子节点上，所以这里也要看子树文字。
            val cameraTile = MEDIA_CAMERA_WORDS.any(ownSemantic::contains) ||
                MEDIA_CAMERA_WORDS.any(description::contains)
            val filenameMedia = MEDIA_FILENAME_REGEX.containsMatchIn(ownDescription)
            val timelineItem = className.contains("TimelineView") &&
                (description.contains("图片") || description.contains("image") ||
                    description.contains("第") && description.contains("项"))
            val idLooksLikeMedia = MEDIA_ID_HINTS.any(id::contains)
            val imageNode = className.endsWith("ImageView") &&
                (description.contains("图片") || description.contains("照片") ||
                    description.contains("image") || idLooksLikeMedia)
            val tileMin = scale.widthScaled(MEDIA_TILE_MIN_SIDE_REF)
            val sensibleBounds = bounds.width() >= tileMin && bounds.height() >= tileMin &&
                bounds.width() <= rootBounds.width() * 0.70f &&
                bounds.height() <= rootBounds.height() * 0.55f &&
                bounds.top >= rootBounds.top + rootBounds.height() * 0.08f
            // 元宝自带的相册选择器里，照片格子是**正方形**且没有文字/ID，而
            // “最近项目”下拉、顶部标签这些控件是**扁的**（例如 343x169、168x135），
            // 它们同样 ≥120px 会误判成格子。所以格子必须接近正方形、可点击、且不是相机。
            // 只接受**在屏幕内**的格子：拍题相册的窗口比屏幕高，屏幕外的格子点了没用
            // （实测候选落在 y=3113~3428 而屏幕只有 2800 高，于是选不中）。
            // 用**全屏**尺寸，不是 displayMetrics（后者是可用窗口 1272x2603）。
            // 相册最下面一行格子的 bottom 能到 2780 左右，用 2603 会被判成"屏幕外"
            // 而整个丢掉 —— 而"最新的那张"在按时间倒序的相册里未必在最后一排，
            // 但"最后一排被排除"会让候选数变少、排序结果偏向上面几排，
            // 实测就出现过"选中的其实是相册最后一张旧截图"。
            val (displayWidth, displayHeight) = displaySize()
            val onScreen = bounds.left >= 0 && bounds.top >= 0 &&
                bounds.right <= displayWidth && bounds.bottom <= displayHeight
            val shortSide = minOf(bounds.width(), bounds.height())
            val longSide = maxOf(bounds.width(), bounds.height())
            val gridMin = scale.widthScaled(MEDIA_GRID_MIN_SIDE_REF)
            val gridMax = scale.widthScaled(MEDIA_GRID_MAX_SIDE_REF)
            val square = shortSide >= gridMin && longSide - shortSide <= longSide * 0.18f
            val gridTile = square && onScreen &&
                bounds.width() in gridMin..gridMax &&
                bounds.height() in gridMin..gridMax &&
                node.isClickable &&
                !cameraTile &&
                !ownSemantic.contains("最近项目") &&
                !ownSemantic.contains("本地相册") &&
                !ownSemantic.contains("ip角色")
            if (
                sensibleBounds && !excluded &&
                (timelineItem || imageNode || idLooksLikeMedia || filenameMedia || gridTile)
            ) {
                var score = 0
                if (description.contains("第 1 项") || description.contains("第1项")) score += 160
                if (timelineItem) score += 120
                if (idLooksLikeMedia) score += 80
                if (filenameMedia) score += 180
                if (gridTile) score += 100
                if (description.contains("图片") || description.contains("照片")) score += 50
                if (node.isClickable) score += 20
                // A vendor gallery window outranks a host page that merely shows
                // thumbnail-like cards.
                if (AccessibilityPackagePolicy.isMediaPackage(packageName)) score += 70
                val ordinal = MEDIA_ORDINAL_REGEX.find(description)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.toIntOrNull()
                val semantic = listOf(ownDescription, description, id)
                    .filter(String::isNotBlank)
                    .joinToString(" ")
                    .replace(Regex("\\s+"), " ")
                    .take(800)
                output += MediaCandidateDescriptor(
                    key = "$packageName|${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}",
                    left = bounds.left,
                    top = bounds.top,
                    right = bounds.right,
                    bottom = bounds.bottom,
                    semantic = semantic,
                    className = className,
                    resourceId = id,
                    camera = cameraTile,
                    ordinal = ordinal,
                    score = score,
                )
            }
        }
        for (index in 0 until node.childCount) {
            val child = runCatching { node.getChild(index) }.getOrNull() ?: continue
            try {
                collectMediaCandidates(child, packageName, rootBounds, output, scale)
            } finally {
                child.recycleCompat()
            }
        }
    }

    private fun subtreeText(node: AccessibilityNodeInfo): String = buildString {
        append(nodeText(node)).append(' ')
        for (index in 0 until node.childCount) {
            val child = runCatching { node.getChild(index) }.getOrNull() ?: continue
            try {
                append(subtreeText(child)).append(' ')
            } finally {
                child.recycleCompat()
            }
        }
    }

    private fun clickNodeOrParent(node: AccessibilityNodeInfo): Boolean {
        if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        var parent = runCatching { node.parent }.getOrNull()
        repeat(8) {
            val current = parent ?: return false
            val clicked = current.isVisibleToUser && current.isEnabled && current.isClickable &&
                current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            val next = if (clicked) null else runCatching { current.parent }.getOrNull()
            current.recycleCompat()
            if (clicked) return true
            parent = next
        }
        parent?.recycleCompat()
        return false
    }

    private fun tapNodeCenter(node: AccessibilityNodeInfo): Boolean {
        val bounds = Rect().also(node::getBoundsInScreen)
        if (bounds.width() <= 0 || bounds.height() <= 0) return false
        return tapAt(bounds.exactCenterX(), bounds.exactCenterY())
    }

    private fun tapRelative(
        root: AccessibilityNodeInfo?,
        xFraction: Float,
        yFraction: Float,
    ): Boolean {
        val bounds = root?.let { Rect().also(it::getBoundsInScreen) }
        if (bounds != null && bounds.width() > 0 && bounds.height() > 0) {
            val x = bounds.left + bounds.width() * xFraction.coerceIn(0.02f, 0.98f)
            val y = bounds.top + bounds.height() * yFraction.coerceIn(0.02f, 0.98f)
            return tapAt(x, y)
        }
        // Nothing measurable to tap: use the display so a coordinate tap still
        // lands, for example while an input method window owns the active window.
        // **用全屏尺寸**：`tapAt` 的手势坐标系是全屏（含导航栏），
        // 用 displayMetrics（可用窗口）换算会让落点整体上移一个导航栏高度。
        val (screenWidth, screenHeight) = displaySize()
        if (screenWidth <= 0 || screenHeight <= 0) return false
        return tapAt(
            screenWidth * xFraction.coerceIn(0.02f, 0.98f),
            screenHeight * yFraction.coerceIn(0.02f, 0.98f),
        )
    }

    /**
     * 在窗口相对坐标处点击：优先找最深的可点节点直接 ACTION_CLICK，失败再走手势。
     *
     * Compose 页面（例如模板详情页的生成键）常常既没有文字也没有 resource-id，
     * 只有一块可点区域，这时候"按坐标找节点"比裸手势稳。
     */
    /**
     * 按**屏幕比例**点一个位置。
     *
     * ## 两个关键修正（1.4.10 真机实证）
     *
     * ### 1. 换算基准必须是**屏幕**，不能是 root 的 bounds
     *
     * 老实现是：
     * ```kotlin
     * val root = roots.firstOrNull() ?: return false
     * val bounds = Rect().also(root::getBoundsInScreen)
     * val x = bounds.left + bounds.width() * xFraction
     * ```
     * 当 `roots.first()` 恰好是**子容器**（不是全屏）时，`0.92` 会换算成一个
     * 比实际小很多的 y —— 落点跑到别的元素上。
     * 真机表现：点创作页底部"P图"（y 应为 2576），却进了模板详情页；
     * 而同一屏手工 `input tap 815 2576` 能正常打开相册。
     *
     * ### 2. 手势优先，不做 `performAction`
     *
     * Compose 页面的浮动按钮通常是"可点父容器包着一堆子元素"，
     * `deepestClickableAt` 会取到外层的卡片容器，`performAction(CLICK)` 于是把
     * 整张卡片点了（就是上面那次"进模板详情"）。
     * 真实触摸按坐标落点，不经过这种父子推断，反而准。
     */
    private fun clickPointAt(
        roots: List<AccessibilityNodeInfo>,
        xFraction: Float,
        yFraction: Float,
    ): Boolean {
        // **必须用 getRealSize()，不能用 resources.displayMetrics。**
        //
        // 真机实证（1.4.11）：无障碍服务进程的 `displayMetrics` 是 **1272x2603**
        // （可用窗口，扣掉了状态栏/导航栏），而节点坐标与手势坐标系都是全屏 **2800**。
        // 于是 `0.92 * 2603 = 2394`，比目标 2576 差了 182px —— 点击落到"P图"上方
        // 的模板卡片上，进了模板详情页；而手工 `input tap 815 2576` 是能开相册的。
        val (screenWidth, screenHeight) = displaySize()
        if (screenWidth <= 0 || screenHeight <= 0) return false
        val x = screenWidth * xFraction.coerceIn(0.02f, 0.98f)
        val y = screenHeight * yFraction.coerceIn(0.02f, 0.98f)
        val landed = tapAt(x, y)
        AndroidLog.d(
            TAG,
            "clickPoint x=${x.toInt()} y=${y.toInt()} " +
                "screen=${screenWidth}x$screenHeight gesture=$landed " +
                "roots=${roots.size}",
        )
        return landed
    }

    /** 坐标处最深的可点节点（返回 obtain 出来的副本，调用方负责回收）。 */
    private fun deepestClickableAt(node: AccessibilityNodeInfo, x: Int, y: Int): AccessibilityNodeInfo? {
        if (!node.isVisibleToUser || !node.isEnabled) return null
        val bounds = Rect().also(node::getBoundsInScreen)
        if (!bounds.contains(x, y)) return null
        for (index in 0 until node.childCount) {
            val child = runCatching { node.getChild(index) }.getOrNull() ?: continue
            val hit = deepestClickableAt(child, x, y)
            child.recycleCompat()
            if (hit != null) return hit
        }
        return if (node.isClickable) AccessibilityNodeInfo.obtain(node) else null
    }

    /**
     * 点击"输入框右侧"的发送键。
     *
     * 智能P图这类 Compose 页面的 ⬆️ 既没有文字也没有 resource-id，而平台 dump 在宿主进程里
     * 拿不到（宿主没有 root），所以完全依靠无障碍树：先找可编辑节点，再找与它垂直同排、
     * 位于其右侧、距离最近的可点节点并点击，失败则用同一坐标派发手势。
     */
    private fun clickButtonNearEditor(roots: List<AccessibilityNodeInfo>): Boolean {
        val editable = roots.asSequence()
            .mapNotNull { findEditable(it) }
            .maxByOrNull { Rect().also(it::getBoundsInScreen).height() }
        if (editable == null) {
            AndroidLog.d(TAG, "clickNearEditor no editable node windows=${windowPackages()}")
            return false
        }
        val editorBounds = Rect().also(editable::getBoundsInScreen)
        editable.recycleCompat()

        // **结构闸**：空文案时"输入框右侧那一颗"就是"切换语音"（有文案才是"发送 ⬆"）。
        // 这条兜底以前没有任何前置判定，正是用户报的"空输入框时被点到切换语音"。
        val flat = flatNodes(roots)
        val editorBox = Bounds(editorBounds.left, editorBounds.top, editorBounds.right, editorBounds.bottom)
        val textVerdict = composerTextVerdict(flat)
        if (textVerdict != true || ComposerSendGuard.showsEmptySignature(
                flat,
                editorBox,
                displaySize().first,
                screenScale(),
            )
        ) {
            AndroidLog.w(
                TAG,
                "clickNearEditor 拒点：输入框未确认有文案 text=$textVerdict（避免点到切换语音 ✗）",
            )
            return false
        }

        val clickables = mutableListOf<AccessibilityNodeInfo>()
        roots.forEach { collectClickableNodes(it, clickables) }
        // **排除"输入框旁边那几个干扰键"**：语音切换 / 拍照 就紧挨着输入框，
        // 旧实现把它们也算进"右侧候选"，真机就选中过麦克风中心 (992,2540)
        // ——点下去元宝切成"按住 说话"，消息永远发不出。
        //
        // ⚠️ **只能用"语音/拍照"这两个标记**，不能连"更多"一起排除：
        // 那颗翻转按钮的容器 `[1048,2435][1216,2645]` 里**就装着**"更多" `[1090,2498][1174,2582]`，
        // 按几何重叠一排除会把真正的发送键也一起排掉。
        val voiceBoxes = flat.filter { node ->
            VOICE_TOGGLE_MARKERS.any { (node.text + node.description).contains(it) }
        }.map { it.box }
        fun overlapsVoice(bounds: Rect): Boolean = voiceBoxes.any { box ->
            bounds.left < box.right && bounds.right > box.left &&
                bounds.top < box.bottom && bounds.bottom > box.top
        }
        val candidates = clickables
            .map { it to Rect().also { rect -> it.getBoundsInScreen(rect) } }
            .filter { (node, bounds) ->
                node.isEnabled && bounds.width() > 0 && bounds.height() > 0 &&
                    bounds.centerY() in (editorBounds.top - 48)..(editorBounds.bottom + 48) &&
                    bounds.left >= editorBounds.right - 24 &&
                    bounds.width() <= editorBounds.width() &&
                    !overlapsVoice(bounds)
            }
            // 发送键与输入框基本**同一垂直中心**（实测 2.87.20 上它偏上；原来取"最靠下"的
            // 会点空或点到别的按钮），所以按"与输入框中心线的距离"排序，再取最靠右的。
            .sortedWith(
                compareBy<Pair<AccessibilityNodeInfo, Rect>> { (_, bounds) ->
                    Math.abs(bounds.centerY() - editorBounds.centerY())
                }.thenByDescending { (_, bounds) -> bounds.left },
            )
        val target = candidates.firstOrNull()
        val clicked = target?.first?.let { node ->
            AndroidLog.d(
                TAG,
                "clickNearEditor class=${node.className} bounds=${target.second.toShortString()} " +
                    "editor=${editorBounds.toShortString()} candidates=${candidates.size}",
            )
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        } ?: false
        val fallbackBounds = target?.second
        clickables.forEach { it.recycleCompat() }
        if (clicked) return true
        AndroidLog.d(
            TAG,
            "clickNearEditor semantic click failed candidates=${candidates.size} fallback=$fallbackBounds",
        )
        return fallbackBounds != null &&
            tapAt(fallbackBounds.centerX().toFloat(), fallbackBounds.centerY().toFloat())
    }

    private fun collectClickableNodes(node: AccessibilityNodeInfo, output: MutableList<AccessibilityNodeInfo>) {
        if (!node.isVisibleToUser) return
        if (node.isClickable) {
            @Suppress("DEPRECATION")
            output += AccessibilityNodeInfo.obtain(node)
        }
        for (index in 0 until node.childCount) {
            val child = runCatching { node.getChild(index) }.getOrNull() ?: continue
            try {
                collectClickableNodes(child, output)
            } finally {
                child.recycleCompat()
            }
        }
    }

    /**
     * 点开并聚焦输入框。
     *
     * 智能P图这类 Compose 页面的输入框在**收起**状态下没有可写节点，直接 `ACTION_SET_TEXT`
     * 要么失败、要么（更糟）整段替换把已选的图片附件抹掉。先点一下把编辑框展开/聚焦，
     * 宿主就能拿到输入连接，用 `commitText` **追加**文案，图片和文案才能共存。
     */
    private fun focusEditor(roots: List<AccessibilityNodeInfo>): Boolean {
        for (root in roots) {
            val editable = findEditable(root) ?: findEditableByClass(root) ?: continue
            val className = editable.className
            val focused = editable.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            val clicked = editable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            if (editable !== root) editable.recycleCompat()
            AndroidLog.d(TAG, "focusEditor class=$className focus=$focused click=$clicked")
            if (focused || clicked) return true
        }
        AndroidLog.d(TAG, "focusEditor found no editor windows=${windowPackages()}")
        return false
    }

    /**
     * 点窗口**底部**最靠下的可点按钮。
     *
     * 元宝自带相册选择器的“确定/完成”是个没有文字、没有 resource-id 的按钮，位于窗口底部；
     * 文字与 ID 都匹配不到，只能按位置找（P图/拍题的确认都走这条）。
     */
    private fun clickBottomButton(roots: List<AccessibilityNodeInfo>): Boolean {
        val clickables = mutableListOf<AccessibilityNodeInfo>()
        roots.forEach { collectClickableNodes(it, clickables) }
        val window = roots.firstOrNull()?.let { Rect().also(it::getBoundsInScreen) }
        val bottomLimit = window?.let { it.top + it.height() * 0.65f } ?: 0f
        val scale = screenScale()
        val minWidth = scale.widthScaled(CLICK_BOTTOM_MIN_WIDTH_REF)
        val minHeight = scale.widthScaled(CLICK_BOTTOM_MIN_HEIGHT_REF)
        val maxSide = scale.widthScaled(CLICK_BOTTOM_MAX_SIDE_REF)
        val candidates = clickables
            .map { it to Rect().also { rect -> it.getBoundsInScreen(rect) } }
            .filter { (node, bounds) ->
                node.isEnabled && bounds.width() in minWidth..maxSide &&
                    bounds.height() in minHeight..maxSide &&
                    bounds.centerY() > bottomLimit
            }
            .sortedWith(
                compareByDescending<Pair<AccessibilityNodeInfo, Rect>> { (_, b) -> b.bottom }
                    .thenByDescending { (_, b) -> b.right },
            )
        val target = candidates.firstOrNull()
        val clicked = target?.first?.let { node ->
            AndroidLog.d(
                TAG,
                "clickBottomButton class=${node.className} bounds=${target.second.toShortString()} " +
                    "candidates=${candidates.size}",
            )
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        } ?: false
        val fallbackBounds = target?.second
        clickables.forEach { it.recycleCompat() }
        if (clicked) return true
        AndroidLog.d(TAG, "clickBottomButton semantic failed candidates=${candidates.size} fallback=$fallbackBounds")
        return fallbackBounds != null &&
            tapAt(fallbackBounds.centerX().toFloat(), fallbackBounds.centerY().toFloat())
    }

    /**
     * 把剪贴板内容粘贴到输入框（`ACTION_PASTE`）。
     *
     * 这是"在已有图片素材的输入框里追加文案"最可靠的通道：粘贴是**插入**语义，
     * 不会像 `ACTION_SET_TEXT` 那样整段替换、把图片附件抹掉。
     */
    private fun pasteIntoEditor(roots: List<AccessibilityNodeInfo>): Boolean {
        // 和 [setText] 同样的理由：老的 `findEditable ?: findEditableByClass` 在元宝的
        // Compose 输入栏上**看不到**那颗 EditText（`mode=fill` 探针里 clipboardPaste=false
        // 就是这么来的），必须换成 `collectEditableByClassRelaxed`。
        val editors = mutableListOf<AccessibilityNodeInfo>()
        roots.forEach { collectEditableByClassRelaxed(it, editors) }
        if (editors.isEmpty()) {
            AndroidLog.d(
                TAG,
                "pasteIntoEditor found no editor windows=${windowPackages()} " +
                    "classes=${classesOf(roots)}",
            )
            return false
        }
        // 输入栏是屏幕上最高的那个可编辑节点；从小往上试，能粘上就返回。
        val ordered = editors.sortedByDescending { Rect().also(it::getBoundsInScreen).height() }
        var pastedAny = false
        try {
            for (editable in ordered) {
                val className = editable.className
                val box = Rect().also(editable::getBoundsInScreen).toShortString()
                val pasted = runCatching {
                    editable.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                    editable.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                }.getOrDefault(false)
                AndroidLog.d(TAG, "pasteIntoEditor class=$className box=$box pasted=$pasted")
                if (pasted) {
                    pastedAny = true
                    break
                }
            }
        } finally {
            editors.forEach { it.recycleCompat() }
        }
        if (!pastedAny) {
            AndroidLog.d(TAG, "pasteIntoEditor none accepted candidate=${editors.size}")
        }
        return pastedAny
    }

    /**
     * 找包含指定文字的节点，返回它的**屏幕中心坐标**。
     *
     * 用于"底部导航 / 入口按钮"这类位置会随版本变化的元素：从无障碍树里取真实坐标，
     * 再交给宿主进程内真实触摸去点，避免写死坐标在换版本后点偏。
     */
    private fun findNodeCenter(
        roots: List<AccessibilityNodeInfo>,
        label: String,
        bottomOnly: Boolean = false,
    ): IntArray? {
        if (label.isBlank()) return null
        val matches = mutableListOf<Rect>()
        for (root in roots) collectTextBounds(root, label, matches)
        // **先剔掉屏幕外的节点。**
        //
        // 真机实证（1.4.3 免 root 版自检）：创作页里有横向滚动的容器，
        // 里面的节点 `getBoundsInScreen()` 会给出 `x≈2087`（屏幕只有 1272 宽）。
        // 这些离屏节点往往**面积更小**，`minByOrNull { area }` 就会选中它们，
        // 于是"底部 P图"拿到了一个点不到的坐标 —— 而上层的越界保护会拒绝点击，
        // 表现成"什么都没发生"，日志里只有一句 `坐标超出屏幕`。
        // 同样用全屏尺寸：`displayMetrics` 是可用窗口（1272x2603），
        // 用它做"是否在屏幕内"的过滤会把底部 200px 的正常节点误判成离屏。
        val (width, height) = displaySize()
        val onScreen = matches.filter { rect ->
            rect.centerX() in 0..width && rect.centerY() in 0..height
        }
        val pool = if (bottomOnly) {
            val limit = height * 0.75f
            onScreen.filter { it.centerY() > limit }
        } else {
            onScreen
        }
        val best = pool.minByOrNull { it.width().toLong() * it.height() } ?: return null
        return intArrayOf(best.centerX(), best.centerY())
    }

    private fun collectTextBounds(node: AccessibilityNodeInfo, label: String, output: MutableList<Rect>) {
        // 注意：**不要**用 isVisibleToUser 过滤 —— 元宝 2.87.20 的底部导航上报为不可见，
        // 但它在屏幕上有正常区域且能被 dump 到。只要求"屏幕区域有效"。
        val bounds = Rect().also(node::getBoundsInScreen)
        if (bounds.width() <= 0 || bounds.height() <= 0) return
        val own = nodeText(node) + " " + node.contentDescription?.toString().orEmpty()
        if (own.contains(label)) {
            output += bounds
        }
        for (index in 0 until node.childCount) {
            val child = runCatching { node.getChild(index) }.getOrNull() ?: continue
            try {
                collectTextBounds(child, label, output)
            } finally {
                child.recycleCompat()
            }
        }
    }

    /**
     * 按文字或 content-desc 找节点并点它的**真实屏幕中心**。
     *
     * 选择策略：
     * 1. 只收"屏幕区域有效"且 `text`/`content-desc` 含 label 的节点；
     * 2. 优先取**可点的祖先**的矩形（装饰节点本身 `clickable=false`，
     *    真正接手势的是外面那层可点容器，例如首页的 `ic_navigation_show`）；
     * 3. 在候选中取**面积最小**的，避免点到整屏根容器；
     * 4. 面积小于下限量（说明是 0×0/1×1 的占位节点）直接丢弃。
     *
     * 找不到就返回 false —— 不做任何坐标兜底（"宁可不点，也不误触"）。
     */
    private fun tapNodeByLabel(roots: List<AccessibilityNodeInfo>, label: String): Boolean {
        if (label.isBlank()) return false
        val candidates = mutableListOf<Pair<Rect, Boolean>>()
        for (root in roots) collectLabelCandidates(root, label, candidates)
        val usable = candidates.filter { (rect, _) ->
            val minSide = screenScale().dpScaled(MIN_TAPTABLE_SIDE_REF)
            rect.width() >= minSide && rect.height() >= minSide
        }
        if (usable.isEmpty()) {
            AndroidLog.w(TAG, "tapNodeByLabel no candidate label=$label raw=${candidates.size}")
            return false
        }
        // 可点的优先；同为可点/不可点时取面积最小。
        val ranked = usable.sortedWith(
            compareByDescending<Pair<Rect, Boolean>> { it.second }
                .thenBy { it.first.width().toLong() * it.first.height() },
        )
        val (rect, clickable) = ranked.first()
        AndroidLog.d(
            TAG,
            "tapNodeByLabel label=$label xy=${rect.centerX()},${rect.centerY()} " +
                "bounds=[${rect.left},${rect.top}][${rect.right},${rect.bottom}] " +
                "clickable=$clickable candidates=${usable.size}",
        )
        return tapAt(rect.centerX().toFloat(), rect.centerY().toFloat())
    }

    /**
     * 收集匹配 label 的节点矩形，并向上找可点祖先。
     *
     * 元宝把语义挂在**内层装饰 View** 上（只有 content-desc、`clickable=false`），
     * 真正吃手势的是外层可点容器。这里对每个匹配节点向上走最多 4 层，
     * 若找到 `isClickable` 的祖先就把它作为候选（记为 clickable=true）。
     */
    private fun collectLabelCandidates(
        node: AccessibilityNodeInfo,
        label: String,
        output: MutableList<Pair<Rect, Boolean>>,
    ) {
        val bounds = Rect().also(node::getBoundsInScreen)
        if (bounds.width() > 0 && bounds.height() > 0) {
            val own = nodeText(node) + " " + node.contentDescription?.toString().orEmpty()
            if (own.contains(label)) {
                val clickableAncestor = runCatching { findClickableAncestor(node) }.getOrNull()
                if (clickableAncestor != null) {
                    val ancestorBounds = Rect().also(clickableAncestor::getBoundsInScreen)
                    if (ancestorBounds.width() > 0 && ancestorBounds.height() > 0) {
                        output += ancestorBounds to true
                    }
                }
                output += bounds to node.isClickable
            }
        }
        for (index in 0 until node.childCount) {
            val child = runCatching { node.getChild(index) }.getOrNull() ?: continue
            try {
                collectLabelCandidates(child, label, output)
            } finally {
                child.recycleCompat()
            }
        }
    }

    /**
     * 向上最多 4 层找可点祖先（元宝的语义节点常是装饰层，容器才吃手势）。
     *
     * 不回收中间节点：平台从 `getParent()` 返回的实例由无障碍框架持有，
     * 主动 recycle 会把仍在树上的节点回收掉（后续读取崩溃）。只在最末端
     * 交给调用方使用，用完由 `withAllRoots` 统一处理。
     */
    private fun findClickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = runCatching { node.parent }.getOrNull()
        var depth = 0
        while (current != null && depth < 4) {
            if (current.isClickable) return current
            current = runCatching { current!!.parent }.getOrNull()
            depth++
        }
        return null
    }

    /** 收集所有"有屏幕区域"的节点（不要求 clickable/可见）：用于找没有语义的发送 ⬆。 */
    private fun collectAllNodesRelaxed(
        node: AccessibilityNodeInfo,
        output: MutableList<AccessibilityNodeInfo>,
    ) {
        val bounds = Rect().also(node::getBoundsInScreen)
        if (bounds.width() > 0 && bounds.height() > 0) {
            @Suppress("DEPRECATION")
            output += AccessibilityNodeInfo.obtain(node)
        }
        for (index in 0 until node.childCount) {
            val child = runCatching { node.getChild(index) }.getOrNull() ?: continue
            try {
                collectAllNodesRelaxed(child, output)
            } finally {
                child.recycleCompat()
            }
        }
    }

    /** 宽松收集"类名是输入框"的节点：不要求 isEditable / isVisibleToUser（元宝上报为不可见）。 */
    private fun collectEditableByClassRelaxed(
        node: AccessibilityNodeInfo,
        output: MutableList<AccessibilityNodeInfo>,
    ) {
        val bounds = Rect().also(node::getBoundsInScreen)
        if (bounds.width() <= 0 || bounds.height() <= 0) return
        if (node.className?.toString()?.contains("EditText") == true) {
            @Suppress("DEPRECATION")
            output += AccessibilityNodeInfo.obtain(node)
        }
        for (index in 0 until node.childCount) {
            val child = runCatching { node.getChild(index) }.getOrNull() ?: continue
            try {
                collectEditableByClassRelaxed(child, output)
            } finally {
                child.recycleCompat()
            }
        }
    }

    /** 同上，为"底部导航"这类上报不可见但实际存在的节点准备的宽松采集。 */
    private fun collectClickableNodesRelaxed(
        node: AccessibilityNodeInfo,
        output: MutableList<AccessibilityNodeInfo>,
    ) {
        val bounds = Rect().also(node::getBoundsInScreen)
        if (bounds.width() <= 0 || bounds.height() <= 0) return
        if (node.isClickable) {
            @Suppress("DEPRECATION")
            output += AccessibilityNodeInfo.obtain(node)
        }
        for (index in 0 until node.childCount) {
            val child = runCatching { node.getChild(index) }.getOrNull() ?: continue
            try {
                collectClickableNodesRelaxed(child, output)
            } finally {
                child.recycleCompat()
            }
        }
    }

    /**
     * 点底部导航第 [index] 个（0 起）。
     *
     * 换版本后底部导航的文字可能不在可点节点里、位置也会变，所以这里**不看文字**：
     * 取屏幕底部一排可点节点，按 x 从左到右排序，第 index 个即第 index 个导航项。
     */
    /**
     * 从容器及其子树里抽取一个可读标签。
     *
     * 底栏那排**可点容器本身没有 text/content-desc**（实测四个容器 `nodeText` 全是空），
     * 文字挂在里面的 TextView 上。按 index 盲点一个"没有标签的容器"根本无法验证点了
     * 什么 —— 实测就是这里点开了"模型"弹窗、把整条流程带崩。抽到标签后日志里能看清
     * 每一项是什么，也才能做"点之前先核对"。
     */
    private fun extractLabel(node: AccessibilityNodeInfo, depth: Int = 0): String {
        nodeText(node).takeIf(String::isNotBlank)?.let { return it }
        if (depth >= 3 || node.childCount <= 0) return ""
        for (index in 0 until node.childCount) {
            val child = runCatching { node.getChild(index) }.getOrNull() ?: continue
            val label = extractLabel(child, depth + 1)
            if (label.isNotBlank()) return label
        }
        return ""
    }

    /**
     * 点底部导航栏第 [index] 项。
     *
     * @param labelHint 调用方期望的标签（如"问元宝"/"我们"）。给了就**先核对再点**：
     *   对不上直接返回 false，绝不去点一个不知道是什么的容器。
     *   实测底栏换版后项含义会变（2.87.20 的 index=0 是"模型"选择器，
     *   一点就弹窗），没有这道核对就只能靠运气。
     */
    private fun tapBottomTab(
        roots: List<AccessibilityNodeInfo>,
        index: Int,
        labelHint: String = "",
    ): Boolean {
        val (_, screenHeight) = displaySize()
        val scale = screenScale()
        val topLimit = screenHeight * 0.85f
        // 底栏单项的尺寸范围：参考机 60..600（宽）/ 60..500（高），按屏宽等比换算。
        val itemMin = scale.widthScaled(BOTTOM_TAB_MIN_SIDE_REF)
        val itemMaxWidth = scale.widthScaled(BOTTOM_TAB_MAX_WIDTH_REF)
        val itemMaxHeight = scale.widthScaled(BOTTOM_TAB_MAX_HEIGHT_REF)
        val rowTolerance = scale.widthScaled(BOTTOM_TAB_ROW_TOLERANCE_REF)
        val duplicateGap = scale.widthScaled(BOTTOM_TAB_DUPLICATE_GAP_REF)
        val clickables = mutableListOf<AccessibilityNodeInfo>()
        roots.forEach { collectClickableNodesRelaxed(it, clickables) }
        val spots = clickables
            .map { it to Rect().also { rect -> it.getBoundsInScreen(rect) } }
            .filter { (_, bounds) ->
                bounds.centerY() > topLimit &&
                    bounds.width() in itemMin..itemMaxWidth &&
                    bounds.height() in itemMin..itemMaxHeight
            }
            .sortedBy { (_, bounds) -> bounds.centerX() }
        // 底部带里可能还有"输入框那一排"（实测 2.87.20）：导航是**最上面那一排**，
        // 所以只保留 centerY 与"最小 centerY"相差一个行高容差以内的项。
        val rowTop = spots.minOfOrNull { (_, bounds) -> bounds.centerY() } ?: 0
        val rowSpots = spots.filter { (_, bounds) -> Math.abs(bounds.centerY() - rowTop) <= rowTolerance }
        val unique = mutableListOf<Pair<AccessibilityNodeInfo, Rect>>()
        for (candidate in rowSpots) {
            val duplicate = unique.any { (_, bounds) ->
                Math.abs(bounds.centerX() - candidate.second.centerX()) < duplicateGap
            }
            if (!duplicate) unique += candidate
        }
        // 每一项抽取可读标签（容器自身没文字，文字在内层），供核对与日志。
        val labeled = unique.map { (node, rect) -> Triple(extractLabel(node), node, rect) }
        val inventory = labeled.joinToString(" | ") { (text, _, rect) ->
            "'${text.take(12)}'@${rect.centerX()},${rect.centerY()}"
        }
        AndroidLog.d(
            TAG,
            "tapBottomTab index=$index hint='$labelHint' spots=${unique.size} candidates=[$inventory]",
        )
        // 给了期望标签就先核对：找不到同名项 → 不点。
        val chosen = if (labelHint.isNotBlank()) {
            val match = labeled.firstOrNull { (text, _, _) ->
                text.contains(labelHint) || labelHint.contains(text.takeIf(String::isNotBlank) ?: "\u0000")
            }
            if (match == null) {
                AndroidLog.w(
                    TAG,
                    "tapBottomTab 底栏没有 '$labelHint' → 不点（避免点到未知容器）candidates=[$inventory]",
                )
                clickables.forEach { it.recycleCompat() }
                return false
            }
            match
        } else {
            labeled.getOrNull(index)
        }
        // 兜底闸门：没有标签的容器不点 —— 点了也不知道点的是什么。
        if (chosen != null && chosen.first.isBlank()) {
            AndroidLog.w(TAG, "tapBottomTab 目标容器无标签 → 不点（无法核对）candidates=[$inventory]")
            clickables.forEach { it.recycleCompat() }
            return false
        }
        val target = chosen
        val clicked = clickNode("bottomTab#${target?.first}", target?.second, target?.third)
        val bounds = target?.third
        clickables.forEach { it.recycleCompat() }
        if (clicked) return true
        return bounds != null && tapAt(bounds.centerX().toFloat(), bounds.centerY().toFloat())
    }

    /**
     * 点底栏**最右侧的小圆图标**（"全部任务"菜单按钮）。
     *
     * 它的位置会随底栏横向滚动而变（实测 (1132,2240)~（1151,2365)），所以不写死坐标：
     * 取底部带（y>75%）里宽度 60~260 的可点节点中 x 最大的那个。
     */
    private fun tapBottomRightIcon(roots: List<AccessibilityNodeInfo>): Boolean {
        // 用全屏高度：节点 bounds 是 getBoundsInScreen() 的全屏坐标，
        // displayMetrics（可用窗口）当基准会把 75% 线整体下移一个导航栏。
        val (_, screenHeight) = displaySize()
        val topLimit = screenHeight * 0.75f
        val hasScale = screenScale()
        val minSide = hasScale.widthScaled(BOTTOM_RIGHT_ICON_MIN_SIDE_REF)
        val maxSide = hasScale.widthScaled(BOTTOM_RIGHT_ICON_MAX_SIDE_REF)
        val clickables = mutableListOf<AccessibilityNodeInfo>()
        roots.forEach { collectClickableNodesRelaxed(it, clickables) }
        val candidate = clickables
            .map { it to Rect().also { rect -> it.getBoundsInScreen(rect) } }
            .filter { (_, bounds) ->
                bounds.centerY() > topLimit &&
                    bounds.width() in minSide..maxSide &&
                    bounds.height() in minSide..maxSide
            }
            .maxByOrNull { (_, bounds) -> bounds.centerX() }
        val clicked = clickNode("bottomRightIcon", candidate?.first, candidate?.second)
        val bounds = candidate?.second
        clickables.forEach { it.recycleCompat() }
        AndroidLog.d(TAG, "tapBottomRightIcon clicked=$clicked bounds=$bounds")
        if (clicked) return true
        return bounds != null && tapAt(bounds.centerX().toFloat(), bounds.centerY().toFloat())
    }

    private val HOST_PACKAGE = "com.tencent.hunyuan.app.chat"

    /**
     * 从无障碍树里解析输入栏几何（输入框 + 同行按钮）。
     *
     * 不做任何"比例估算"：节点报不出输入框就返回 null，调用方负责放弃这一步。
     */
    private fun composerBar(roots: List<AccessibilityNodeInfo>): ComposerGeometry.Bar? {
        val siblings = mutableListOf<AccessibilityNodeInfo>()
        roots.forEach { collectAllNodesRelaxed(it, siblings) }
        // 把无障碍节点"拍平"成 ScreenScanner.DumpNode，然后**复用单测锁定的那套
        // ComposerGeometry.resolve**（同一份代码，测试通过就等于真机逻辑正确）。
        // 坐标只读 Rect 的原始字段，不调 Rect 的方法 —— 那些在单测里是空壳。
        val flat = mutableListOf<ScreenScanner.DumpNode>()
        for (node in siblings) {
            val rect = Rect().also { node.getBoundsInScreen(it) }
            if (rect.right <= rect.left || rect.bottom <= rect.top) continue
            flat.add(
                ScreenScanner.DumpNode(
                    packageName = node.packageName?.toString().orEmpty(),
                    text = nodeText(node),
                    description = node.contentDescription?.toString().orEmpty(),
                    resourceId = node.viewIdResourceName.orEmpty(),
                    className = node.className?.toString().orEmpty(),
                    // 直接读 Rect 的原始字段装进 Bounds（不调 Rect 的方法）。
                    bounds = Bounds(rect.left, rect.top, rect.right, rect.bottom),
                    clickable = node.isClickable,
                    scrollable = node.isScrollable,
                ),
            )
        }
        prevCandidates = flat
        // **语义翻转开关**：有文案时右侧那颗按钮是"发送 ⬆"，里面那颗 `content-desc='更多'`
        // 只是箭头图标，不再是干扰键；空框时它才是真"更多(+)"。不区分这两者就会像真机
        // `evidence/trace_c2` 那样：发送容器中心被判成"落在更多里"，绕到左边缘去点 → 点不中。
        val textPresent = ComposerState.resolveFromNodes(flat) == ComposerState.Text.HAS_TEXT
        val bar = ComposerGeometry.resolve(flat, textPresent = textPresent, scale = screenScale())
        siblings.forEach { it.recycleCompat() }
        if (bar == null) {
            AndroidLog.d(TAG, "composerBar 未从无障碍树解析出输入框（nodes=${flat.size}）→ 放弃")
        } else {
            AndroidLog.d(
                TAG,
                "composerBar editor=${bar.editor.toShortString()} send=${bar.sendButton?.toShortString()} " +
                    "voice=${bar.voiceButton?.toShortString()} more=${bar.moreButton?.toShortString()} " +
                    "textPresent=$textPresent action=${bar.actionX},${bar.actionY}",
            )
            // 诊断：把"可能被当成发送键"的候选**全部**列出来。
            // 换版本/改布局时，这一行能直接看出真正的 ⬆ 是哪个 bounds
            // （2026-10 那次 8 连空点就是靠它定位到"选中了一颗 70×70 的小节点"）。
            AndroidLog.d(TAG, "composerBar 右侧候选 ${rightSideCandidates(flat, bar.editor).joinToString(" ; ")}")
        }
        return bar
    }

    /** 诊断用：输入框右缘之外、尺寸像按钮的节点清单。 */
    private fun rightSideCandidates(
        flat: List<ScreenScanner.DumpNode>,
        editor: Bounds,
    ): List<String> {
        val scale = screenScale()
        return flat
            .filter { node ->
                val minSide = scale.controlMinSide()
                val maxSide = scale.controlMaxSide()
                node.box.width in minSide..maxSide && node.box.height in minSide..maxSide &&
                    node.box.left >= editor.right - scale.rowTolerance()
            }
            .sortedByDescending { it.box.left }
            .take(12)
            .map { node ->
                "${node.className.substringAfterLast('.')}@${node.box.toShortString()}" +
                    " desc='${node.description.take(10)}' text='${node.text.take(8)}'"
            }
    }

    /**
     * 按真实几何点发送键，并在派发前做**安全校验**。
     *
     * 校验不通过就返回 false —— 这一步的设计目标是"宁可不发送，也绝不误触"。
     */
    private fun tapSendByGeometry(roots: List<AccessibilityNodeInfo>): Boolean {
        val bar = composerBar(roots) ?: run {
            AndroidLog.d(TAG, "sendSafe 找不到输入栏 → 放弃（不做坐标兜底）")
            return false
        }
        // **结构闸**（用户报的误触就出在这里）：右侧那颗容器空文案时是"切换语音"、
        // 有文案时才是"发送"。没有正面证据（发送节点 / 文案实证）一律不点。
        // 这条判定是纯函数，见 ComposerSendGuard（有真机 dump 单测）。
        // ⓪' **输入栏必须落在屏幕内**（1.1.6 新增）。
        //
        // 真机 `evidence/trace_e3_ask` 实证：侧栏（抽屉）展开时主窗口被推到屏幕**右侧之外**，
        // 无障碍树报 `editor=[1108,2274][1871,2571]` / `send=[2008,2392][2167,2551]`
        // （屏幕只有 1272 宽）。此时所有几何都不可信，点下去只会白点。
        // 与其让上层把"白点"当成"发送没生效"再连试 4 次，不如在这里明确判失败。
        val (screenW, screenH) = displaySize()
        if (screenW > 0 && screenH > 0 &&
            (bar.editor.right > screenW || bar.editor.bottom > screenH ||
                bar.editor.left < 0 || bar.editor.top < 0)
        ) {
            AndroidLog.w(
                TAG,
                "sendSafe 输入栏在屏幕外 editor=${bar.editor.toShortString()} 屏幕=${screenW}x$screenH" +
                    " → 主窗口被侧栏推出屏幕，拒绝发送（几何不可信）",
            )
            return false
        }
        val flat = flatNodes(roots)
        val guard = ComposerSendGuard.decide(
            nodes = flat,
            editor = bar.editor,
            // 服务进程能从无障碍树读到 Compose 输入框的文案 —— 把真实读数喂进闸门。
            hostEditorText = composerTextVerdict(flat),
            geometryPoint = bar.actionX to bar.actionY,
            screenWidth = displaySize().first,
            scale = bar.scale,
        )
        if (!guard.allowed) {
            AndroidLog.w(TAG, "sendSafe 拒点：${guard.reason}（避免点到切换语音 ✗）")
            return false
        }
        if (guard.verdict == ComposerSendGuard.Verdict.SEND_BY_NODE) {
            AndroidLog.d(TAG, "sendSafe 点发送节点 xy=${guard.x},${guard.y} (${guard.reason})")
            return tapAt(guard.x.toFloat(), guard.y.toFloat())
        }
        val x = bar.actionX
        val y = bar.actionY
        if (!ComposerGeometry.isSafeActionPoint(bar, x, y)) {
            AndroidLog.w(TAG, "sendSafe 目标点不安全 xy=$x,$y editor=${bar.editor.toShortString()} → 放弃")
            return false
        }
        AndroidLog.d(TAG, "sendSafe 按真实几何点发送 xy=$x,$y send=${bar.sendButton?.toShortString()}")
        return tapAt(x.toFloat(), y.toFloat())
    }

    /**
     * 把 [ComposerState] 的结论翻译成 [ComposerSendGuard] 要的三态。
     *
     * `true` = 确认有文案；`false` = 确认空/语音模式；`null` = 证据不足。
     *
     * 以前两处 `ComposerSendGuard.decide(...)` 都硬传 `null`，等于只靠"同行有没有
     * 空签名"这一条结构证据；现在服务进程能从无障碍树直接读到 Compose 输入框的文案，
     * 把真实读数喂进去，判定强得多。
     */
    private fun composerTextVerdict(flat: List<ScreenScanner.DumpNode>): Boolean? =
        when (ComposerState.resolveFromNodes(flat)) {
            ComposerState.Text.HAS_TEXT -> true
            ComposerState.Text.EMPTY, ComposerState.Text.VOICE_MODE -> false
            ComposerState.Text.UNKNOWN -> null
        }

    /** 把输入栏几何回传给宿主进程（宿主据此决定点哪里，不再自己算比例）。 */
    private fun composerGeometryResult(roots: List<AccessibilityNodeInfo>): Bundle {
        val bar = composerBar(roots) ?: return result(false)
        // 回传**输入框安全聚焦点**而不是裸中心：中心可能叠着图片缩略图/内嵌按钮。
        val focus = bar.editorFocusPoint() ?: ComposerGeometry.editorCenter(bar)
        return result(true, focus.first, focus.second).apply {
            putInt(KEY_SEND_X, bar.actionX)
            putInt(KEY_SEND_Y, bar.actionY)
            putBoolean(KEY_SEND_SAFE, ComposerGeometry.isSafeActionPoint(bar, bar.actionX, bar.actionY))
            putInt(KEY_EDITOR_LEFT, bar.editor.left)
            putInt(KEY_EDITOR_TOP, bar.editor.top)
            putInt(KEY_EDITOR_RIGHT, bar.editor.right)
            putInt(KEY_EDITOR_BOTTOM, bar.editor.bottom)
        }
    }

    /**
     * 回传输入框的**文案状态**（[ComposerState.Text]）。
     *
     * 为什么必须由服务进程回答：元宝 2.87.20 的对话输入框是 Compose 虚拟节点，
     * `resource-id=""`、View 树里没有原生 `EditText` —— 宿主两条路都读不到，
     * 于是 `editorHasText()` 恒 false、"文案写进去了也永远不点发送"。
     * 无障碍树里的 `EditText` 节点是**唯一**能拿到文案的地方（其 `text` 即 EditableText）。
     */
    private fun composerStateResult(roots: List<AccessibilityNodeInfo>): Bundle {
        val flat = flatNodes(roots)
        val editor = flat.firstOrNull { it.className.contains("EditText") }
        val state = ComposerState.resolveFromNodes(nodes = flat)
        val voiceToggle = flat.firstOrNull { node ->
            (node.text + node.description).contains(ComposerState.EMPTY_MARKER)
        }
        AndroidLog.d(
            TAG,
            "composerState state=$state editor=${editor?.box?.toShortString() ?: "null"} " +
                "text='${editor?.text.orEmpty().take(40)}' voiceToggle=${voiceToggle != null}",
        )
        return result(state == ComposerState.Text.HAS_TEXT).apply {
            putString(KEY_COMPOSER_STATE, state.name)
            putString(KEY_COMPOSER_TEXT, editorTextWithoutPlaceholder(editor?.text))
            putBoolean(KEY_COMPOSER_EDITOR_FOUND, editor != null)
        }
    }

    /**
     * 输入框节点文字，**去掉占位提示**。
     *
     * 元宝 2.93.0 起，空输入框的 `EditText.text` 会直接返回占位文案
     * 「发消息或按住说话...」。上游把它当正文后触发幂等闸：
     * `existing=11 字 ≠ 目标 41 字` → 判定"框里躺着别的文案" → 所有写入通道放弃，
     * 日志表现为 `fillText channel=none`，问元宝与写作两项因此整条不执行。
     */
    private fun editorTextWithoutPlaceholder(text: String?): String {
        val raw = text.orEmpty()
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""
        val isPlaceholder = EDITOR_HINT_MARKERS.any { marker ->
            trimmed == marker || trimmed.startsWith(marker)
        }
        return if (isPlaceholder) "" else raw
    }

    /**
     * 若当前输入栏停在**语音模式**（"按住 说话"），点一下"切换键盘"把它切回来。
     *
     * 返回 true 表示确实做了一次切换（调用方需要重新解析节点）。
     *
     * 为什么必须做：语音模式下输入栏没有 EditText，去点"输入框"必然失败；
     * 而旧代码失败后会盲点写死坐标，正好踩到左侧的切换键/右侧的更多 —— 这就是用户报的误触。
     * 这里点的目标是**切换键自己的真实 bounds**，不猜坐标。
     */
    private fun ensureKeyboardMode(roots: List<AccessibilityNodeInfo>): Boolean {
        val flat = flatNodes(roots)

        if (!ComposerGeometry.isVoiceMode(flat)) return false
        val toggle = ComposerGeometry.voiceToggle(flat)
        if (toggle == null) {
            AndroidLog.w(TAG, "voiceMode 检测到语音模式但找不到切换键 → 放弃（不盲点）")
            return false
        }
        val ok = tapAt(toggle.centerX.toFloat(), toggle.centerY.toFloat())
        AndroidLog.d(
            TAG,
            "voiceMode 切回键盘 toggle=[${toggle.left},${toggle.top}][${toggle.right},${toggle.bottom}] ok=$ok",
        )
        if (ok) SystemClock.sleep(600L)
        return ok
    }

    /**
     * 把所有 root 的节点摊平成 [ScreenScanner.DumpNode]（几何判定统一用这个形态）。
     *
     * 不回收节点：Android 13+ 的 `recycle` 已是空操作，而"同一节点回收两次"会抛异常
     * 直接**搞崩无障碍服务**（实测踩过，之后模块再也看不到任何窗口）。
     */
    private fun flatNodes(roots: List<AccessibilityNodeInfo>): List<ScreenScanner.DumpNode> {
        val nodes = mutableListOf<AccessibilityNodeInfo>()
        roots.forEach { collectAllNodesRelaxed(it, nodes) }
        return nodes.map { node ->
            val rect = Rect().also { node.getBoundsInScreen(it) }
            ScreenScanner.DumpNode(
                packageName = node.packageName?.toString().orEmpty(),
                text = nodeText(node),
                description = node.contentDescription?.toString().orEmpty(),
                resourceId = node.viewIdResourceName.orEmpty(),
                className = node.className?.toString().orEmpty(),
                bounds = Bounds(rect.left, rect.top, rect.right, rect.bottom),
                clickable = node.isClickable,
                scrollable = node.isScrollable,
            )
        }
    }

    /**
     * 统一的**节点点击**入口（`performAction(ACTION_CLICK)`）。
     *
     * 为什么要统一：`ACTION_CLICK` **不产生手势**，因此不经过 [tapAt]，调试时就是
     * 追踪盲区。实测踩过这个坑——底部导航栏的一次错误点击（把"模型"弹窗顶开了、
     * 整条流程崩掉）**完全没留下 TAPTRACE**，只能事后靠截图反推是哪一步干的。
     * 所有节点点击都走这里，保证每一次点击都可在日志里追溯。
     *
     * @param label 人类可读的来源标记，例如 `bottomTab#0`、`allTasks`。
     */
    private fun clickNode(
        label: String,
        node: AccessibilityNodeInfo?,
        bounds: Rect? = null,
    ): Boolean {
        if (node == null) return false
        val rect = bounds ?: Rect().also { node.getBoundsInScreen(it) }
        val ok = runCatching { node.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
            .getOrDefault(false)
        TapTrace.record(
            action = "nodeClick/$label",
            x = rect.centerX(),
            y = rect.centerY(),
            detail = "bounds=$rect ok=$ok",
        )
        TapTrace.pace()
        return ok
    }

    private fun tapAt(x: Float, y: Float): Boolean {
        if (!x.isFinite() || !y.isFinite()) return false
        // **落点必须在屏幕内**（1.1.6 新增的通用安全网）。
        //
        // 真机 `evidence/trace_e3_ask` 实证：模块打开过侧栏（抽屉）之后没有关掉，
        // 主内容窗口被整体推到屏幕**右侧之外**，无障碍树里的输入框因此报到
        // `[1108,2274][1871,2571]`（而屏幕只有 1272 宽），算出的发送落点是
        // **`x=2087` —— 完全在屏幕外**。
        //
        // 这种点击"点不到任何东西"，但它会**静默地连点 4 次**、每次都报
        // "派发成功"，把真正的原因（页面状态不对）掩盖成"发送没生效"，
        // 上层于是按"再试一次"处理，白白多跑几十秒。
        //
        // 屏幕外的点一律**不派发**，并留下能直接指认原因的日志/追踪。
        val (screenW, screenH) = displaySize()
        if (screenW > 0 && screenH > 0 && (x < 0f || y < 0f || x >= screenW || y >= screenH)) {
            AndroidLog.w(
                TAG,
                "拒绝派发屏幕外点击 xy=${x.toInt()},${y.toInt()} 屏幕=${screenW}x$screenH" +
                    "（多半是侧栏/抽屉没关，主窗口被推出屏幕 → 几何不可信）",
            )
            TapTrace.record("refused_offscreen", x.toInt(), y.toInt(), "screen=${screenW}x$screenH")
            return false
        }
        // 恢复"原样直接点"：之前新加的钳制/前台闸反而导致退出与误点（用户实测 ✗）。
        val px = x
        val py = y
        // A single-point path is accepted by the framework but several vendor
        // touch pipelines drop a zero-length stroke, which made every
        // accessibility tap silently do nothing. Give the stroke one pixel of
        // travel so it is delivered like a real finger down/up.
        val drift = if (py > 2f) -1f else 1f
        val path = Path().apply {
            moveTo(px, py)
            lineTo(px, py + drift)
        }
        val ok = dispatchGestureLogged(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0L, TAP_DURATION_MS))
                .build(),
            label = "tap@${px.toInt()},${py.toInt()}",
        )
        // 调试节拍：记下"这一下点在哪"，然后停 6~8 秒，让外部工具从容截屏核对。
        if (TapTrace.enabled || TapTrace.paceMs > 0L) {
            TapTrace.record(currentAction, px.toInt(), py.toInt(), "ok=$ok")
            TapTrace.pace()
        }
        return ok
    }

    /** 当前正在执行的 action 名，供 [TapTrace] 标记"这一点是哪一步点的"。 */
    @Volatile
    private var currentAction: String = ""

    /**
     * 屏幕的**真实**物理像素尺寸，用于"落点必须在屏幕内"的兜底校验。
     *
     * ## 为什么不能用 `resources.displayMetrics`
     *
     * 真机 `evidence/trace_e4_ask` 实测：无障碍服务里 `resources.displayMetrics`
     * 报的是 **1272×2603** —— 高度被扣掉了导航栏（2800−2603＝197px）。
     * 而元宝的输入栏/发送键本来就压在那一带：
     *
     * ```text
     * sendSafe 输入栏在屏幕外 editor=[70,2330][880,2645] 屏幕=1272x2603   ← 误拦！
     * Button  发送 ⬆            [1027,2456][1195,2624]                    ← 正常控件
     * ```
     *
     * 用 2603 当边界会把**完全正常的发送键**判成"屏幕外"而拒绝点击 ——
     * 这比不校验更糟（等于把功能直接关掉）。所以必须取 `getRealSize()`。
     *
     * 无障碍节点报的是**屏幕坐标**（含导航栏区域），因此边界也必须是整块屏幕。
     */
    /**
     * 点掉厂商安全中心弹的"是否保留无障碍权限"。
     *
     * ## 为什么必须由无障碍自己干
     *
     * 这个弹窗属于**另一个应用**（`com.oplus.safecenter` 等），
     * 而 [allRoots] 刻意只收"元宝 + 媒体/相机"的窗口（防跨应用误点）。
     * 所以这里**单独**遍历窗口，只挑白名单包名的安全中心，且只在这个窗口内找按钮。
     *
     * ## 安全约束（缺一不可）
     *
     * - 窗口包名必须在 [SystemGuardPolicy.GUARD_PACKAGES] 白名单里；
     * - 节点文字必须**精确等于**保留文案（且不含「关闭/停用/取消」）；
     * - 找不到就返回 `ok=false`，**绝不按坐标兜底**。
     *
     * 返回字段：`ok`=是否点成功、`guard_seen`=是否看到安全中心窗口、`text`=点到的文案。
     */
    private fun dismissGuardResult(): Bundle {
        var guardSeen = false
        var clicked = false
        var label = ""
        var candidates = 0
        for (window in runCatching { windows }.getOrNull().orEmpty()) {
            val root = runCatching { window.root }.getOrNull() ?: continue
            try {
                val packageName = root.packageName?.toString().orEmpty()
                if (!SystemGuardPolicy.isGuardPackage(packageName)) continue
                guardSeen = true
                val found = findSafeGuardButton(root)
                if (found != null) {
                    candidates++
                    label = found.first
                    val rect = Rect().also { found.second.getBoundsInScreen(it) }
                    // **必须用真实手势，不能用 performAction。**
                    //
                    // 真机实证（1.4.6）：对系统安全中心的按钮
                    // `performAction(ACTION_CLICK)` 返回 **true**，但界面毫无反应 ——
                    // 日志连打 8 次"已点保持开启"，弹窗一次都没关，元宝永远等不到前台。
                    // 这类系统应用不认无障碍的点击动作，只认真实触摸。
                    //
                    // 坐标取自**刚刚读到的节点 bounds**（不是写死的像素），
                    // 所以既没有分辨率问题，也不会点到「关闭无障碍」那一行。
                    clicked = dispatchTapAt(rect.centerX().toFloat(), rect.centerY().toFloat())
                    AndroidLog.w(
                        TAG,
                        "guard: 手势点“$label” xy=(${rect.centerX()},${rect.centerY()}) " +
                            "ok=$clicked package=$packageName",
                    )
                    if (clicked) break
                }
            } finally {
                root.recycleCompat()
            }
        }
        if (!guardSeen) {
            AndroidLog.d(TAG, "guard: 没有安全中心窗口 windows=${windowPackages()}")
        } else if (!clicked) {
            AndroidLog.w(
                TAG,
                "guard: 看到安全中心但没点到保留按钮（candidates=$candidates）" +
                    " → 需要人工处理 windows=${windowPackages()}",
            )
        }
        return Bundle().apply {
            putBoolean(KEY_OK, clicked)
            putBoolean(KEY_GUARD_SEEN, guardSeen)
            putString(KEY_TEXT, label)
        }
    }

    /**
     * 在绝对坐标上做一次短按手势。
     *
     * 用 `dispatchGesture` 而不是 `performAction`：系统安全中心这类应用
     * 不响应无障碍的点击动作（见 [dismissGuardResult] 里的实证）。
     */
    private fun dispatchTapAt(x: Float, y: Float): Boolean {
        val path = Path().apply {
            moveTo(x, y)
            lineTo(x + 1f, y + 1f)
        }
        return dispatchGestureLogged(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0L, 80L))
                .build(),
            label = "guard-tap $x,$y",
        )
    }

    /**
     * 在一个安全中心窗口里找"保留"按钮。
     *
     * 返回 `按钮文字 to 可点节点`；找不到返回 null。
     * 优先取**不可点节点的可点祖先** —— 各家这类按钮的文案节点通常自己也 `clickable=false`。
     */
    private fun findSafeGuardButton(root: AccessibilityNodeInfo): Pair<String, AccessibilityNodeInfo>? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var depth = 0
        while (queue.isNotEmpty() && depth < MAX_GUARD_SCAN) {
            val node = queue.removeFirst()
            depth++
            val text = listOf(
                node.text?.toString().orEmpty(),
                node.contentDescription?.toString().orEmpty(),
            ).firstOrNull { it.isNotBlank() }.orEmpty()
            if (SystemGuardPolicy.isSafeToClick(text)) {
                val target = if (node.isClickable) node else clickableAncestorOf(node)
                if (target != null) return text.trim() to target
            }
            for (index in 0 until node.childCount) {
                runCatching { node.getChild(index) }.getOrNull()?.let(queue::addLast)
            }
        }
        return null
    }

    /** 向上找第一个可点祖先（不包含自己）。 */
    private fun clickableAncestorOf(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current = node.parent
        var hops = 0
        while (current != null && hops < MAX_GUARD_ANCESTOR_HOPS) {
            if (current.isClickable && current.isEnabled) return current
            current = current.parent
            hops++
        }
        return null
    }

    /**
     * 截一帧并做 OCR，结果按"文字 + 屏幕矩形"返回。
     *
     * 用 **`AccessibilityService.takeScreenshot()`（API 30+）**，不是 MediaProjection：
     * 无障碍截图**不需要用户授权投屏**，也不需要前台服务，正好适合"偶尔补一次盲区"。
     * 参考 APK（汽水自动跳广告）用 MediaProjection 是因为它要长期连续取帧，
     * 我们这条链路一天可能都触发不了几次，没必要背那个授权负担。
     *
     * 系统对截图有 **~1 次/秒** 的频率限制，超频会返回 `ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT`；
     * 调用方那边也有冷却（见 [OcrFallbackPolicy]），两头都挡着。
     */
    private fun ocrResult(): Bundle {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return Bundle().apply {
                putBoolean(KEY_OK, false)
                putString(KEY_OCR_REASON, "需要 Android 11+（takeScreenshot）")
            }
        }
        val screenshots = CountDownLatch(1)
        var bitmap: android.graphics.Bitmap? = null
        var failure = ""
        runCatching {
            takeScreenshot(
                android.view.Display.DEFAULT_DISPLAY,
                mainExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        bitmap = runCatching {
                            val hardware = android.graphics.Bitmap.wrapHardwareBuffer(
                                screenshot.hardwareBuffer,
                                screenshot.colorSpace,
                            )
                            // ML Kit 只吃软件位图，硬件位图要先拷成 ARGB_8888。
                            hardware?.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
                        }.getOrNull()
                        runCatching { screenshot.hardwareBuffer.close() }
                        screenshots.countDown()
                    }

                    override fun onFailure(errorCode: Int) {
                        failure = screenshotErrorName(errorCode)
                        screenshots.countDown()
                    }
                },
            )
        }.onFailure {
            failure = "调用失败：${it.message}"
            screenshots.countDown()
        }
        if (!screenshots.await(SCREENSHOT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            AndroidLog.w(TAG, "OCR 截图超时")
            return Bundle().apply {
                putBoolean(KEY_OK, false)
                putString(KEY_OCR_REASON, "截图超时")
            }
        }
        val frame = bitmap
        if (frame == null) {
            AndroidLog.w(TAG, "OCR 截图失败 $failure")
            return Bundle().apply {
                putBoolean(KEY_OK, false)
                putString(KEY_OCR_REASON, failure.ifBlank { "截图失败" })
            }
        }
        val items = runCatching { ocrEngine.recognize(frame) }.getOrDefault(emptyList())
        frame.recycle()
        lastOcrAtMs = SystemClock.uptimeMillis()
        val (screenWidth, screenHeight) = displaySize()
        val payload = ArrayList<Bundle>(items.size)
        items.forEach { item ->
            val clamped = item.clampedTo(screenWidth, screenHeight)
            payload += Bundle().apply {
                putString(KEY_TEXT, clamped.text)
                putInt(KEY_X, clamped.left)
                putInt(KEY_Y, clamped.top)
                putInt("right", clamped.right)
                putInt("bottom", clamped.bottom)
            }
        }
        AndroidLog.d(TAG, "OCR 识别到 ${items.size} 行（屏幕 ${screenWidth}x$screenHeight）")
        return Bundle().apply {
            putBoolean(KEY_OK, true)
            putParcelableArrayList(KEY_OCR_ITEMS, payload)
        }
    }

    /** 把系统的截图错误码翻成人话，否则日志里只有一个数字。 */
    private fun screenshotErrorName(code: Int): String = when (code) {
        ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR -> "内部错误"
        ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS -> "没有无障碍访问权限"
        ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> "距上次截图太近（系统限制约 1 次/秒）"
        ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY -> "无效的 display"
        ERROR_TAKE_SCREENSHOT_SECURE_WINDOW -> "当前窗口有 FLAG_SECURE，禁止截图"
        else -> "未知错误 code=$code"
    }

    private fun displaySize(): Pair<Int, Int> = runCatching {
        val manager = getSystemService(WINDOW_SERVICE) as? WindowManager
        val display = manager?.defaultDisplay
        if (display != null) {
            val point = Point()
            @Suppress("DEPRECATION")
            display.getRealSize(point)
            if (point.x > 0 && point.y > 0) return@runCatching point.x to point.y
        }
        val metrics = resources.displayMetrics
        metrics.widthPixels to metrics.heightPixels
    }.getOrDefault(0 to 0)

    /**
     * 当前屏幕尺度（全屏真实像素 + dpi），所有像素判据的唯一换算入口。
     *
     * 必须用 [displaySize]（`getRealSize()`）而不是 `resources.displayMetrics`：
     * 后者是**可用窗口**（实测 1272×2603，屏幕 1272×2800），拿它当基准会让
     * "比例 → 绝对坐标"整体偏上/偏下，底部按钮尤其明显。
     */
    private fun screenScale(): ScreenScale {
        val (width, height) = displaySize()
        return ScreenScale.of(width, height, resources.displayMetrics.densityDpi)
    }

    /**
     * 会用到"底部输入栏"的动作集合。
     *
     * 这些动作在入口处统一做一次"语音模式 → 键盘模式"自愈（见 `execute`）。
     * 漏掉任何一个，那条通道就会在语音模式下静默失效。
     */
    private val COMPOSER_ACTIONS = setOf(
        ACTION_PASTE_EDITOR,
        ACTION_SET_TEXT,
        ACTION_CLEAR_COMPOSER,
        ACTION_FOCUS_EDITOR,
        ACTION_BOTTOM_EDITOR_ROW,
        ACTION_TAP_EDITOR_CENTER,
        ACTION_CLICK_NEAR_EDITOR,
        ACTION_TAP_SEND_SAFE,
        ACTION_CLICK_SEND_RIGHT_OF_EDITOR,
        ACTION_COMPOSER_GEOMETRY,
        ACTION_TAP_CHAT_SEND,
        ACTION_TAP_WRITING_SEND,
    )

    private fun dispatchGestureLogged(gesture: GestureDescription, label: String): Boolean {
        val accepted = runCatching {
            dispatchGesture(
                gesture,
                object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        AndroidLog.d(TAG, "gesture completed $label")
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        AndroidLog.w(TAG, "gesture cancelled by the system $label")
                    }
                },
                main,
            )
        }.getOrDefault(false)
        if (!accepted) AndroidLog.w(TAG, "gesture rejected $label")
        return accepted
    }

    private fun collectText(node: AccessibilityNodeInfo, output: StringBuilder) {
        if (output.length >= MAX_TEXT) return
        if (node.isVisibleToUser) {
            node.text?.toString()?.takeIf(String::isNotBlank)?.let { output.append(it).append('\n') }
            node.contentDescription?.toString()?.takeIf(String::isNotBlank)?.let {
                output.append(it).append('\n')
            }
        }
        for (index in 0 until node.childCount) {
            if (output.length >= MAX_TEXT) return
            val child = runCatching { node.getChild(index) }.getOrNull() ?: continue
            try {
                collectText(child, output)
            } finally {
                child.recycleCompat()
            }
        }
    }

    private fun nodeTreeSignature(root: AccessibilityNodeInfo): String {
        val raw = buildString {
            append(root.packageName?.toString().orEmpty()).append('|')
            collectNodeSignature(root, this)
        }
        return Integer.toHexString(raw.hashCode())
    }

    private fun hasMediaConfirmNode(node: AccessibilityNodeInfo): Boolean {
        if (node.isVisibleToUser && node.isEnabled) {
            val id = node.viewIdResourceName.orEmpty().lowercase()
            val text = nodeText(node).trim().lowercase()
            if (MEDIA_CONFIRM_ID_HINTS.any(id::contains) ||
                node.isClickable && MEDIA_CONFIRM_TEXT.any { text.equals(it, ignoreCase = true) }
            ) return true
        }
        for (index in 0 until node.childCount) {
            val child = runCatching { node.getChild(index) }.getOrNull() ?: continue
            try {
                if (hasMediaConfirmNode(child)) return true
            } finally {
                child.recycleCompat()
            }
        }
        return false
    }

    private fun collectNodeSignature(node: AccessibilityNodeInfo, output: StringBuilder) {
        if (output.length >= MAX_SIGNATURE_TEXT) return
        if (node.isVisibleToUser) {
            val bounds = Rect().also(node::getBoundsInScreen)
            output.append(node.className?.toString().orEmpty()).append('|')
                .append(node.viewIdResourceName.orEmpty()).append('|')
                .append(nodeText(node)).append('|')
                .append(bounds.left).append(',').append(bounds.top).append(',')
                .append(bounds.right).append(',').append(bounds.bottom).append('|')
                .append(node.isClickable).append('|').append(node.isSelected).append(';')
        }
        for (index in 0 until node.childCount) {
            if (output.length >= MAX_SIGNATURE_TEXT) return
            val child = runCatching { node.getChild(index) }.getOrNull() ?: continue
            try {
                collectNodeSignature(child, output)
            } finally {
                child.recycleCompat()
            }
        }
    }

    private fun mediaTapResult(
        dispatched: Boolean,
        packageName: String,
        candidate: MediaCandidateDescriptor? = null,
        candidateCount: Int,
    ): Bundle = result(dispatched).apply {
        putString(KEY_PACKAGE, packageName)
        putString(KEY_SELECTED_KEY, candidate?.key.orEmpty())
        putInt(KEY_X, candidate?.centerX ?: -1)
        putInt(KEY_Y, candidate?.centerY ?: -1)
        putInt(KEY_CANDIDATE_COUNT, candidateCount)
    }

    private fun nodeText(node: AccessibilityNodeInfo): String =
        listOfNotNull(node.text?.toString(), node.contentDescription?.toString())
            .filter(String::isNotBlank)
            .joinToString(" ")

    /**
     * 当前屏幕的节点树（`<hierarchy>` 形态，字段名与 `uiautomator dump` 完全一致）。
     *
     * **1.1.4 起默认由无障碍树直接生成**，不再跑 `uiautomator dump`。真机实证的两个理由：
     *
     * 1. **它会踢掉我们自己的无障碍服务**（决定性证据，logcat）：
     *    ```
     *    21:52:57.071 D/AndroidRuntime: Calling main entry com.android.commands.uiautomator.Launcher
     *    21:52:57.082 D/YuanbaoDaily: accessibility service destroyed
     *    21:52:58.148 D/YuanbaoDaily: accessibility service connected
     *    ```
     *    这台 ColorOS 上 `uiautomator` 一启动，系统就把第三方无障碍服务销毁，
     *    约 1 秒后才重连。这一秒里模块对整棵树的读取全是 `UNKNOWN` →
     *    `editorHasText()` 拿到 null → 掉进宿主通道（对 Compose 输入框无效）→
     *    把**空的**输入栏判成"有文案" → 去点右侧那颗按钮，而空框时那颗正是
     *    **"切换语音"** ✗。这就是用户报的"找不到输入框 + 误触切换语音"的根因。
     *
     * 2. 慢：实测一次 `uiautomator dump` ~2.3 秒，还要拉一个 uid=0 的 `app_process`。
     *
     * 只有无障碍树完全拿不到窗口（例如服务刚连上、窗口还没上报）时，才退回
     * `uiautomator` 兜底 —— 兜底路径保留原样，避免"一条路都不通"。
     */
    private fun dumpResult(): Bundle {
        accessibilityDump()?.let { raw ->
            AndroidLog.d(TAG, "dump via a11y uid=${Process.myUid()} chars=${raw.length}")
            return Bundle().apply {
                putBoolean(KEY_OK, true)
                putString("output", raw)
            }
        }
        AndroidLog.w(
            TAG,
            "accessibilityDump 为空（windows=${runCatching { windows }.getOrNull()?.size}）→ 退回 uiautomator",
        )
        return dumpViaUiautomator()
    }

    /**
     * 把当前无障碍树序列化成 `uiautomator dump` 的 `<hierarchy>` 形态。
     *
     * 属性名/取值与原 dump 保持一致（`package` / `text` / `content-desc` /
     * `resource-id` / `class` / `bounds` / `clickable` / `scrollable`），
     * 这样 [ScreenScanner.parseDump] 不需要任何改动。
     */
    private fun accessibilityDump(): String? {
        val snapshot = runCatching { windows }.getOrNull().orEmpty()
        if (snapshot.isEmpty()) return null
        val xml = StringBuilder("<hierarchy rotation=\"0\">")
        var emitted = 0
        for (window in snapshot) {
            val root = runCatching { window.root }.getOrNull() ?: continue
            emitted += appendNodeXml(xml, root, depth = 1)
        }
        if (emitted == 0) return null
        xml.append("</hierarchy>")
        return xml.toString()
    }

    /** 递归写一个节点及其子树；返回写入的节点数。 */
    private fun appendNodeXml(xml: StringBuilder, node: AccessibilityNodeInfo, depth: Int): Int {
        if (depth > MAX_DUMP_DEPTH || xml.length > MAX_DUMP_CHARS) return 0
        val rect = Rect()
        runCatching { node.getBoundsInScreen(rect) }
        if (rect.right <= rect.left || rect.bottom <= rect.top) return 0
        xml.append("<node")
        xml.append(" index=\"").append(depth).append('"')
        appendAttr(xml, "text", node.text?.toString().orEmpty())
        appendAttr(xml, "resource-id", node.viewIdResourceName.orEmpty())
        appendAttr(xml, "class", node.className?.toString().orEmpty())
        appendAttr(xml, "package", node.packageName?.toString().orEmpty())
        appendAttr(xml, "content-desc", node.contentDescription?.toString().orEmpty())
        appendAttr(xml, "bounds", "[${rect.left},${rect.top}][${rect.right},${rect.bottom}]")
        xml.append(" clickable=\"").append(node.isClickable).append('"')
        xml.append(" enabled=\"").append(node.isEnabled).append('"')
        xml.append(" focusable=\"").append(node.isFocusable).append('"')
        xml.append(" focused=\"").append(node.isFocused).append('"')
        xml.append(" scrollable=\"").append(node.isScrollable).append('"')
        xml.append('>')
        var count = 1
        val childCount = runCatching { node.childCount }.getOrDefault(0)
        for (index in 0 until childCount) {
            val child = runCatching { node.getChild(index) }.getOrNull() ?: continue
            count += appendNodeXml(xml, child, depth + 1)
        }
        xml.append("</node>")
        return count
    }

    /** 写 `name="值"`，并把 XML 元字符转义（元宝的文案里有 `&`/引号）。 */
    private fun appendAttr(xml: StringBuilder, name: String, value: String) {
        if (value.isEmpty()) return
        xml.append(' ').append(name).append("=\"")
        for (char in value) {
            when (char) {
                '&' -> xml.append("&amp;")
                '<' -> xml.append("&lt;")
                '>' -> xml.append("&gt;")
                '"' -> xml.append("&quot;")
                '\n' -> xml.append("&#10;")
                '\r' -> xml.append("&#13;")
                else -> xml.append(char)
            }
        }
        xml.append('"')
    }

    /**
     * 兜底：在**无障碍服务进程**内执行一次 uiautomator dump。
     *
     * 为什么必须放在这里（而不是调用方进程）：KernelSU 只授权了模块自己的 uid，
     * 宿主进程（元宝）的 `su` 会静默挂起，dump 永远拿不到结果。服务进程的 uid
     * 是被授权的，所以由它代跑，Bundle 里只回传文本。
     *
     * **注意副作用**：这条路径会踢掉无障碍服务（见 [dumpResult]），只在
     * [accessibilityDump] 完全失败时才走。
     */
    private fun dumpViaUiautomator(): Bundle {
        val shell = runCatching { RootInputController.perform(Bundle().apply { putString(KEY_ACTION, "dump") }) }
            .getOrNull()
        val raw = shell?.getString("output").orEmpty()
        AndroidLog.d(
            "YuanbaoDaily",
            "dump via su uid=${Process.myUid()} chars=${raw.length} ok=${raw.contains("<hierarchy")}",
        )
        return Bundle().apply {
            putBoolean(KEY_OK, raw.contains("<hierarchy"))
            putInt("exit", shell?.getInt("exit", Int.MIN_VALUE) ?: Int.MIN_VALUE)
            if (raw.isNotEmpty()) putString("output", raw)
        }
    }

    /**
     * 这一次 action 该给主线程留多少时间。
     *
     * 真机踩坑（`evidence/trace_d3_fill_send`）：慢速调试打开后
     * （`TAP_PACE_MS=7000`），`tapAt` 会在**主线程里**按节拍睡 7 秒。而 `onMain`
     * 原本硬编码 4 秒门限 → latch 超时 → `execute` 返回 `result(false)`。
     * 于是出现"手势明明已经派发（`TAPTRACE ... ok=true`），binder 却回 `ok=false`"，
     * 宿主据此认为发送失败，**又点了一次同一个坐标** ✗ —— 一次点击变两次，
     * 第二次正好落在已经翻转语义的按钮上（就是误触）。
     *
     * 生产环境 `paceMs = 0`，退化成常数 [ON_MAIN_TIMEOUT_MS]，行为不变；
     * 调试节拍下一次 action 最多派发两次点击，所以按两倍节拍放宽。
     */
    private fun onMainTimeout(): Long = ON_MAIN_TIMEOUT_MS + TapTrace.paceMs * 2

    private fun <T> onMain(block: () -> T): T? = onMain(onMainTimeout(), block)

    private fun <T> onMain(timeoutMs: Long, block: () -> T): T? {
        if (Looper.myLooper() == Looper.getMainLooper()) return runCatching(block).getOrNull()
        val latch = CountDownLatch(1)
        var output: T? = null
        main.post {
            output = runCatching(block).getOrNull()
            latch.countDown()
        }
        latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        return output
    }

    /**
     * **不做任何回收**（故意的）。
     *
     * Android 13+ 的 [AccessibilityNodeInfo.recycle] 已是空操作；而调用它
     * 在"同一节点被回收两次"时会抛异常 → **无障碍服务崩溃** ✗ → 之后模块
     * 看不到任何窗口（找不到输入框/发送键 ✗，实测踩过）。
     * 这里保持空实现，从根上消除这一类崩溃。
     */
    @Suppress("UNUSED_PARAMETER", "DEPRECATION")
    private fun AccessibilityNodeInfo.recycleCompat() = Unit

    companion object {
        private const val TARGET_PACKAGE = "com.tencent.hunyuan.app.chat"
        private const val TAG = "YuanbaoDaily"
        private const val MAX_TEXT = 50_000
        private const val MAX_SIGNATURE_TEXT = 100_000
        /**
         * 各输入框的提示语。元宝 2.93.0 起这些提示会直接出现在 `EditText.text` 里，
         * 不排掉就会被上游的幂等闸当成"用户已经写了别的文案"：
         * `existing=9 字 ≠ 目标 47 字` → 放弃所有写入通道 → `fillText channel=none`。
         */
        private val EDITOR_HINT_MARKERS = listOf(
            "发消息或按住说话",
            "按住说话",
            "说点什么",
            "请输入你要写的主题",
            "请输入主题",
            "描述你想怎么改",
            "请输入你的问题",
        )
        /**
         * `tapNodeByLabel` 的最小可用边长（**参考机型像素**，按 dp 换算）。
         *
         * dump 里大量节点上报 `[0,0][0,0]`（未布局的 Compose 占位），
         * 点它们等于点在屏幕左上角，必须按尺寸筛掉。32px @480dpi ≈ 10.7dp，
         * 低于任何真实可点控件，又能把 0×0 / 1×1 这类占位节点排除干净。
         */
        private const val MIN_TAPTABLE_SIDE_REF = 32

        /**
         * 相册缩略图的尺寸判据（**参考机型像素 1272 宽**，按屏宽等比换算）。
         *
         * - [MEDIA_TILE_MIN_SIDE_REF] ≈ 7.5% 屏宽：低于它的不是缩略图（图标/文字）；
         * - [MEDIA_GRID_MIN_SIDE_REF] ≈ 11.8% 屏宽：照片格子下限；
         * - [MEDIA_GRID_MAX_SIDE_REF] ≈ 71% 屏宽：上限，挡住"整块相册容器"。
         *
         * 写死绝对值时：1080 宽上格子上限 900 相对偏大（半屏容器可能被当成格子），
         * 1440 宽上缩略图普遍 ~460px、仍 OK，但 4 列布局（~330px）在 1440 上不受影响；
         * 真正会出错的是**平板/折叠屏**（1600+ 宽、格子 500~600px，且整块容器 1100px
         * 会被 900 上限误判）。等比换算后在每种宽度上都保持同一套相对判据。
         */
        private const val MEDIA_TILE_MIN_SIDE_REF = 96
        private const val MEDIA_GRID_MIN_SIDE_REF = 150
        private const val MEDIA_GRID_MAX_SIDE_REF = 900

        /**
         * 底部导航单项的尺寸/成行判据（**参考机型像素 1272 宽**，按屏宽等比换算）。
         *
         * 老实现写死 `width in 60..600`、`height in 60..500`、行距 60、去重间距 80。
         * 换到 1080 宽（单项约 127px 宽）还能凑合；换到**折叠屏展开/平板**（1600+ 宽，
         * 单项 200~260px、行高 220px）时，行距 60 小于行高，同一行的项会被拆成多行 →
         * "第 index 个"直接错位 → 点到隔壁标签。等比换算后这类错位不再出现。
         */
        private const val BOTTOM_TAB_MIN_SIDE_REF = 60
        private const val BOTTOM_TAB_MAX_WIDTH_REF = 600
        private const val BOTTOM_TAB_MAX_HEIGHT_REF = 500
        private const val BOTTOM_TAB_ROW_TOLERANCE_REF = 60
        private const val BOTTOM_TAB_DUPLICATE_GAP_REF = 80

        /** `sendRightOfEditor` 兜底通道里"像发送键"的最大边长（参考 320px @1272）。 */
        private const val SEND_NEAR_EDITOR_MAX_SIDE_REF = 320

        /** `clickBottomButton`（相册"确定/完成"）的尺寸窗口（参考机型像素，按屏宽等比）。 */
        private const val CLICK_BOTTOM_MIN_WIDTH_REF = 80
        private const val CLICK_BOTTOM_MIN_HEIGHT_REF = 60
        private const val CLICK_BOTTOM_MAX_SIDE_REF = 700

        /** `tapBottomRightIcon`（底栏最右"全部任务"圆图标）的尺寸窗口（参考 60..260 @1272）。 */
        private const val BOTTOM_RIGHT_ICON_MIN_SIDE_REF = 60
        private const val BOTTOM_RIGHT_ICON_MAX_SIDE_REF = 260
        private const val TAP_DURATION_MS = 110L
        private const val LINK_PUMP_INTERVAL_MS = 10_000L

        private const val KEY_ACTION = "action"
        private const val KEY_ALIASES = "aliases"
        private const val KEY_ALLOW_CONTAINS = "allow_contains"
        private const val KEY_BLOCKED_KEYS = "blocked_keys"
        private const val KEY_SELECTED_KEY = "selected_key"
        private const val KEY_IDS = "ids"
        private const val KEY_VALUE = "value"
        private const val KEY_MARKER = "marker"
        private const val KEY_X = "x"
        private const val KEY_Y = "y"
        /** 输入栏几何回传：发送键绝对坐标（区别于 KEY_X/KEY_Y 的编辑框中心）。 */
        private const val KEY_SEND_X = "send_x"
        private const val KEY_SEND_Y = "send_y"
        /** 发送落点是否通过了安全校验（`isSafeActionPoint`）。 */
        private const val KEY_SEND_SAFE = "send_safe"
        private const val KEY_EDITOR_LEFT = "editor_left"
        private const val KEY_EDITOR_TOP = "editor_top"
        private const val KEY_EDITOR_RIGHT = "editor_right"
        private const val KEY_EDITOR_BOTTOM = "editor_bottom"
        private const val KEY_CANDIDATE_COUNT = "candidate_count"
        private const val KEY_X_FRACTION = "x_fraction"
        private const val KEY_Y_FRACTION = "y_fraction"
        private const val KEY_OK = "ok"
        private const val KEY_TEXT = "text"
        private const val KEY_INDEX = "index"
        private const val KEY_BOTTOM_ONLY = "bottom_only"
        private const val KEY_FROM_FRACTION = "from_fraction"
        private const val KEY_TO_FRACTION = "to_fraction"
        private const val KEY_PACKAGE = "package"
        private const val KEY_SIGNATURE = "signature"
        private const val KEY_CONFIRM_VISIBLE = "confirm_visible"

        /** Public so the bridge reads the same key. */
        const val KEY_PICKER_VISIBLE = "picker_visible"
        const val KEY_WINDOWS = "windows"
        const val KEY_ACTIVITY = "activity"

        const val ACTION_READ_TEXT = "read_text"
        const val ACTION_DUMP = "dump"

        /** 屏幕真实像素尺寸（含导航栏），见 `execute` 里的注释。 */
        const val ACTION_SCREEN_SIZE = "screen_size"

        /** OCR 兜底：截一帧并识别其中的文字，返回 [KEY_OCR_ITEMS]。 */
        const val ACTION_OCR_TEXT = "ocr_text"

        /** 点掉厂商安全中心的"无障碍权限"提示（只点「保持开启」这类保留按钮）。 */
        const val ACTION_DISMISS_GUARD = "dismiss_guard"
        const val KEY_GUARD_SEEN = "guard_seen"
        const val KEY_OCR_ITEMS = "ocr_items"
        const val KEY_OCR_REASON = "ocr_reason"

        /** 等系统回调截图的超时。正常几十毫秒就回来，超时说明系统侧卡住了。 */
        private const val SCREENSHOT_TIMEOUT_MS = 3_000L

        /** 扫描安全中心弹窗时最多访问多少节点（防止异常树把线程拖住）。 */
        private const val MAX_GUARD_SCAN = 400

        /** 从按钮文案往上找可点祖先，最多跳几层。 */
        private const val MAX_GUARD_ANCESTOR_HOPS = 6
        const val KEY_SCREEN_WIDTH = "screen_width"
        const val KEY_SCREEN_HEIGHT = "screen_height"
        const val ACTION_STATUS = "status"
        const val ACTION_CLICK_TEXT = "click_text"
        const val ACTION_CLICK_TEXT_NEXT = "click_text_next"
        const val ACTION_SET_TEXT = "set_text"
        /** 清空输入框（见 `execute` 里 ACTION_CLEAR_COMPOSER 的说明）。 */
        const val ACTION_CLEAR_COMPOSER = "clear_composer"
        const val ACTION_CLICK_ID = "click_id"
        const val ACTION_CLICK_LATEST_MEDIA = "click_latest_media"
        const val ACTION_TAP = "tap"
        const val ACTION_CLICK_POINT = "click_point"
        const val ACTION_CLICK_NEAR_EDITOR = "click_near_editor"
        const val ACTION_FOCUS_EDITOR = "focus_editor"
        const val ACTION_CLICK_BOTTOM = "click_bottom"
        const val ACTION_PASTE_EDITOR = "paste_editor"
        const val ACTION_NODE_BOUNDS = "node_bounds"
        const val ACTION_TAB_TAP = "tab_tap"
        const val ACTION_DRAG_HORIZONTAL = "drag_horizontal"
        const val ACTION_TAP_BOTTOM_RIGHT_ICON = "tap_bottom_right_icon"
        const val ACTION_BOTTOM_EDITOR_ROW = "bottom_editor_row"
        const val ACTION_TAP_CHAT_SEND = "tap_chat_send"
        const val ACTION_TAP_WRITING_SEND = "tap_writing_send"
        const val ACTION_CLICK_SEND_RIGHT_OF_EDITOR = "click_send_right_of_editor"
        const val ACTION_TAP_EDITOR_CENTER = "tap_editor_center"
        const val ACTION_TAP_TEXT = "tap_text"
        const val ACTION_SCROLL_FORWARD = "scroll_forward"
        const val ACTION_SCROLL_BACKWARD = "scroll_backward"
        const val ACTION_BACK = "back"
        /** 按**真实节点几何**点发送键，并在派发前做安全校验（不通过就放弃）。 */
        const val ACTION_TAP_SEND_SAFE = "tap_send_safe"
        /** 回传输入栏真实几何（编辑框 + 发送键），取代宿主侧的写死比例坐标。 */
        const val ACTION_COMPOSER_GEOMETRY = "composer_geometry"
        /**
         * 按**文字或 content-desc** 找节点，用它自己的真实 bounds 中心点一下。
         *
         * 与 `ACTION_CLICK_TEXT` 的区别：那个优先走无障碍 `ACTION_CLICK`（对不可点的
         * 装饰节点无效），本动作一律取**节点真实屏幕坐标**再派发触摸，并且会优先
         * 选"可点的祖先 + 最小面积"的匹配，避免点到整行大容器上。
         *
         * 福利中心入口（`ic_navigation_show` / `content-desc='抽屉页入口'`）只有
         * content-desc、没有 text，且内层节点 `clickable=false`，正好需要这条通道。
         */
        const val ACTION_TAP_NODE_BY_LABEL = "tap_node_by_label"

        /** 配置本进程（无障碍服务）的点击节拍，见 [TapTrace]。 */
        const val ACTION_SET_TAP_PACE = "set_tap_pace"
        private const val KEY_TAP_PACE_MS = "tap_pace_ms"

        /**
         * 回传输入框的**文案状态**（空 / 有文案 / 语音模式），见 [ComposerState]。
         *
         * 必须由**服务进程**回答：宿主进程读不到元宝的 Compose 输入框
         * （无 `resource-id`、View 树里没有原生 `EditText`），只有无障碍树拿得到
         * `EditText` 节点及其 `text`。
         */
        const val ACTION_COMPOSER_STATE = "composer_state"

        /** [ACTION_COMPOSER_STATE] 的返回字段。 */
        const val KEY_COMPOSER_STATE = "composer_state"
        const val KEY_COMPOSER_TEXT = "composer_text"
        const val KEY_COMPOSER_EDITOR_FOUND = "composer_editor_found"

        /**
         * 无障碍树序列化成 `<hierarchy>` 时的规模上限。
         *
         * 真机一次 dump 的原文是 33~40KB / 90~110 个节点，给到 20 倍余量足够；
         * 上限的作用是防止畸形节点树（深链循环/超大列表）把 Bundle 撑爆
         * （binder 事务 1MB 硬上限，超了会直接抛 TransactionTooLargeException）。
         */
        private const val MAX_DUMP_DEPTH = 60
        private const val MAX_DUMP_CHARS = 900_000

        /** 输入框右侧的"语音切换"标记：挑发送键时必须排除它们（见 clickButtonNearEditor）。 */
        private val VOICE_TOGGLE_MARKERS = listOf("切换语音", "切换键盘")

        private val MEDIA_ID_HINTS = listOf(            "thumbnail",
            "thumb",
            "media_item",
            "photo_item",
            "image_item",
            "timeline_item",
        )

        private val MEDIA_EXCLUDED_WORDS = listOf(
            "视频",
            "返回",
            "取消",
            "完成",
            "添加",
            "确定",
            "选择",
            "预览",
            "camera",
            "video",
        )

        private val MEDIA_CAMERA_WORDS = listOf("相机", "拍照", "拍摄", "camera")

        /** Labels that only a photo picker shows. */
        private val PICKER_WINDOW_MARKERS = listOf(
            "本地相册",
            "ip角色",
            "最近项目",
            "相机胶卷",
            "选择照片",
            "所有照片",
            "选择项目",
        )

        /** Broader picker wording, used only for the active window. */
        private val MEDIA_PICKER_WORDS = listOf(
            "本地相册",
            "相册",
            "照片",
            "所有照片",
            "选择照片",
            "最近项目",
            "相机胶卷",
            "选择项目",
            "gallery",
            "photos",
        )

        private val MEDIA_CONFIRM_ID_HINTS = listOf(
            "action_preview_selected",
            "ps_tv_complete",
            "ps_complete_select",
            "button_add",
            "button_confirm",
            "confirm_button",
            "menu_done",
        )

        private val MEDIA_CONFIRM_TEXT = listOf(
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

        private val MEDIA_FILENAME_REGEX = Regex(
            "[^\\s/\\\\]+\\.(png|jpe?g|webp|heic|gif)$",
            RegexOption.IGNORE_CASE,
        )

        private val MEDIA_ORDINAL_REGEX = Regex("第\\s*(\\d+)\\s*项")

        private data class TextCandidate(
            val node: AccessibilityNodeInfo,
            val bounds: Rect,
            val key: String,
        )

        @Volatile
        private var instance: AutomationAccessibilityService? = null

        fun isConnected(): Boolean = instance != null

        /**
         * 无障碍服务被"自己人"踢下线后，最多等它多久重连。
         *
         * 真机踩坑（1.1.4，决定性证据）：模块自己的 `ui dump` 走的是
         * `su -c uiautomator dump`，而在这台 ColorOS 上**一启动 uiautomator，
         * 系统就会把我们自己的无障碍服务销毁**（logcat 实测：
         * `Calling main entry com.android.commands.uiautomator.Launcher` 之后 11ms
         * 就是 `accessibility service destroyed`，uiautomator 退出后 1.0~1.1 秒
         * `accessibility service connected`）。
         *
         * 这一秒里 `instance == null`，所有指令都返回 `ok=false`。上层
         * `editorHasText()` 于是拿到 null → 掉进两条**对 Compose 输入框完全无效**的
         * 宿主通道 → 把**空的**输入栏判成"有文案" → 去点右侧那颗按钮，
         * 而空框时那颗正是 **"切换语音"** ✗ —— 这就是用户报的误触。
         *
         * 所以这里必须**等**，不能立刻放弃。2.6 秒覆盖实测的 1.0~1.1 秒重连窗口，
         * 留一倍余量（低端机/高负载下会更慢）。
         */
        private const val SERVICE_READY_WAIT_MS = 2_600L

        /**
         * `onMain` 的基线超时（毫秒）。
         *
         * 主线程上一件 action 的硬上限；生产节拍下就是这个值。
         * 调试节拍会在此基础上再加两倍 `TAP_PACE_MS`（见 `onMainTimeout`）。
         */
        private const val ON_MAIN_TIMEOUT_MS = 4_000L

        fun perform(input: Bundle): Bundle {
            instance?.let { return it.execute(input) }
            if (awaitInstance(SERVICE_READY_WAIT_MS)) {
                instance?.let { return it.execute(input) }
            }
            AndroidLog.w(
                TAG,
                "perform 无障碍服务未就绪（等了 ${SERVICE_READY_WAIT_MS}ms）" +
                    "action=${input.getString(KEY_ACTION)}",
            )
            return result(false)
        }

        /** 轮询等待服务重连；返回是否已就绪。 */
        private fun awaitInstance(timeoutMs: Long): Boolean {
            val deadline = SystemClock.uptimeMillis() + timeoutMs
            while (instance == null && SystemClock.uptimeMillis() < deadline) {
                SystemClock.sleep(50L)
            }
            return instance != null
        }

        private fun result(ok: Boolean): Bundle = Bundle().apply { putBoolean(KEY_OK, ok) }

        private fun result(ok: Boolean, x: Int, y: Int): Bundle = Bundle().apply {
            putBoolean(KEY_OK, ok)
            putInt(KEY_X, x)
            putInt(KEY_Y, y)
        }
    }
}
