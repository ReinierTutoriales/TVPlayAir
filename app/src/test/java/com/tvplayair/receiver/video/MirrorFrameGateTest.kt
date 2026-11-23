package com.tvplayair.receiver.video

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MirrorFrameGateTest {

    @Test
    fun legacyOutputProgressDoesNotConfirmStartupOrRecovery() {
        var now = 0L
        val watchdog = MirrorOutputWatchdog(allowLegacyOutputProgress = true) { now }
        watchdog.awaitFirstFrame()
        watchdog.onFrameQueued()
        now += 2_000
        watchdog.onOutputReleased()
        assertTrue(watchdog.renderStalled())

        watchdog.onFrameRendered()
        repeat(4) { watchdog.onFrameQueued() }
        now += 2_000
        watchdog.onOutputReleased()
        assertFalse(watchdog.renderStalled())
        now += 2_000
        assertTrue(watchdog.renderStalled())

        watchdog.beginRecovery()
        now += 5_000
        watchdog.onOutputReleased()
        assertTrue(watchdog.recoveryExpired())
    }

    @Test
    fun recoveryCallbackSurvivesWorkerExitButRejectsStaleOrCancelledRequests() {
        assertTrue(shouldDeliverRecoveryTimeout(true, true, true))
        assertFalse(shouldDeliverRecoveryTimeout(false, true, true))
        assertFalse(shouldDeliverRecoveryTimeout(true, false, true))
        assertFalse(shouldDeliverRecoveryTimeout(true, true, false))
    }

    @Test
    fun frameClockSchedulesOnlyPlausibleFutureFramesAndExpiresUnavailableAudio() {
        var now = 1_000_000_000L
        var offset = -3_000_000_000L
        val clock = MirrorFrameClock({ offset }, { now })
        assertTrue(clock.renderTimeNs(4_020_000L) == 1_020_000_000L)
        assertNull(clock.renderTimeNs(4_500_000L))
        assertNull(clock.renderTimeNs(3_999_000L))
        assertNull(clock.renderTimeNs(0L))

        offset = Long.MIN_VALUE
        now += 100_000_000L
        assertNull(clock.renderTimeNs(4_120_000L))
    }

    @Test
    fun frameClockRejectsOverflowedSenderClockArithmetic() {
        val nearMaxPts = Long.MAX_VALUE / 1000L
        val positiveOverflow = MirrorFrameClock({ Long.MAX_VALUE - 1L }, { 100L })
        assertNull(positiveOverflow.renderTimeNs(nearMaxPts))

        val negativeOverflow = MirrorFrameClock({ Long.MIN_VALUE + 1L }, { 100L })
        assertNull(negativeOverflow.renderTimeNs(nearMaxPts))

        val nearMaxNow = MirrorFrameClock({ 0L }, { Long.MAX_VALUE - 10L })
        assertNull(nearMaxNow.renderTimeNs(nearMaxPts))
    }

    @Test
    fun duplicateCodecConfigDoesNotReplaceTheCachedBytes() {
        val gate = MirrorFrameGate()
        val config = byteArrayOf(1, 2, 3)
        assertTrue(gate.onCodecConfig(config))
        assertFalse(gate.onCodecConfig(config.copyOf()))
        assertFalse(gate.onCodecConfig(byteArrayOf()))
        config[0] = 9
        assertArrayEquals(byteArrayOf(1, 2, 3), gate.getCachedCodecConfig())
        assertTrue(gate.onCodecConfig(config))
    }

    @Test
    fun recoveryDeadlineDoesNotSlideAndClearsWhenRenderingReturns() {
        var nowMs = 100L
        val watchdog = MirrorOutputWatchdog { nowMs }
        nowMs += 30_000
        assertFalse(watchdog.recoveryExpired())

        watchdog.beginRecovery()
        nowMs += 4_999
        watchdog.beginRecovery()
        assertFalse(watchdog.recoveryExpired())
        nowMs++
        assertTrue(watchdog.recoveryExpired())

        watchdog.onFrameRendered()
        assertFalse(watchdog.recoveryExpired())
    }

    @Test
    fun oneQueuedFrameWithoutRenderConfirmationTriggersStartupRecovery() {
        var nowMs = 0L
        val watchdog = MirrorOutputWatchdog { nowMs }
        watchdog.awaitFirstFrame()
        watchdog.onFrameQueued()
        nowMs = 1_999
        assertFalse(watchdog.renderStalled())
        nowMs++
        assertTrue(watchdog.renderStalled())
        watchdog.beginRecovery()
        repeat(4) { watchdog.onFrameQueued() }
        nowMs += 4_999
        assertFalse(watchdog.recoveryExpired())
        nowMs++
        assertTrue(watchdog.recoveryExpired())
    }

    @Test
    fun startupWithoutAnyPacketsExpiresAndRenderedStreamCanStayIdle() {
        var nowMs = 0L
        val watchdog = MirrorOutputWatchdog { nowMs }
        watchdog.awaitFirstFrame()
        nowMs += 5_000
        assertTrue(watchdog.recoveryExpired())

        watchdog.onFrameRendered()
        repeat(3) { watchdog.onFrameQueued() }
        nowMs += 30_000
        assertFalse(watchdog.renderStalled())
        assertFalse(watchdog.recoveryExpired())

        repeat(4) { watchdog.onFrameQueued() }
        watchdog.onFrameRendered()
        repeat(4) { watchdog.onFrameQueued() }
        nowMs += 2_000
        assertTrue(watchdog.renderStalled())
    }

    @Test
    fun dropsNonIdrFramesUntilKeyframeArrives() {
        val gate = MirrorFrameGate()

        assertFalse(gate.shouldSubmitFrame(0))
        assertFalse(gate.shouldSubmitFrame(0))

        assertTrue(gate.shouldSubmitFrame(MirrorFrameGate.FLAG_KEY_FRAME))
        gate.onFrameSubmitted(MirrorFrameGate.FLAG_KEY_FRAME)
        assertTrue(gate.shouldSubmitFrame(0))
        gate.onFrameSubmitted(0)
    }

    @Test
    fun aKeyframeRejectedByMediaCodecDoesNotOpenTheGate() {
        val gate = MirrorFrameGate()
        assertTrue(gate.shouldSubmitFrame(MirrorFrameGate.FLAG_KEY_FRAME))
        assertFalse(gate.shouldSubmitFrame(0))
        gate.onFrameSubmitted(MirrorFrameGate.FLAG_KEY_FRAME)
        assertTrue(gate.shouldSubmitFrame(0))
    }

    @Test
    fun resetGateRetainsCachedCodecConfigAndRearmsIdrGate() {
        val gate = MirrorFrameGate()
        val spsPpsVps = byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x40, 0x01, 0x42, 0x01)
        gate.onCodecConfig(spsPpsVps)
        assertTrue(gate.shouldSubmitFrame(MirrorFrameGate.FLAG_CODEC_CONFIG))
        assertTrue(gate.shouldSubmitFrame(MirrorFrameGate.FLAG_KEY_FRAME))
        gate.onFrameSubmitted(MirrorFrameGate.FLAG_KEY_FRAME)

        // Returning from ExoPlayer: reset gate but preserve SPS/PPS/VPS cache.
        gate.resetGate()
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
    }
}
