package me.codex.yuanbaodaily.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LogBridgeTest {
    @Test
    fun ordinaryModuleProcessWithoutXposedBridgeUsesAndroidSink() {
        val events = mutableListOf<String>()

        LogBridge.write(
            tag = "YuanbaoDaily",
            message = "startup",
            xposedSink = null,
            androidSink = { tag, line -> events += "$tag:$line" },
        )

        assertEquals(listOf("YuanbaoDaily:startup"), events)
    }

    @Test
    fun missingXposedBridgeIsDetectedWithoutLinkageError() {
        assertNull(LogBridge.findXposedSink(object : ClassLoader(null) {}))
    }

    @Test
    fun failingXposedSinkFallsBackToAndroidSink() {
        val events = mutableListOf<String>()

        LogBridge.write(
            tag = "YuanbaoDaily",
            message = "startup",
            xposedSink = { error("bridge unavailable") },
            androidSink = { tag, line -> events += "$tag:$line" },
        )

        assertEquals(listOf("YuanbaoDaily:startup"), events)
    }
}
