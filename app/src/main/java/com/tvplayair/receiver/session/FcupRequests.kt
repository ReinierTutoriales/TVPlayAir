package com.tvplayair.receiver.session

import java.io.IOException
import java.util.concurrent.CompletableFuture

/** Shares concurrent loads of one resource. Each later HLS reload gets a new request. */
internal class FcupRequests {
    class Request(val id: Int, val url: String) {
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

    class MissingResponseData : IOException("FCUP response has no data")

    @Synchronized
    fun isPending(id: Int): Boolean = byId.containsKey(id)

    @Synchronized
    fun complete(id: Int?, url: String?, data: ByteArray?): Boolean {
        val request = (if (id != null) byId[id] else byUrl[url]) ?: return false
        if (url != null && request.url != url) return false
        remove(request)
        return if (data == null || data.isEmpty()) request.result.completeExceptionally(MissingResponseData())
        else request.result.complete(data)
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
