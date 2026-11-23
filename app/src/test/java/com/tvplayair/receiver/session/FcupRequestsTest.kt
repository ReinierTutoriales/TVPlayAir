package com.tvplayair.receiver.session

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class FcupRequestsTest {
    private val videoUrl = "mlhls://localhost/itag/602/mediadata.m3u8"
    private val audioUrl = "mlhls://localhost/itag/233/mediadata.m3u8"

    @Test
    fun outOfOrderAndDuplicateResponsesNeverCompleteAnotherRendition() {
        val requests = FcupRequests()
        requests.reset(1)
        val video = requests.acquire(1, videoUrl) { true }!!
        val audio = requests.acquire(1, audioUrl) { true }!!
        val audioPlaylist = byteArrayOf(1)
        val videoPlaylist = byteArrayOf(2)

        assertFalse(requests.complete(audio.id, videoUrl, audioPlaylist))
        assertTrue(requests.complete(audio.id, audioUrl, audioPlaylist))
        assertFalse(requests.complete(audio.id, audioUrl, audioPlaylist))
        assertFalse(video.result.isDone)
        assertArrayEquals(audioPlaylist, audio.result.join())
        assertTrue(requests.complete(video.id, videoUrl, videoPlaylist))
        assertArrayEquals(videoPlaylist, video.result.join())
    }

    @Test
    fun identifiedResponseWithoutDataFailsOnlyItsOwnRequest() {
        val requests = FcupRequests()
        requests.reset(1)
        val audio = requests.acquire(1, audioUrl) { true }!!
        val video = requests.acquire(1, videoUrl) { true }!!

        assertFalse(requests.complete(audio.id, videoUrl, null))
        assertSame(audio, requests.pending(audio.id))
        assertTrue(requests.complete(audio.id, audioUrl, null))
        assertNull(requests.pending(audio.id))
        val failure = runCatching { audio.result.join() }.exceptionOrNull()
        assertTrue(failure?.cause is FcupRequests.MissingResponseData)
        assertSame(video, requests.pending(video.id))

        val retry = requests.acquire(1, audioUrl) { true }!!
        assertNotEquals(audio.id, retry.id)
        assertFalse(requests.complete(audio.id, audioUrl, byteArrayOf(1)))
        assertFalse(retry.result.isDone)
        assertTrue(requests.complete(video.id, videoUrl, byteArrayOf(2)))
        assertArrayEquals(byteArrayOf(2), video.result.join())
    }

    @Test
    fun concurrentReadersShareARequestButLaterReloadGetsFreshPlaylist() {
        val requests = FcupRequests()
        requests.reset(1)
        val initial = requests.acquire(1, videoUrl) { true }!!
        assertSame(initial, requests.acquire(1, videoUrl) { error("Duplicate request") })
        requests.complete(initial.id, videoUrl, byteArrayOf(1))

        val reload = requests.acquire(1, videoUrl) { true }!!
        assertNotEquals(initial.id, reload.id)
        assertFalse(reload.result.isDone)
        requests.complete(reload.id, videoUrl, byteArrayOf(2))
        assertArrayEquals(byteArrayOf(2), reload.result.join())
    }

    @Test
    fun rejectedEnqueueDoesNotLeaveALoadWaitingForAnUnsentRequest() {
        val requests = FcupRequests()
        requests.reset(1)
        assertNull(requests.acquire(1, videoUrl) { false })
        val retry = requests.acquire(1, videoUrl) { true }!!
        assertTrue(requests.complete(retry.id, videoUrl, byteArrayOf(1)))
    }

    @Test
    fun reverseDeliveryStateIsSpecificToEachRequest() {
        val requests = FcupRequests()
        requests.reset(1)
        val audio = requests.acquire(1, audioUrl) { true }!!
        val video = requests.acquire(1, videoUrl) { true }!!
        assertEquals(FcupRequests.Delivery.QUEUED, audio.delivery)
        audio.markWritten()
        assertEquals(FcupRequests.Delivery.WRITTEN, audio.delivery)
        audio.markAcknowledged()
        assertEquals(FcupRequests.Delivery.ACKNOWLEDGED, audio.delivery)
        assertEquals(FcupRequests.Delivery.QUEUED, video.delivery)
    }

    @Test
    fun replacementAndTimeoutInvalidateOldRequests() {
        val requests = FcupRequests()
        requests.reset(1)
        val old = requests.acquire(1, videoUrl) { true }!!
        requests.reset(2)
        assertTrue(old.result.isCancelled)
        assertNull(requests.acquire(1, videoUrl) { error("Old playback requested data") })
        val timedOut = requests.acquire(2, videoUrl) { true }!!
        requests.cancel(timedOut)
        val retry = requests.acquire(2, videoUrl) { true }!!

        assertFalse(requests.complete(old.id, videoUrl, byteArrayOf(1)))
        assertFalse(requests.complete(timedOut.id, videoUrl, byteArrayOf(2)))
        assertFalse(retry.result.isDone)
        assertTrue(requests.complete(retry.id, videoUrl, byteArrayOf(3)))
    }
}
