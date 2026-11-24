package me.codex.yuanbaodaily.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **多品牌 / 多分辨率沙箱**：同一套真机 dump 按不同屏幕尺寸缩放后，所有几何判定必须给出同样结论。
 *
 * ## 背景
 *
 * 这套自动化最初只对着开发机（PLQ110，**1272×2800 / 480dpi**）调。用户明确要求
 * "不能只适配一台手机，还要适配各个品牌、各个分辨率"，因为像素写死会在别的机器上
 * 直接变成**乱点**：
 *
 * - `MAX_TEXT_SIDE = 1200`：在 1080 宽机型上比整屏还宽 → 整屏/整行容器筛不掉；
 * - `MAX_BUTTON_SIDE = 400`：在 720 宽机型上几乎等于半屏 → 正常按钮被判成容器；
 * - `ROW_TOLERANCE = 24`：在 320dpi 平板上只有 12dp → 同一行的按钮被判成"不同行"；
 * - 反过来，`MIN_BUTTON_SIDE = 40` 在低 dpi 设备上又会把真实按钮滤掉。
 *
 * 现在全部走 [ScreenScale]（屏宽等比 + dp 等比），本测试把"每种尺寸下结论一致"钉死。
 *
 * ## 覆盖机型
 *
 * 设备集来自 **`D:/codex/手机屏幕分辨率分析.xlsx`**（2026-10，54 台近 3 年机型，
 * 19 台已核实分辨率）。不再是"随便挑几个常见分辨率"，而是**逐个真实渲染档**：
 *
 * | 渲染分辨率 | dpi | 代表机型 | 宽高比 |
 * | --- | --- | --- | --- |
 * | 1080×2378 | 440 | 一加 Ace 6（面板 2800×1272，默认按此档渲染） | 2.202 |
 * | 1156×2510 | 440 | REDMI K90 | 2.171 |
 * | 1200×2670 | 480 | 小米 15 | 2.225 |
 * | 1208×2624 | 480 | 小米 18 Pro Max | 2.172 |
 * | 1220×2656 | 480 | 小米 17 / 17 Pro | 2.177 |
 * | 1256×2760 | 480 | OPPO Find X8 | 2.198 |
 * | 1260×2800 | 480 | vivo X200 / X300 Pro | 2.222 |
 * | 1264×2780 | 480 | Find X7 / 荣耀 Magic6 | 2.199 / 2.215 |
 * | 1272×2772 | 480 | OPPO Find X9 Pro | 2.179 |
 * | 1272×2800 | 480 | 开发机 PLQ110 / Ace 6 面板 | 2.201 |
 * | 1280×2832 | 480 | 华为 Mate 80 | 2.213 |
 * | 1320×2848 | 480 | Mate 90 Pro Max（**最矮**） | 2.158 |
 * | 1320×2868 | 480 | 荣耀 Magic9 Pro Max | 2.173 |
 * | 1440×3120 | 560 | Galaxy S26 Ultra | 2.167 |
 * | 1440×3168 | 560 | X500 Pro Max / iQOO 16 / Find X10 / 一加 12 | 2.200 |
 *
 * 另有两条边界（老机下限 720 宽、折叠展开/小平板 1600×2560）验证"不崩"。
 *
 * **为什么不用官方宣传的"面板分辨率"**：同型号存在「高清/超清」双档，OPPO/一加/真我
 * 出厂默认跑高清档（Ace 6 面板 2800×1272 → 实际渲染 2378×1080）。适配只能认运行时
 * `getRealSize()` 的实测值，所以这里用的也全是渲染档。
 */
class ResolutionInvarianceTest {

    private data class Device(val label: String, val width: Int, val height: Int, val dpi: Int) {
        val scale: ScreenScale get() = ScreenScale.of(width, height, dpi)
        val aspect: Float get() = height.toFloat() / width
    }

    private val devices = listOf(
        // —— 真实渲染档（19 台已核实机型去重） ——
        Device("1080×2378@440（一加 Ace 6 默认渲染档）", 1080, 2378, 440),
        Device("1156×2510@440（REDMI K90）", 1156, 2510, 440),
        Device("1200×2670@480（小米 15，最高宽高比 2.225）", 1200, 2670, 480),
        Device("1208×2624@480（小米 18 Pro Max）", 1208, 2624, 480),
        Device("1220×2656@480（小米 17 / 17 Pro）", 1220, 2656, 480),
        Device("1256×2760@480（OPPO Find X8）", 1256, 2760, 480),
        Device("1260×2800@480（vivo X200 / X300 Pro）", 1260, 2800, 480),
        Device("1264×2780@480（Find X7 / 荣耀 Magic6）", 1264, 2780, 480),
        Device("1272×2772@480（OPPO Find X9 Pro）", 1272, 2772, 480),
        Device("1272×2800@480（开发机 PLQ110 / Ace 6 面板）", 1272, 2800, 480),
        Device("1280×2832@480（华为 Mate 80）", 1280, 2832, 480),
        Device("1320×2848@480（Mate 90 Pro Max，最矮 2.158）", 1320, 2848, 480),
        Device("1320×2868@480（荣耀 Magic9 Pro Max）", 1320, 2868, 480),
        Device("1440×3120@560（Galaxy S26 Ultra）", 1440, 3120, 560),
        Device("1440×3168@560（X500 Pro Max / iQOO 16 / Find X10 / 一加 12）", 1440, 3168, 560),
        // —— 边界：不在近 3 年主流档内，但必须不崩 ——
        Device("720×1600@320（老机下限）", 720, 1600, 320),
        Device("1600×2560@320（折叠展开 / 小平板）", 1600, 2560, 320),
    )

    // ------------------------------ 基准 fixture（1272×2800 真机 dump） ------------------------------

    private fun node(
        left: Int, top: Int, right: Int, bottom: Int,
        label: String = "",
        description: String = "",
        resourceId: String = "",
        className: String = "android.view.View",
        clickable: Boolean = false,
    ) = ScreenScanner.DumpNode(
        packageName = "com.tencent.hunyuan.app.chat",
        text = label,
        description = description,
        resourceId = resourceId,
        className = className,
        bounds = Bounds(left, top, right, bottom),
        clickable = clickable,
        scrollable = false,
    )

    /** 空框态（`evidence/d_home2.xml`）：输入框 + 切换语音 + 更多(+) + 相机，右侧容器包着 ＋。 */
    private val emptyBarNodes = listOf(
        node(210, 2435, 922, 2645, label = "发消息或按住说话...", className = "android.widget.EditText"),
        node(1062, 2435, 1202, 2645, className = "android.widget.Button"),
        node(1048, 2435, 1216, 2645),
        node(950, 2498, 1034, 2582, description = "切换语音", className = "android.widget.ImageView"),
        node(1090, 2498, 1174, 2582, description = "更多"),
        node(98, 2498, 182, 2582, description = "拍照"),
    )

    /** 有文案态（`evidence/trace_c2`）：真正那颗 ⬆ 在最右侧。 */
    private val filledBarNodes = listOf(
        node(70, 2435, 866, 2645, label = "自检写入composer123", className = "android.widget.EditText"),
        node(866, 2435, 1034, 2645),
        node(908, 2498, 992, 2582, description = "更多"),
        node(880, 2435, 1020, 2645, className = "android.widget.Button"),
        node(1027, 2456, 1195, 2624, className = "android.widget.Button"),
    )

    /**
     * 按目标设备**双向等比**缩放 fixture。
     *
     * x 按宽度比例、y 按高度比例 —— 这样连 16:10 的平板（1600×2560）也能模拟：
     * 真实 UI 是 dp 布局 + 弹性高度，横向铺满、纵向按可用高度分布。
     */
    private fun scale(nodes: List<ScreenScanner.DumpNode>, device: Device): List<ScreenScanner.DumpNode> {
        val sx = device.width.toFloat() / 1272f
        val sy = device.height.toFloat() / 2800f
        fun px(v: Int, k: Float): Int = Math.round(v * k)
        return nodes.map { n ->
            n.copy(
                bounds = Bounds(
                    px(n.bounds.left, sx),
                    px(n.bounds.top, sy),
                    px(n.bounds.right, sx),
                    px(n.bounds.bottom, sy),
                ),
            )
        }
    }

    private fun ratio(value: Int, total: Int): Float = value.toFloat() / total

    // ------------------------------ 尺度换算本身 ------------------------------

    @Test
    fun referenceDeviceKeepsTheOriginalConstants() {
        val scale = ScreenScale.FALLBACK
        assertEquals("参考机型上按屏宽换算必须还原原值", 400, scale.widthScaled(400))
        assertEquals("参考机型上按 dp 换算必须还原原值", 24, scale.dpScaled(24))
        assertEquals(1200, scale.widthScaled(1200))
        assertEquals("参考机型 rowTolerance 必须还是 24", 24, scale.rowTolerance())
        assertEquals(400, scale.controlMaxSide())
        assertEquals(40, scale.controlMinSide())
    }

    /**
     * **最关键的一条**：任何分辨率下，"整屏容器"的宽度都必须超过文字节点的允许上限。
     *
     * 老写法 `MAX_TEXT_SIDE = 1200` 在 1080 宽机型上比整屏（1080）还宽，这条断言当场失败。
     */
    @Test
    fun fullScreenContainerIsAlwaysWiderThanTheTextLimit() {
        devices.forEach { device ->
            val limit = device.scale.widthScaled(1200)
            assertTrue(
                "${device.label}: 文字节点上限($limit) 必须小于屏宽(${device.width})，否则整屏容器筛不掉",
                limit < device.width,
            )
            assertTrue(
                "${device.label}: 上限也不能小到把正常占位符滤掉（应 > 85% 屏宽）",
                limit > device.width * 0.85f,
            )
        }
    }

    /** 控件尺寸上下限在任何分辨率下都必须落在"合理区间"，不能把真按钮滤掉。 */
    @Test
    fun controlSizeWindowStaysSaneOnEveryResolution() {
        devices.forEach { device ->
            val min = device.scale.controlMinSide()
            val max = device.scale.controlMaxSide()
            assertTrue("${device.label}: min($min) 必须 > 0", min > 0)
            assertTrue("${device.label}: min($min) 必须远小于 max($max)", min * 4 < max)
            assertTrue(
                "${device.label}: max($max) 必须小于屏宽(${device.width})，否则挡不住同行整行容器",
                max < device.width,
            )
        }
    }

    // ------------------------------ 输入栏几何 ------------------------------

    @Test
    fun emptyComposerStillHasNoSendButtonOnEveryResolution() {
        devices.forEach { device ->
            val bar = ComposerGeometry.resolve(
                scale(emptyBarNodes, device),
                textPresent = false,
                scale = device.scale,
            )
            assertNotNull("${device.label}: 必须能解析出输入栏", bar)
            requireNotNull(bar)
            assertNull("${device.label}: 空框态不能凭空认出送键", bar.sendButton)
            assertNotNull("${device.label}: 必须认出语音切换键", bar.voiceButton)
            assertFalse(
                "${device.label}: 空框态的动作点必须被拒（那是切换语音）",
                ComposerGeometry.isSafeActionPoint(bar, bar.actionX, bar.actionY),
            )
        }
    }

    @Test
    fun filledComposerPicksTheSameArrowOnEveryResolution() {
        devices.forEach { device ->
            val bar = ComposerGeometry.resolve(
                scale(filledBarNodes, device),
                textPresent = true,
                scale = device.scale,
            )
            assertNotNull("${device.label}: 必须能解析出输入栏", bar)
            requireNotNull(bar)
            val send = bar.sendButton
            assertNotNull("${device.label}: 有文案时必须认出发送键", send)
            requireNotNull(send)

            // 逻辑元素一致 = 相对位置一致（允许 ±1.5% 的取整误差）。
            assertEquals(
                "${device.label}: 发送键左边界比例应稳定在 1027/1272",
                ratio(1027, 1272),
                ratio(send.left, device.width),
                0.015f,
            )
            assertEquals(
                "${device.label}: 动作点 x 比例应稳定在 1111/1272",
                ratio(1111, 1272),
                ratio(bar.actionX, device.width),
                0.015f,
            )
            assertTrue(
                "${device.label}: 有文案时落点必须安全 x=${bar.actionX} y=${bar.actionY}",
                ComposerGeometry.isSafeActionPoint(bar, bar.actionX, bar.actionY),
            )
            assertTrue(
                "${device.label}: 落点必须落在发送键矩形内",
                send.contains(bar.actionX, bar.actionY),
            )
            // ＋ 容器中心在任何分辨率下都不能当发送落点。
            val plusCenterX = Math.round(950f * device.width / 1272f)
            val rowCenterY = Math.round(2540f * device.height / 2800f)
            assertFalse(
                "${device.label}: ＋ 容器中心 ($plusCenterX,$rowCenterY) 不能当发送落点",
                ComposerGeometry.isSafeActionPoint(bar, plusCenterX, rowCenterY),
            )
        }
    }

    // ------------------------------ 空框签名 / 发送闸门 ------------------------------

    /**
     * 整行容器（宽 = 屏宽）即使带上"切换语音"文字，也必须被尺寸阈值滤掉。
     *
     * 这是 `MAX_TEXT_SIDE` 写死 1200 时在 1080 宽机型上会失效的那条路径。
     */
    @Test
    fun fullWidthRowContainerNeverCountsAsAnEmptySignatureOnAnyResolution() {
        devices.forEach { device ->
            val editor = Bounds(
                Math.round(210f * device.width / 1272f),
                Math.round(2435f * device.height / 2800f),
                Math.round(922f * device.width / 1272f),
                Math.round(2645f * device.height / 2800f),
            )
            val rowContainer = node(
                left = 0,
                top = editor.top,
                right = device.width,
                bottom = editor.bottom,
                label = "切换语音",
            )
            assertFalse(
                "${device.label}: 宽=${device.width} 的整行容器不能被当成'空框签名节点'",
                ComposerSendGuard.showsEmptySignature(
                    listOf(rowContainer),
                    editor,
                    screenWidth = device.width,
                    scale = device.scale,
                ),
            )
        }
    }

    /** 真正的占位符（约 38% 屏宽）在任何分辨率下都必须被识别为空框签名。 */
    @Test
    fun realPlaceholderStillCountsAsEmptySignatureOnEveryResolution() {
        devices.forEach { device ->
            val editor = Bounds(
                Math.round(224f * device.width / 1272f),
                Math.round(2435f * device.height / 2800f),
                Math.round(1048f * device.width / 1272f),
                Math.round(2645f * device.height / 2800f),
            )
            val placeholder = node(
                left = 224,
                top = 2502,
                right = 714,
                bottom = 2579,
                label = "按住说话",
            ).let { n ->
                n.copy(
                    bounds = Bounds(
                        Math.round(n.bounds.left * device.width / 1272f),
                        Math.round(n.bounds.top * device.height / 2800f),
                        Math.round(n.bounds.right * device.width / 1272f),
                        Math.round(n.bounds.bottom * device.height / 2800f),
                    ),
                )
            }
            assertTrue(
                "${device.label}: 真实占位符必须被识别为空框签名",
                ComposerSendGuard.showsEmptySignature(
                    listOf(placeholder),
                    editor,
                    screenWidth = device.width,
                    scale = device.scale,
                ),
            )
        }
    }

    /**
     * 发送闸门：**没有发送节点**时，唯一放行的理由是"宿主确认有文案 + 有几何落点"。
     *
     * 这条在每种分辨率下都必须一致 —— 否则会出现"某机型空框被判成有文案 → 点到切换语音"。
     */
    @Test
    fun sendGuardVerdictIsResolutionInvariant() {
        devices.forEach { device ->
            val nodes = scale(filledBarNodes, device)
            val bar = requireNotNull(
                ComposerGeometry.resolve(nodes, textPresent = true, scale = device.scale),
            )
            val editor = bar.editor
            val point = bar.actionX to bar.actionY

            val allowed = ComposerSendGuard.decide(
                nodes = nodes,
                editor = editor,
                hostEditorText = true,
                geometryPoint = point,
                screenWidth = device.width,
                scale = device.scale,
            )
            assertTrue("${device.label}: 确认有文案 + 有几何落点 → 应放行", allowed.allowed)
            assertEquals(
                "${device.label}: 应走几何通道",
                ComposerSendGuard.Verdict.SEND_BY_GEOMETRY,
                allowed.verdict,
            )

            val refused = ComposerSendGuard.decide(
                nodes = nodes,
                editor = editor,
                hostEditorText = null,
                geometryPoint = point,
                screenWidth = device.width,
                scale = device.scale,
            )
            assertFalse(
                "${device.label}: 证据不足时必须拒点（宁可不发）",
                refused.allowed,
            )
        }
    }

    // ------------------------------ 相册格子 ------------------------------

    /**
     * 相册缩略图判据：四列布局时格子宽度 ≈ 1/4 屏宽，在任何分辨率下都必须落在
     * `[gridMin, gridMax]` 区间内（否则"找不到照片格子"或"把整块容器当格子"）。
     */
    @Test
    fun galleryGridTileWindowCoversQuarterWidthOnEveryResolution() {
        devices.forEach { device ->
            val tile = device.width / 4
            val min = device.scale.widthScaled(150)
            val max = device.scale.widthScaled(900)
            assertTrue("${device.label}: 格子下限($min) 应低于四列格子($tile)", min <= tile)
            assertTrue("${device.label}: 格子上限($max) 应高于四列格子($tile)", max >= tile)
            assertTrue("${device.label}: 格子上限必须小于屏宽", max < device.width)
        }
    }

    // ------------------------------ 底栏成行判断 ------------------------------

    /**
     * 底栏成行容差：同一行内各入口的 centerY 差不超过容差，相邻两行必须能分开。
     *
     * 老写法 60px 在 1600 宽折叠屏上小于行高（约 220px），同一行会被拆成多行 →
     * "第 index 个"错位 → 点到隔壁标签。
     */
    @Test
    fun bottomBarRowToleranceScalesWithScreenWidth() {
        devices.forEach { device ->
            val tolerance = device.scale.widthScaled(60)
            val rowPitch = device.height * 0.067f // 底栏一个 item 的高度 ≈ 屏高 6.7%
            assertTrue(
                "${device.label}: 行内容差($tolerance) 必须小于行间距(${rowPitch.toInt()})，否则两行会并成一行",
                tolerance < rowPitch,
            )
        }
    }

    // ------------------------------ 近 3 年真实机型覆盖（数据源：手机屏幕分辨率分析.xlsx） ------------------------------

    /**
     * 单测设备集必须覆盖表里出现的**每一个真实渲染宽度**。
     *
     * 漏一档就等于漏掉一批机型；这条用来防止"以后只测开发机"的回归。
     */
    @Test
    fun deviceSetCoversEveryRealRenderWidth() {
        val covered = devices.map { it.width }.toSet()
        val missing = ScreenScale.KNOWN_RENDER_WIDTHS.filter { it !in covered }
        assertTrue("以下真实渲染宽度没有被任何测试设备覆盖: $missing", missing.isEmpty())
    }

    /**
     * 直板机宽高比必须落在 2.15–2.23 —— 这是"贴底布局按距底偏移换算"的前提。
     *
     * 表里 15 组已核实分辨率的宽高比全部落在 2.158–2.225，跨度仅 3%。
     */
    @Test
    fun realStraightPhonesKeepAspectRatioWithinTheMeasuredBand() {
        devices.filter { it.width <= 1440 }.forEach { device ->
            assertTrue(
                "${device.label}: 宽高比 ${device.aspect} 超出实测区间 2.15~2.23",
                device.aspect in 2.14f..2.24f,
            )
        }
    }

    /**
     * **底栏兜底必须按"距底偏移"换算，不能固定高度百分比。**
     *
     * 旧写法 `0.845` 只在 2800 高时正确。表里 2378 高的 1080 渲染档会偏 29px、
     * 正好从"底栏入口行"偏到"输入栏"，表现就是横滑滑不出 AI创作 / AI写作。
     */
    @Test
    fun bottomBarFallbackTracksScreenHeightNotAFixedFraction() {
        // ① 参考机上必须与旧值完全等价，保证开发机行为一字不变。
        assertEquals(
            "参考机上 434px 距底偏移必须还原成 0.845",
            1f - 434f / 2800f,
            ScreenScale.FALLBACK.bottomAnchoredFraction(434),
            1e-4f,
        )

        devices.forEach { device ->
            val absoluteY = Math.round(device.scale.bottomAnchoredFraction(434) * device.height)

            // ② 距屏底的像素数必须恒等于"该机 dpi 下的 434px 等价值"，与屏高无关。
            //    这里是精确整数比较：bottomAnchoredFraction 用 1 - inset/height 反算，
            //    再乘回 height 时只可能差 1px 的浮点误差，所以断言允许 ±1 的写法要手写。
            val expectedInset = device.scale.dpScaled(434)
            val actualInset = device.height - absoluteY
            assertTrue(
                "${device.label}: 底栏距底偏移应恒为 ${expectedInset}px，实际 ${actualInset}px",
                kotlin.math.abs(actualInset - expectedInset) <= 1,
            )

            // ③ 屏高离参考机型越远，固定百分比的偏差越大 —— 这正是要修的地方。
            if (kotlin.math.abs(device.height - 2800) > 60) {
                val fixedY = Math.round(0.845f * device.height)
                assertTrue(
                    "${device.label}: 固定 0.845 偏差 ${Math.abs(fixedY - absoluteY)}px，应大于 8px",
                    Math.abs(fixedY - absoluteY) > 8,
                )
            }
        }
    }

    /** 兜底落点必须稳定落在"输入栏上方、屏幕底之上"这条带里（只对直板机断言）。 */
    @Test
    fun bottomBarFallbackNeverLandsOnComposerOrNavigationBar() {
        devices.filter { it.aspect in 2.10f..2.30f }.forEach { device ->
            val y = device.scale.bottomAnchoredFraction(434) * device.height
            // 输入栏顶 = 2435px @2800，真实布局按屏高比例分布。
            val composerTop = 2435f * device.height / 2800f
            assertTrue(
                "${device.label}: 兜底 y=${y.toInt()} 必须明显高于输入栏顶 ${composerTop.toInt()}",
                y < composerTop - 6f,
            )
            assertTrue(
                "${device.label}: 兜底 y=${y.toInt()} 不能贴到屏幕最底",
                y < device.height * 0.97f,
            )
        }
    }

    /** 屏宽档位标签：日志里必须能一眼看出走的是 1080 / 1.5K / 2K 哪一档。 */
    @Test
    fun widthTierLabelSeparatesRealDensityClasses() {
        assertEquals("fhd(~1080)", ScreenScale.of(1080, 2378, 440).widthTierLabel())
        assertEquals("fhd(~1080)", ScreenScale.of(1156, 2510, 440).widthTierLabel())
        assertEquals("wqhd1500(1200-1320)", ScreenScale.of(1200, 2670, 480).widthTierLabel())
        assertEquals("wqhd1500(1200-1320)", ScreenScale.of(1272, 2800, 480).widthTierLabel())
        assertEquals("wqhd1500(1200-1320)", ScreenScale.of(1320, 2848, 480).widthTierLabel())
        assertEquals("wqhd(1440)", ScreenScale.of(1440, 3168, 560).widthTierLabel())
        assertEquals("wide(>1520)", ScreenScale.of(1600, 2560, 320).widthTierLabel())
        assertEquals("unknown", ScreenScale(0, 0, 0).widthTierLabel())
    }
}
