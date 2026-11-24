package me.codex.yuanbaodaily.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaCandidatePolicyTest {
    @Test
    fun emptyGalleryDoesNotReturnPlaceholderTimelineItem() {
        val placeholder = candidate(
            key = "0,361,422,783",
            semantic = ",第 1 项,图片,,2026年9月21日 21:30",
            className = "com.oplus.gallery.business_lib.timeline.view.TimelineView",
        )

        val selected = MediaCandidatePolicy.choose(
            candidates = listOf(placeholder),
            marker = "YuanbaoDaily_AUTO_pic_123",
            screenText = "照片 图集 无照片或视频",
            blockedKeys = emptySet(),
        )

        assertNull(selected)
    }

    @Test
    fun newestSemanticItemWinsWithoutFixedGridPosition() {
        val camera = candidate(
            key = "camera",
            semantic = "相机 拍照",
            left = 0,
            top = 360,
            camera = true,
        )
        val newest = candidate(
            key = "newest",
            semantic = "第 1 项 图片 2026年9月26日 12:30",
            left = 422,
            top = 360,
            ordinal = 1,
        )
        val older = candidate(
            key = "older",
            semantic = "第 2 项 图片 2026年9月25日 18:00",
            left = 0,
            top = 782,
            ordinal = 2,
        )

        val selected = MediaCandidatePolicy.choose(
            candidates = listOf(camera, older, newest),
            marker = "YuanbaoDaily_AUTO_pic_123",
            screenText = "照片 图集",
            blockedKeys = emptySet(),
        )

        assertEquals("newest", selected?.key)
    }

    @Test
    fun exactGeneratedFilenameWinsAndBlockedCandidateIsNotRetried() {
        val newest = candidate(key = "newest", semantic = "第 1 项 图片", ordinal = 1)
        val generated = candidate(
            key = "generated",
            semantic = "YuanbaoDaily_AUTO_problem_987_1.jpg",
            left = 422,
            ordinal = 4,
        )

        val exact = MediaCandidatePolicy.choose(
            candidates = listOf(newest, generated),
            marker = "YuanbaoDaily_AUTO_problem_987_1",
            screenText = "所有照片",
            blockedKeys = emptySet(),
        )
        val next = MediaCandidatePolicy.choose(
            candidates = listOf(newest, generated),
            marker = "YuanbaoDaily_AUTO_problem_987_1",
            screenText = "所有照片",
            blockedKeys = setOf("generated"),
        )

        assertEquals("generated", exact?.key)
        assertEquals("newest", next?.key)
    }

    @Test
    fun blockedCoordinateDoesNotRetryDuplicateAccessibilityNode() {
        val outer = candidate(key = "outer", semantic = "第 1 项 图片", ordinal = 1)
        val nested = candidate(key = "nested", semantic = "图片缩略图", ordinal = 1)
        val next = candidate(
            key = "next",
            semantic = "第 2 项 图片",
            top = 782,
            ordinal = 2,
        )

        val selected = MediaCandidatePolicy.choose(
            candidates = listOf(outer, nested, next),
            marker = "",
            screenText = "所有照片",
            blockedKeys = setOf("outer"),
        )

        assertEquals("next", selected?.key)
    }

    /**
     * **目标被 block、且其余候选都带别的文件名时，必须放弃。**
     *
     * 1.4.12 真机事故现场：相册里既有我们生成的素材，也有一屏截图。
     * 目标第一次点击没产生界面变化 → 进 blockedKeys → 旧代码退化成按位置选 →
     * 选中了一张 `d_pic1.png` 截图，附加到 P图 页面的不是我们的图，
     * 而日志一路 `selected=true`，极难发现。
     */
    @Test
    fun refusesFallbackWhenRemainingCandidatesAreOtherFiles() {
        val target = candidate(
            key = "target",
            semantic = "YuanbaoDaily_AUTO_pic_1792_1.png",
            left = 324,
            top = 569,
        )
        val screenshot = candidate(key = "shot", semantic = "d_pic1.png", left = 7, top = 2471)
        val screenshot2 = candidate(key = "shot2", semantic = "screenshot_2026.jpg", left = 331, top = 2471)

        val selected = MediaCandidatePolicy.choose(
            candidates = listOf(screenshot, screenshot2, target),
            marker = "YuanbaoDaily_AUTO_pic_1792_1.png",
            screenText = "所有照片",
            blockedKeys = setOf("target"),
        )

        assertNull("剩下的都是别的具名文件，宁可不选", selected)
    }

    /**
     * 目标根本不在相册里（且候选带具名文件）时同样放弃 ——
     * 这说明素材没被索引到，选任何别的图都是错的。
     */
    @Test
    fun refusesWhenMarkerAbsentAndCandidatesAreNamed() {
        val selected = MediaCandidatePolicy.choose(
            candidates = listOf(
                candidate(key = "a", semantic = "d_pic1.png"),
                candidate(key = "b", semantic = "d_pic2.png"),
            ),
            marker = "YuanbaoDaily_AUTO_pic_404.png",
            screenText = "所有照片",
            blockedKeys = emptySet(),
        )
        assertNull(selected)
    }

    @Test
    fun dynamicGalleryPackageIsAllowedButUnrelatedPackageIsRejected() {
        assertTrue(AccessibilityPackagePolicy.isAllowed("com.vendor.magicgallery"))
        assertTrue(AccessibilityPackagePolicy.isAllowed("com.vendor.photo.picker"))
        assertFalse(AccessibilityPackagePolicy.isAllowed("com.vendor.messaging"))
    }

    private fun candidate(
        key: String,
        semantic: String,
        className: String = "android.widget.ImageView",
        left: Int = 0,
        top: Int = 360,
        camera: Boolean = false,
        ordinal: Int? = null,
    ) = MediaCandidateDescriptor(
        key = key,
        left = left,
        top = top,
        right = left + 422,
        bottom = top + 422,
        semantic = semantic,
        className = className,
        resourceId = "",
        camera = camera,
        ordinal = ordinal,
        score = 100,
    )
}
