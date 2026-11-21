package com.tvplayair.receiver.video

import android.app.Application
import android.os.IBinder
import androidx.lifecycle.AndroidViewModel

class DirectVideoViewModel(application: Application) : AndroidViewModel(application) {
    var sessionBinder: IBinder? = null
    val player = WebVideoPlayer(application)

    override fun onCleared() {
        player.release()
        super.onCleared()
    }
}
