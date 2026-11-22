package com.tvplayair.receiver.video

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MirrorFrameGateTest {

    @Test
    fun outputRecoveryRequiresQueuedWorkAndRearmsOnlyAfterOutputReturns() {
        var nowMs = 0L
        val watchdog = MirrorOutputWatchdog { nowMs }
        nowMs += 10_000
        assertFalse(watchdog.takeRecovery())
        repeat(3) { watchdog.onFrameQueued() }
        nowMs += 3_000
        assertFalse(watchdog.takeRecovery())

        watchdog.clearQueuedFrames()
        repeat(4) { watchdog.onFrameQueued() }
        nowMs += 1_999
        assertFalse(watchdog.takeRecovery())
        nowMs += 1
        assertTrue(watchdog.takeRecovery())

        repeat(4) { watchdog.onFrameQueued() }
        nowMs += 2_000
        assertFalse(watchdog.takeRecovery())
        watchdog.onOutputReleased()
        watchdog.onFrameQueued()
        nowMs += 1_999
        assertFalse(watchdog.takeRecovery())
        nowMs += 1
        assertTrue(watchdog.takeRecovery())
    }


    @Test
    fun dropsNonIdrFramesUntilKeyframeArrives() {
        val gate = MirrorFrameGate()
        assertTrue(gate.isAwaitingIdr())

        assertFalse(gate.shouldSubmitFrame(0))
        assertFalse(gate.shouldSubmitFrame(0))
        assertEquals(2L, gate.droppedNonIdrFrames)
        assertEquals(0L, gate.acceptedFrames)

        assertTrue(gate.shouldSubmitFrame(MirrorFrameGate.FLAG_KEY_FRAME))
        gate.onFrameSubmitted(MirrorFrameGate.FLAG_KEY_FRAME)
        assertFalse(gate.isAwaitingIdr())
        assertTrue(gate.shouldSubmitFrame(0))
        gate.onFrameSubmitted(0)
        assertEquals(2L, gate.acceptedFrames)
    }

    @Test
    fun aKeyframeRejectedByMediaCodecDoesNotOpenTheGate() {
        val gate = MirrorFrameGate()
        assertTrue(gate.shouldSubmitFrame(MirrorFrameGate.FLAG_KEY_FRAME))
        assertTrue(gate.isAwaitingIdr())
        assertFalse(gate.shouldSubmitFrame(0))
        assertEquals(0L, gate.acceptedFrames)
        gate.onFrameSubmitted(MirrorFrameGate.FLAG_KEY_FRAME)
        assertTrue(gate.shouldSubmitFrame(0))
    }

    @Test
    fun resetGateRetainsCachedCodecConfigAndRearmsIdrGate() {
        val gate = MirrorFrameGate()
        val spsPpsVps = byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x40, 0x01, 0x42, 0x01)
        gate.onCodecConfig(spsPpsVps)
        assertTrue(gate.hasCachedCodecConfig())
        assertTrue(gate.shouldSubmitFrame(MirrorFrameGate.FLAG_CODEC_CONFIG))
        assertTrue(gate.shouldSubmitFrame(MirrorFrameGate.FLAG_KEY_FRAME))
        gate.onFrameSubmitted(MirrorFrameGate.FLAG_KEY_FRAME)
        assertFalse(gate.isAwaitingIdr())

        // Returning from ExoPlayer: reset gate but preserve SPS/PPS/VPS cache.
        gate.resetGate()
        assertTrue(gate.isAwaitingIdr())
        assertArrayEquals(spsPpsVps, gate.getCachedCodecConfig())
        assertFalse(gate.shouldSubmitFrame(0))
        assertTrue(gate.shouldSubmitFrame(MirrorFrameGate.FLAG_KEY_FRAME))
    }

    @Test
    fun clearAllRemovesCachedCodecConfigAndRearmsGate() {
        val gate = MirrorFrameGate()
        gate.onCodecConfig(byteArrayOf(1, 2, 3, 4))
        gate.onFrameSubmitted(MirrorFrameGate.FLAG_KEY_FRAME)

        gate.clearAll()
        assertNull(gate.getCachedCodecConfig())
        assertFalse(gate.hasCachedCodecConfig())
        assertTrue(gate.isAwaitingIdr())
    }
}
