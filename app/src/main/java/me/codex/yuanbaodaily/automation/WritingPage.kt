package me.codex.yuanbaodaily.automation

/**
 * "AI写作"页的识别（纯函数，可在 JVM 单测里直接跑）。
 *
 * ### 为什么单独立一个文件
 * 元宝的"AI写作"页和对话页一样，输入框是 **Compose 虚拟节点**：宿主 View 树里找不到，
 * 只有无障碍服务看得见。所以写入必须走"聚焦 + 粘贴/直写 + 回读"那条通道，
 * 而**判断当前是不是写作页**就成了这条通道的入口条件。
 *
 * 旧代码在入口条件上用了"不是能力页"的写法（`text.contains("选择写作类型")` 会把它排除），
 * 结果写作页永远进不了 Compose 通道，表现为用户报的 **"AI写作定位不到输入框"**。
 * 这类"页面识别"最容易随版本/文案回归，所以判据抽到这里并配单测锁住。
 *
 * ### 判据来源（真机 dump，1272×2800）
 * `evidence/write_probe.xml`（键盘弹起）与 `evidence/write_nokb.xml`（键盘收起）里，
 * 写作页的文案是：
 * ```
 * 选择写作类型 / 通用 / 作文 / 作文素材 / 营销文案 / 公文
 * 发布平台 / 写作风格 / 字数
 * EditText     [70,2435][1062,2645] focused=true   ← 收起键盘
 * EditText     [70,1438][1062,1648] focused=true   ← 弹起键盘（位置随键盘移动）
 * TextView     '请输入你要写的主题'                  ← 输入框占位符
 * ImageView    desc='切换语音'                      ← 空框时右侧那颗（点了会切语音 ✗）
 * ```
 *
 * ### 注意
 * **不能用单独的 `AI写作` 判定**：主页那一行快捷入口里也有 `AI写作`
 * （`evidence/home_probe.xml`：`desc='AI写作' [406,2302][462,2358]`）。
 * 所以判据只取写作页**独有**的文案。
 */
internal object WritingPage {

    /** 写作页独有文案（主页 / 对话页 / P图页都不会出现）。 */
    val MARKERS = listOf(
        "选择写作类型",
        "请输入你要写的主题",
    )

    /**
     * 这段屏幕文本看起来是不是"AI写作"页。
     *
     * 命中任一独有文案即认定；页面加载中途只出现一半时也能命中
     * （`请输入你要写的主题` 是输入框占位符，通常先于类型列表出现）。
     */
    fun looks(text: String): Boolean {
        if (text.isEmpty()) return false
        return MARKERS.any { text.contains(it) }
    }
}
