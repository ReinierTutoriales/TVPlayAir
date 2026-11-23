package com.tvplayair.receiver.session

import java.io.IOException
import java.util.concurrent.CompletableFuture

/** Shares concurrent loads of one resource. Each later HLS reload gets a new request. */
internal class FcupRequests {
    enum class Delivery { QUEUED, WRITTEN, ACKNOWLEDGED }

    class Request(val id: Int, val url: String) {
        @Volatile
        var delivery = Delivery.QUEUED
            private set

        fun markWritten() { delivery = Delivery.WRITTEN }
        fun markAcknowledged() { delivery = Delivery.ACKNOWLEDGED }
        val result = CompletableFuture<ByteArray>()
    }

    private var playbackId: Long? = null
    private var nextId = 1
    private val byId = mutableMapOf<Int, Request>()
    private val byUrl = mutableMapOf<String, Request>()

    @Synchronized
    fun reset(playbackId: Long? = null) {
        val previous = byId.values.toList()
        this.playbackId = playbackId
        byId.clear()
        byUrl.clear()
        previous.forEach { it.result.cancel(false) }
    }

    @Synchronized
    fun acquire(playbackId: Long, url: String, enqueue: (Int) -> Boolean): Request? {
        if (this.playbackId != playbackId) return null
        byUrl[url]?.let { return it }
        val request = Request(nextId++, url)
        byId[request.id] = request
        byUrl[url] = request
        if (enqueue(request.id)) return request
        remove(request)
        return null
    }

    class SenderHttpError(val status: Int) : IOException("FCUP sender HTTP $status")

    class MissingResponseData : IOException("FCUP response has no data")

    @Synchronized
    fun pending(id: Int): Request? = byId[id]

    @Synchronized
    fun fail(id: Int, reason: String): Boolean {
        val request = byId[id] ?: return false
        remove(request)
        return request.result.completeExceptionally(IOException(reason))
    }

    @Synchronized
    fun complete(id: Int?, url: String?, data: ByteArray?, senderStatus: Int? = null): Boolean {
        val request = (if (id != null) byId[id] else byUrl[url]) ?: return false
        if (url != null && request.url != url) return false
        remove(request)
        return when {
            senderStatus != null && senderStatus in 400..599 ->
                request.result.completeExceptionally(SenderHttpError(senderStatus))
            data == null || data.isEmpty() -> request.result.completeExceptionally(MissingResponseData())
            else -> request.result.complete(data)
        }
    }

    @Synchronized
    fun cancel(request: Request) {
        if (byId[request.id] !== request) return
        remove(request)
        request.result.cancel(false)
    }

    private fun remove(request: Request) {
        byId.remove(request.id)
        byUrl.remove(request.url)
    }
}
