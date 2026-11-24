package me.codex.yuanbaodaily.automation

/**
 * "哪个窗口该被当成相册来扫描"的**纯判定**。
 *
 * ## 为什么必须单独抽出来（真机回归）
 *
 * 元宝的相册选择器和 P图 能力页**在同一个包名里**，所以只能靠窗口里的文字区分。
 * 原来的判据用的是**泛词**：
 *
 * ```kotlin
 * MEDIA_PICKER_WORDS = listOf("本地相册", "相册", "照片", "所有照片", …)
 * // …
 * root === activeRoot && MEDIA_PICKER_WORDS.any(rootText::contains)  → 当作相册扫描
 * ```
 *
 * 「相册」「照片」这两个词在 **P图 能力页**上就有（素材位写着"点击添加创作素材"、
 * 页面里还有"相册"字样）。于是：
 *
 * 1. 能力页被当成"相册窗口"去扫，当然扫不到任何照片格子 → 候选数 0；
 * 2. 真正的相册窗口**不是** activeRoot、又不是媒体包、文本里也没有文件名 →
 *    被 `when` 的三个分支全部拒掉；
 * 3. 候选数 0 → 上层判定"相册里没有素材" → 按 **返回** 关掉相册 → 重开 → 再来一遍。
 *
 * 用户看到的就是"打开相册后不选照片，而是点返回，反复这样"。
 *
 * ## 判据
 *
 * 同包窗口**必须命中强标记**（只有相册才有的那几个），泛词一律不算。
 * 媒体包（系统选择器 / 各家相册）直接放行，相机包一律排除。
 */
internal object MediaPickerWindowPolicy {

    /**
     * 只有**相册窗口**才会出现的强标记。
     *
     * 刻意剔除「相册」「照片」这种泛词 —— 它们会在 P图 能力页、首页素材位上出现。
     */
    val STRONG_MARKERS = listOf(
        "本地相册",
        "最近项目",
        "相机胶卷",
        "所有照片",
        "选择照片",
        "选择项目",
        "照片和视频",
        "无照片或视频",
    )

    /**
     * 这个窗口要不要当作相册来扫描。
     *
     * @param isCameraPackage [AccessibilityPackagePolicy.isCameraPackage]
     * @param isMediaPackage [AccessibilityPackagePolicy.isMediaPackage]
     */
    fun shouldScan(
        packageName: String,
        windowText: String,
        isCameraPackage: Boolean,
        isMediaPackage: Boolean,
    ): Boolean {
        if (isCameraPackage) return false
        if (isMediaPackage) return true
        // 元宝自己的窗口：必须有强标记。泛词 "相册/照片" 不算 —— 见类注释。
        val lower = windowText.lowercase()
        return STRONG_MARKERS.any { lower.contains(it) }
    }

    /** 窗口里能直接看到这批文件的文件名时，无条件认为它是相册（最强证据）。 */
    fun mentionsMarker(windowText: String, marker: String): Boolean {
        if (marker.isBlank() || windowText.isEmpty()) return false
        return windowText.contains(marker, ignoreCase = true)
    }
}
