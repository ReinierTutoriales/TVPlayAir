package com.tvplayair.receiver

import android.os.Bundle
import androidx.activity.ComponentActivity
import com.tvplayair.receiver.audio.OboeAudioConsumer
import com.tvplayair.receiver.session.SessionConnection

class MainActivity : ComponentActivity() {
    private lateinit var audioConsumer: OboeAudioConsumer
    private lateinit var sessionConnection: SessionConnection

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        audioConsumer = OboeAudioConsumer(this)
        sessionConnection = SessionConnection(
            context = this,
            onSharedMemoryReady = { audioConsumer.start() },
            onSharedMemoryDetached = { audioConsumer.stop() }
        )
        sessionConnection.connect()
    }

    override fun onDestroy() {
        audioConsumer.stop()
        sessionConnection.close()
        super.onDestroy()
    }
}
