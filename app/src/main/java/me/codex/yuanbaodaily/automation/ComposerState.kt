package me.codex.yuanbaodaily.automation

/**
 * 「输入框里到底有没有文案」的**结构判定**（纯数据、无 Android API，可被纯 JVM 单测覆盖）。
 *
 * ## 为什么必须有这一层
 *
 * 用户反馈（1.1.2/1.1.3 仍复现）：**"还是找不到问元宝对话页面的输入框"**。
 * 真机 1272×2800 / 元宝 2.87.20 取证后确认，元宝对话栏的输入框是一个
 * **Compose 虚拟节点**，它同时骗过了两条传统读取路径：
 *
 * ```text
 * <node NAF="true" text="" resource-id="" class="android.widget.EditText"
 *       package="com.tencent.hunyuan.app.chat" focused="true"
 *       bounds="[210,2435][922,2645]" hint=""/>
 * ```
 *
 * 1. `resource-id=""` —— 2.87.20 **没有** `edConversationInput` 这个 id，
 *    `findViewById(id)` 永远返回 null；
 * 2. View 树里**没有真正的 `EditText`**（承载它的是 `AndroidComposeView`），
 *    所以 `is EditText` 的递归查找也永远返回 null。
 *
 * 结果：宿主侧 `editorHasText()` **恒为 false** ⇒
 * 「对话页等待文案就绪 ready=false → 不点发送」——**文案写进去了也永远发不出去**。
 *
 * ## 判据（全部来自真机 dump）
 *
 * | 证据 | 含义 |
 * | --- | --- |
 * | 同行有 `切换语音` / `按住说话` | 输入框**空**（元宝空框时的结构签名） |
 * | 无障碍 `EditText` 节点的 `text` 非空 | 输入框**有文案**（Compose 的 EditableText 会映射到 a11y） |
 * | 宿主 InputConnection 读回非空 | 输入框**有文案**（最可靠，直接问编辑器） |
 * | 宿主 View 树 `EditText.text` 非空 | 输入框**有文案**（原生页仍有） |
 * | 以上都没有 | **UNKNOWN** → 调用方按"宁可不发"处理 |
 *
 * 设计原则与 [ComposerSendGuard] / [ComposerGeometry] 一致：**宁可不发，也不误触**。
 */
internal object ComposerState {

    /** 输入框文案状态。 */
    enum class Text {
        /** 确认有文案 → 可以点发送。 */
        HAS_TEXT,

        /** 确认是空框 → 右侧那颗按钮此刻是"切换语音"，**绝不能点**。 */
        EMPTY,

        /** 界面停在"按住 说话"语音模式 → 输入框不存在，必须先切回键盘。 */
        VOICE_MODE,

        /** 证据不足 → 按"宁可不发"处理。 */
        UNKNOWN,
    }

    /** 元宝空输入框的占位文案（真机实测 `[224,2502][714,2579]`）。 */
    val PLACEHOLDER_MARKERS = listOf("发消息或按住说话", "按住说话", "说点什么")

    /** 空输入框的结构签名：右侧图标 `content-desc='切换语音'`。 */
    const val EMPTY_MARKER = "切换语音"

    /** 语音模式签名：输入框位置变成一颗"按住 说话"。 */
    const val VOICE_MARKER = "按住说话"

    /** 收集到的全部证据。任何一项可以缺失（`null` / `false` = 没观察到）。 */
    data class Evidence(
        /** 宿主 InputConnection 读回的当前文案（最可靠；`null` = 读不到）。 */
        val inputConnectionText: String? = null,
        /** 宿主 View 树里原生 `EditText` 的文案（Compose 页恒为 `null`）。 */
        val hostEditorText: String? = null,
        /** 无障碍树里 `EditText` 节点的文案（Compose 页**可能**为空，但值得采信）。 */
        val nodeText: String? = null,
        /** 同行是否出现占位符（"发消息或按住说话…"）。 */
        val placeholderVisible: Boolean = false,
        /** 同行是否出现 `切换语音` 图标（空框签名）。 */
        val voiceToggleVisible: Boolean = false,
        /** 是否处于"按住 说话"语音模式。 */
        val voiceMode: Boolean = false,
    )

    /**
     * 从证据里推出文案状态。
     *
     * 顺序刻意如此（每一条都有真机/单测依据）：
     * 1. 语音模式 → [Text.VOICE_MODE]（此时根本没有输入框）；
     * 2. 任一通道读到非空文案 → [Text.HAS_TEXT]；
     * 3. 出现空框签名（`切换语音` 或占位符）→ [Text.EMPTY]；
     * 4. 宿主明确读到空（`""` 而非 `null`）→ [Text.EMPTY]；
     * 5. 其余 → [Text.UNKNOWN]。
     *
     * ⚠️ 第 2 步优先于第 3 步，与 [ComposerSendGuard] 里的顺序**相反**，这是有意的：
     * 守卫回答的是"这颗按钮是什么语义"，空签名在那里是最强证据；
     * 这里回答的是"框里有没有字"，**直接读到的文字**才是最强证据
     * （元宝在**有文案**时右侧仍会保留那颗麦克风图标，"切换语音"并不随内容消失 —— 真机实测）。
     */
    fun resolve(evidence: Evidence): Text {
        if (evidence.voiceMode) return Text.VOICE_MODE
        if (hasAnyText(evidence)) return Text.HAS_TEXT
        if (evidence.voiceToggleVisible || evidence.placeholderVisible) return Text.EMPTY
        if (evidence.inputConnectionText != null && evidence.inputConnectionText.isBlank()) return Text.EMPTY
        if (evidence.hostEditorText != null && evidence.hostEditorText.isBlank()) return Text.EMPTY
        if (evidence.nodeText != null && evidence.nodeText.isBlank() && evidence.placeholderVisible) {
            return Text.EMPTY
        }
        return Text.UNKNOWN
    }

    /** 任一通道读到非空、且不是占位符的文案。 */
    private fun hasAnyText(e: Evidence): Boolean = listOfNotNull(
        e.inputConnectionText,
        e.hostEditorText,
        e.nodeText,
    ).any { it.isMeaningfulText() }

    /** 便捷入口：从扁平节点列表 + 宿主读数推出状态。 */
    fun resolveFromNodes(
        nodes: List<ScreenScanner.DumpNode>,
        inputConnectionText: String? = null,
        hostEditorText: String? = null,
    ): Text {
        val editor = nodes.firstOrNull { it.className.contains("EditText") }
        return resolve(
            Evidence(
                inputConnectionText = inputConnectionText,
                hostEditorText = hostEditorText,
                nodeText = editor?.text,
                placeholderVisible = nodes.any { node ->
                    PLACEHOLDER_MARKERS.any { (node.text + node.description).contains(it) }
                },
                voiceToggleVisible = nodes.any { node ->
                    (node.text + node.description).contains(EMPTY_MARKER)
                },
                // **有输入框就绝不是语音模式**：语音模式下 EditText 根本不存在
                // （与 [ComposerGeometry.isVoiceMode] 同一条规则）。
                // 且"按住说话"必须**整体相等**：空框占位符 `发消息或按住说话...`
                // 里就含这个子串，用 contains 会把空框误判成语音模式
                // —— 一旦误判就会去点"切换语音"把用户切进语音界面（单测当场抓到）。
                voiceMode = editor == null && nodes.any { isHoldToTalkLabel(it.text, it.description) },
            ),
        )
    }

    /**
     * 这个节点的文字是不是**语音模式的"按住 说话"**。
     *
     * 必须整体相等（去掉空白与省略号后），**不能用 contains**：
     * 空框占位符是 `发消息或按住说话...`，里面就含"按住说话"这三个字。
     * 用 contains 会把"空框"判成"语音模式"，然后去点切换键把用户切进语音界面 ——
     * 单测 `placeholderIsNeverTreatedAsRealText` 当场抓到过。
     */
    fun isHoldToTalkLabel(text: String, description: String = ""): Boolean =
        listOf(text, description).any { raw ->
            WHITESPACE_RUN.replace(raw, "")
                .trim('.', '。', '…', '·')
                .equals(VOICE_MARKER, ignoreCase = false)
        }

    private val WHITESPACE = Regex("\\s+")
}

/** 连续空白（顶层扩展函数与 object 共用一份）。 */
private val WHITESPACE_RUN = Regex("\\s+")

/**
 * 这段文字算不算"真有文案"。
 *
 * 排除两类**不代表真实文字**的内容：
 *
 * 1. **占位符**：Compose 有时会把 hint 当成节点文字暴露出来，若直接采信就会把
 *    "空框"误判成"有文案"，然后把消息发出去 —— 正是要避免的方向。
 * 2. **纯附件占位符**（1.1.7 新增）：元宝往输入框里挂图片/文件时，节点文字只有一个
 *    **对象替换符** `\uFFFC`。真机 `evidence/trace_reg_pic` 实证：P图页附着照片后
 *    读数就是 `text='\uFFFC'` —— 它被当成"上一轮留下的提示词残留"，于是清残留逻辑
 *    执行 `ACTION_SET_TEXT("")` 把**刚附加的照片抹掉**，P图必然提交失败
 *    （`picture: selected=true submitted=false`，输入框宽度从 1006 变 1153 就是图片没了的痕迹）。
 *
 * 所以这里把"对象替换符 / 替换字符 / 零宽字符"一并剔除后再判空 ——
 * 只剩这些字符 = 没有可发送的文字。
 *
 * 放在 object 外面是因为 [ComposerState] 的调用方（宿主的 `YuanbaoTaskAdapter`
 * 在 `me.codex.yuanbaodaily.task` 包）需要 import 它当扩展函数用。
 */
internal fun String.isMeaningfulText(): Boolean {
    val normalized = WHITESPACE_RUN.replace(this, "").replace(PLACEHOLDER_CHARS, "")
    if (normalized.isEmpty()) return false
    return ComposerState.PLACEHOLDER_MARKERS.none {
        normalized.contains(WHITESPACE_RUN.replace(it, ""))
    }
}

/**
 * 只起"占位"作用、本身不承载文字的字符。
 *
 * - `\uFFFC` OBJECT REPLACEMENT CHARACTER：附件（图片/文件）在输入框里的占位；
 * - `\uFFFD`：解码失败的替换字符；
 * - `\u200B`~`\u200F`、`\uFEFF`：零宽空格/连接符与 BOM。
 */
private val PLACEHOLDER_CHARS = Regex("[\\uFFFC\\uFFFD\\u200B-\\u200F\\uFEFF]")
