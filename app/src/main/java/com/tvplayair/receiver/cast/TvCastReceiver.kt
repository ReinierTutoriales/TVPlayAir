package com.tvplayair.receiver.cast

import android.content.Context
import android.content.Intent
import android.media.session.MediaSession
import android.os.Handler
import android.os.Looper
import android.support.v4.media.session.MediaSessionCompat
import com.google.android.gms.cast.MediaError
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.tv.CastReceiverContext
import com.google.android.gms.cast.tv.CastReceiverOptions
import com.google.android.gms.cast.tv.ReceiverOptionsProvider
import com.google.android.gms.cast.tv.media.MediaException
import com.google.android.gms.cast.tv.media.MediaLoadCommandCallback
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.TaskCompletionSource

class TvCastOptions : ReceiverOptionsProvider {
    override fun getOptions(context: Context): CastReceiverOptions =
        CastReceiverOptions.Builder(context).build()
}

internal class TvCastReceiver(
    context: Context,
    onLoad: (CastMediaRequest) -> Boolean
) {
    private var loadHandler: ((CastMediaRequest) -> Boolean)? = onLoad

    fun replaceLoadHandler(handler: ((CastMediaRequest) -> Boolean)?) {
        loadHandler = handler
    }

    private val handler = Handler(Looper.getMainLooper())
    private val receiver = run {
        CastReceiverContext.initInstance(context.applicationContext)
        CastReceiverContext.getInstance()
    }
    private val manager = receiver.mediaManager
    private var started = false

    private val loadCallback = object : MediaLoadCommandCallback() {
        override fun onLoad(senderId: String?, requestData: MediaLoadRequestData): Task<MediaLoadRequestData> {
            val result = TaskCompletionSource<MediaLoadRequestData>()
            handler.post {
                val media = requestData.mediaInfo
                val request = CastMediaRequest.from(
                    media?.contentUrl ?: media?.contentId, requestData.currentTime, requestData.autoplay != false
                )
                if (started && requestData.queueData == null && request != null && loadHandler?.invoke(request) == true) {
                    manager.setDataFromLoad(requestData)
                    manager.broadcastMediaStatus()
                    result.setResult(requestData)
                } else {
                    result.setException(
                        MediaException(
                            MediaError.Builder()
                                .setDetailedErrorCode(MediaError.DetailedErrorCode.LOAD_FAILED)
                                .setReason(MediaError.ERROR_REASON_INVALID_REQUEST)
                                .build()
                        )
                    )
                }
            }
            return result.task
        }
    }

    fun start() {
        if (started) return
        manager.setMediaLoadCommandCallback(loadCallback)
        receiver.start()
        started = true
    }

    fun handleIntent(intent: Intent): Boolean = started && manager.onNewIntent(intent)

    fun attachSession(token: MediaSession.Token?) {
        manager.setSessionCompatToken(token?.let(MediaSessionCompat.Token::fromToken))
        if (started) manager.broadcastMediaStatus()
    }

    fun stop() {
        if (!started) return
        started = false
        attachSession(null)
        manager.setMediaLoadCommandCallback(null)
        receiver.stop()
    }
}
