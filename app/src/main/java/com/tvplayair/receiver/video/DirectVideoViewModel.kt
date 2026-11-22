package com.tvplayair.receiver.video

import android.app.Application
import android.os.IBinder
import androidx.lifecycle.AndroidViewModel

class DirectVideoViewModel(application: Application) : AndroidViewModel(application) {
    var sessionBinder: IBinder? = null
    var player = WebVideoPlayer(application)
        private set
    private var ownsPlayer = true

    internal fun adopt(retained: DirectPlaybackService.RetainedPlayback) {
        if (player !== retained.player && ownsPlayer) player.release()
        player = retained.player
        sessionBinder = retained.connection.remoteBinder
        ownsPlayer = true
    }

    internal fun relinquishPlayer() {
        ownsPlayer = false
    }

    override fun onCleared() {
        if (ownsPlayer) player.release()
        super.onCleared()
    }
}
