package me.codex.yuanbaodaily.task

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaPublicationVerifierTest {
    @Test
    fun publishedMediaMustMatchNameHaveBytesAndNotBePending() {
        val ready = MediaPublicationSnapshot(
            displayName = "YuanbaoDaily_AUTO_pic_123.png",
            sizeBytes = 4096L,
            pending = 0,
        )

        assertTrue(MediaPublicationVerifier.isReady("YuanbaoDaily_AUTO_pic_123.png", ready))
        assertFalse(MediaPublicationVerifier.isReady(ready.displayName, ready.copy(sizeBytes = 0L)))
        assertFalse(MediaPublicationVerifier.isReady(ready.displayName, ready.copy(pending = 1)))
        assertFalse(MediaPublicationVerifier.isReady("different.png", ready))
        assertFalse(MediaPublicationVerifier.isReady(ready.displayName, null))
    }
}
