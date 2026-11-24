package me.codex.yuanbaodaily.automation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaSelectionVerifierTest {
    @Test
    fun acceptedGestureWithoutSurfaceChangeIsNotSelectionSuccess() {
        val before = SurfaceSnapshot("com.coloros.gallery3d", "picker:abc", pickerVisible = true)
        val after = SurfaceSnapshot("com.coloros.gallery3d", "picker:abc", pickerVisible = true)

        assertFalse(MediaSelectionVerifier.hasAdvanced(before, after))
    }

    @Test
    fun previewOrTargetTransitionCountsAsProgress() {
        val before = SurfaceSnapshot("com.coloros.gallery3d", "picker:abc", pickerVisible = true)
        val preview = SurfaceSnapshot(
            "com.coloros.gallery3d",
            "preview:def",
            pickerVisible = false,
            confirmVisible = true,
        )
        val target = SurfaceSnapshot(
            "com.tencent.hunyuan.app.chat",
            "target:ghi",
            pickerVisible = false,
            targetVisible = true,
        )

        assertTrue(MediaSelectionVerifier.hasAdvanced(before, preview))
        assertTrue(MediaSelectionVerifier.hasAdvanced(before, target))
    }

    @Test
    fun confirmThatWasAlreadyVisibleDoesNotFakeASelectionTransition() {
        val before = SurfaceSnapshot(
            "com.coloros.gallery3d",
            "picker:abc",
            pickerVisible = true,
            confirmVisible = true,
        )
        val after = before.copy()

        assertFalse(MediaSelectionVerifier.hasAdvanced(before, after))
    }
}
