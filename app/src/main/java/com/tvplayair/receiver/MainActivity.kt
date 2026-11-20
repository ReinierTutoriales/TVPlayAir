package com.tvplayair.receiver

import android.os.Bundle
import androidx.activity.ComponentActivity
import com.tvplayair.receiver.session.SessionConnection

class MainActivity : ComponentActivity() {
    private lateinit var sessionConnection: SessionConnection

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sessionConnection = SessionConnection(this)
        sessionConnection.connect()
    }

    override fun onDestroy() {
        sessionConnection.close()
        super.onDestroy()
    }
}
