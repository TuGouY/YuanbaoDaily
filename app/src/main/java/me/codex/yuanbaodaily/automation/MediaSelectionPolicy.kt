package me.codex.yuanbaodaily.automation

internal data class MediaCandidateDescriptor(
    val key: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val semantic: String,
    val className: String,
    val resourceId: String,
    val camera: Boolean,
    val ordinal: Int?,
    val score: Int,
) {
    val centerX: Int get() = left + (right - left) / 2
    val centerY: Int get() = top + (bottom - top) / 2
}

internal object MediaCandidatePolicy {
    fun choose(
        candidates: List<MediaCandidateDescriptor>,
        marker: String,
        screenText: String,
        blockedKeys: Set<String>,
    ): MediaCandidateDescriptor? {
        if (EMPTY_GALLERY_MARKERS.any(screenText.lowercase()::contains)) return null
        val blockedBounds = candidates
            .filter { it.key in blockedKeys }
            .map { listOf(it.left, it.top, it.right, it.bottom) }
            .toSet()
        val usable = candidates
            .asSequence()
            .filterNot { it.camera || it.key in blockedKeys }
            .filterNot { listOf(it.left, it.top, it.right, it.bottom) in blockedBounds }
            .filter { it.right > it.left && it.bottom > it.top }
            .distinctBy { listOf(it.left, it.top, it.right, it.bottom) }
            .toList()
        if (usable.isEmpty()) return null

        val normalizedMarker = marker.trim().lowercase()
        if (normalizedMarker.isNotEmpty()) {
            usable.firstOrNull { it.semantic.lowercase().contains(normalizedMarker) }?.let { return it }
            // **marker 命中不了时，退化必须收紧。**
            //
            // 真机实证（1.4.12）：相册里同时有我们自己生成的素材和一屏截图。
            // 第一次点中目标那张（`…|324,569,633,878`），但那次点击没有产生界面变化，
            // 于是它进了 `blockedKeys`；第二次 `choose` 过滤掉它之后，代码直接
            // "按位置取第一个" —— 选中的是相册最旧的一张**截图**，
            // 附加到 P图 页面的根本不是我们的图，而日志一路 `selected=true`。
            //
            // 但不能简单返回 null：拍题场景里目标常常只有一次点击机会，
            // 死守同一张会导致整轮失败（已有单测 `exactGeneratedFilenameWins...` 锁着这个行为）。
            //
            // 折中：退化时**排除掉"语义里带别的文件名"的候选**。
            // 既然我们能读到 `d_pic1.png` 这种名字，就说明相册上报了文件名 ——
            // 那么目标读不到就真的不在，此时选任何带文件名的别的图都是错的。
            // 而"第 N 项 图片"这类中性描述不带具名文件，退化为它们仍可接受。
            val neutral = usable.filterNot { OTHER_FILENAME.containsMatchIn(it.semantic.lowercase()) }
            if (neutral.isEmpty()) return null
            return neutral.sortedWith(
                compareBy<MediaCandidateDescriptor> { it.ordinal ?: Int.MAX_VALUE }
                    .thenByDescending { it.score }
                    .thenBy { it.top }
                    .thenBy { it.left },
            ).firstOrNull()
        }
        return usable.sortedWith(
            compareBy<MediaCandidateDescriptor> { it.ordinal ?: Int.MAX_VALUE }
                .thenByDescending { it.score }
                .thenBy { it.top }
                .thenBy { it.left },
        ).firstOrNull()
    }

    /**
     * 语义里出现的**具体文件名**（如 `d_pic1.png`、`screenshot_2026.jpg`）。
     *
     * 用途见 [choose]：marker 命中不了时，退化只能选"没有具名文件"的候选，
     * 带了别的文件名就说明那是另一张照片。
     */
    private val OTHER_FILENAME = Regex("""[a-z0-9_\-]{2,}\.(png|jpe?g|webp|heic)""")

    private val EMPTY_GALLERY_MARKERS = listOf(
        "无照片或视频",
        "没有照片或视频",
        "暂无照片",
        "no photos or videos",
        "no photos",
    )
}

internal object AccessibilityPackagePolicy {
    private const val TARGET_PACKAGE = "com.tencent.hunyuan.app.chat"

    fun isAllowed(packageName: String): Boolean =
        packageName == TARGET_PACKAGE || isMediaPackage(packageName) || isCameraPackage(packageName)

    fun isMediaPackage(packageName: String): Boolean {
        val normalized = packageName.lowercase()
        return normalized in MEDIA_PACKAGES || MEDIA_PACKAGE_HINTS.any(normalized::contains)
    }

    fun isCameraPackage(packageName: String): Boolean {
        val normalized = packageName.lowercase()
        return normalized in CAMERA_PACKAGES || normalized.endsWith(".camera") ||
            normalized.contains(".camera.")
    }

    private val MEDIA_PACKAGES = setOf(
        "com.android.providers.media",
        "com.google.android.providers.media.module",
        "com.android.providers.media.module",
        "com.android.photopicker",
        "com.google.android.photopicker",
        "com.android.documentsui",
        "com.android.permissioncontroller",
        "com.coloros.gallery3d",
        "com.oplus.gallery",
        "com.miui.gallery",
        "com.huawei.photos",
        "com.sec.android.gallery3d",
        "com.android.gallery3d",
        "com.google.android.apps.photos",
        "com.google.android.apps.photosgo",
        "com.transsion.gallery",
        "com.zui.gallery",
    )

    private val CAMERA_PACKAGES = setOf(
        "com.android.camera",
        "com.android.camera2",
        "com.oplus.camera",
        "com.coloros.camera",
        "com.oneplus.camera",
        "com.miui.camera",
        "com.samsung.android.camera",
        "com.sec.android.app.camera",
        "com.huawei.camera",
        "com.hihonor.camera",
        "com.hihonor.camera2",
        "com.vivo.camera",
        "com.iqoo.camera",
    )

    private val MEDIA_PACKAGE_HINTS = listOf(
        "gallery",
        "photopicker",
        "photo.picker",
        "photos",
        "documentsui",
        "providers.media",
    )
}

internal data class SurfaceSnapshot(
    val packageName: String,
    val signature: String,
    val pickerVisible: Boolean,
    val targetVisible: Boolean = false,
    val confirmVisible: Boolean = false,
)

internal object MediaSelectionVerifier {
    fun hasAdvanced(before: SurfaceSnapshot, after: SurfaceSnapshot): Boolean {
        if (after.targetVisible) return true
        if (!before.confirmVisible && after.confirmVisible) return true
        if (before.packageName != after.packageName) return true
        if (before.pickerVisible && !after.pickerVisible) return true
        return before.signature.isNotBlank() && after.signature.isNotBlank() &&
            before.signature != after.signature
    }
}

/**
 * Photo candidates taken from a platform UI dump instead of the accessibility
 * tree.
 *
 * Used when the host runs in another Android user (cloned / "双开" app): there
 * the accessibility service never sees the picker, but the root shell dump does.
 * The resulting descriptors feed the very same [MediaCandidatePolicy], so a dump
 * selection behaves exactly like an accessibility selection.
 */
internal object DumpMediaCandidates {
    /**
     * @param screenWidth 当前屏幕宽度（像素）。给了就按屏宽等比换算格子尺寸下限
     *   （参考机型 1272 宽下的 96px）。不给则沿用参考值 —— 保证老调用方行为不变。
     */
    fun build(
        nodes: List<ScreenScanner.DumpNode>,
        screenText: String,
        screenWidth: Int = 0,
    ): List<MediaCandidateDescriptor> {
        val visibleText = screenText.lowercase()
        if (EMPTY_GALLERY_MARKERS.any(visibleText::contains)) return emptyList()
        val minSide = ScreenScale.scaleWidth(screenWidth, MIN_TILE_SIDE_REF)
        val output = mutableListOf<MediaCandidateDescriptor>()
        for (node in nodes) {
            val bounds = node.box
            if (bounds.width < minSide || bounds.height < minSide) continue
            val label = node.label.lowercase()
            val id = node.resourceId.lowercase()
            val className = node.className
            val ownSemantic = "$label $id"
            if (ownSemantic.isBlank()) continue
            if (EXCLUDED_WORDS.any(ownSemantic::contains)) continue
            val camera = CAMERA_WORDS.any(ownSemantic::contains)
            val filenameMedia = FILENAME_REGEX.containsMatchIn(label)
            val timelineItem = className.contains("TimelineView") || label.contains("时间线")
            val idLooksLikeMedia = ID_HINTS.any(id::contains)
            val imageNode = className.endsWith("ImageView") &&
                (label.contains("图片") || label.contains("照片") ||
                    label.contains("image") || idLooksLikeMedia)
            val ordinal = ORDINAL_REGEX.find(label)?.groupValues?.getOrNull(1)?.toIntOrNull()
            val photoLike = ordinal != null || timelineItem || imageNode || idLooksLikeMedia ||
                filenameMedia || label.contains("图片") || label.contains("照片")
            if (!photoLike) continue
            var score = 0
            if (ordinal == 1) score += 160
            if (timelineItem) score += 120
            if (idLooksLikeMedia) score += 80
            if (filenameMedia) score += 180
            if (label.contains("图片") || label.contains("照片")) score += 50
            if (node.clickable) score += 20
            if (AccessibilityPackagePolicy.isMediaPackage(node.packageName)) score += 70
            if (AccessibilityPackagePolicy.isCameraPackage(node.packageName)) score -= 40
            output += MediaCandidateDescriptor(
                key = "${node.packageName}|${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}",
                left = bounds.left,
                top = bounds.top,
                right = bounds.right,
                bottom = bounds.bottom,
                semantic = "$label ${node.resourceId}".trim().take(800),
                className = className,
                resourceId = node.resourceId,
                camera = camera,
                ordinal = ordinal,
                score = score,
            )
        }
        return output
    }

    private val EMPTY_GALLERY_MARKERS = listOf(
        "无照片或视频",
        "没有照片或视频",
        "暂无照片",
        "no photos or videos",
        "no photos",
    )

    private val EXCLUDED_WORDS = listOf(
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

    private val CAMERA_WORDS = listOf("相机", "拍照", "拍摄", "camera")

    private val ID_HINTS = listOf(
        "thumbnail",
        "thumb",
        "media_item",
        "photo_item",
        "image_item",
        "timeline_item",
    )

    private val FILENAME_REGEX = Regex(
        "[^\\s/\\\\]+\\.(png|jpe?g|webp|heic|gif)$",
        RegexOption.IGNORE_CASE,
    )

    private val ORDINAL_REGEX = Regex("第\\s*(\\d+)\\s*项")

    /**
     * 缩略图尺寸下限（**参考机型 1272 宽上的像素**）。
     *
     * 写死 96 时在 720 宽机型上相当于 17% 屏宽（四列格子只有 180px，勉强过），
     * 在 1600 宽折叠屏上只相当于 6% 屏宽 → 大量图标/文字块被当成照片。
     * 按屏宽等比换算后，各分辨率下"多大算缩略图"保持同一套相对判据。
     */
    private const val MIN_TILE_SIDE_REF = 96
}
