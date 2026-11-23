package com.tvplayair.receiver.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.tvplayair.receiver.video.AudioHandoffController

/**
 * Manages the native Google Oboe audio consumer and API 29+ AudioFocus lifecycle.
 * Reads normalized PCM 16-bit / 48 kHz stereo frames from the consumer SharedRingBuffer.
 */
class OboeAudioConsumer(context: Context) : AudioHandoffController {
    companion object {
        private const val TAG = "OboeAudioConsumer"
        private const val SENDER_STOP_AUDIO_RESUME_DELAY_MS = 850L
        const val MIN_OFFSET_MS = -200
        const val MAX_OFFSET_MS = 200

        init {
            System.loadLibrary("tvplayair_native")
        }
    }

    private val audioManager: AudioManager =
        context.applicationContext.getSystemService(AudioManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var active = false
    private var pausedForExternalPlayer = false
    private var hasFocus = false
    private var currentGenerationId = 0
    private var interruptedGeneration: Int? = null

    private val resumeExistingAudio = Runnable {
        setPausedForExternalPlayer(false)
    }
    private var resumeScheduled = false

    override fun resumeAfterSenderStop() {
        if (!active) {
            setPausedForExternalPlayer(false)
            return
        }
        mainHandler.removeCallbacks(resumeExistingAudio)
        resumeScheduled = true
        mainHandler.postDelayed(resumeExistingAudio, SENDER_STOP_AUDIO_RESUME_DELAY_MS)
    }

    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                hasFocus = true
                nativeSetDucked(false)
                nativeSetMuted(false)
                if (active && !pausedForExternalPlayer && !nativeIsRunning()) {
                    if (!startOutput()) {
                        Log.e(TAG, "Failed to restart Oboe stream on AUDIOFOCUS_GAIN")
                    }
                }
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                hasFocus = false
                nativeStop()
                abandonAudioFocus()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                hasFocus = false
                nativeSetMuted(true)
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                nativeSetDucked(true)
            }
        }
    }

    private val audioFocusRequest: AudioFocusRequest =
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build()
            )
            .setAcceptsDelayedFocusGain(true)
            .setOnAudioFocusChangeListener(focusChangeListener, mainHandler)
            .build()

    fun start(generationId: Int = currentGenerationId): Boolean {
        currentGenerationId = generationId
        active = true
        if (resumeScheduled) {
            cancelScheduledResume()
            pausedForExternalPlayer = false
        }
        Log.i(TAG, "start(gen=$generationId) pausedForExternal=$pausedForExternalPlayer running=${isRunning()}")
        if (pausedForExternalPlayer) {
            interruptedGeneration = generationId
            return false
        }
        return requestFocusAndStart()
    }

    override fun stop() {
        Log.i(TAG, "stop(gen=$currentGenerationId)")
        active = false
        interruptedGeneration = null
        if (resumeScheduled) setPausedForExternalPlayer(false)
        nativeStop()
        abandonAudioFocus()
    }

    /**
     * Suspends mirror Oboe playback and yields AudioFocus when ExoPlayer takes over
     * direct web video playback, and restores focus + playback when returning to mirror.
     */
    override fun setPausedForExternalPlayer(paused: Boolean) {
        cancelScheduledResume()
        if (pausedForExternalPlayer == paused) return
        pausedForExternalPlayer = paused
        if (paused) {
            interruptedGeneration = currentGenerationId.takeIf { active }
            nativeStop()
            abandonAudioFocus()
        } else if (active) {
            requestFocusAndStart()
        }
    }

    private fun cancelScheduledResume() {
        if (!resumeScheduled) return
        mainHandler.removeCallbacks(resumeExistingAudio)
        resumeScheduled = false
    }

    fun setGeneration(generationId: Int) {
        currentGenerationId = generationId
        nativeSetGeneration(generationId)
    }

    fun setAudioOffsetMs(offsetMs: Int) {
        nativeSetOffsetMs(offsetMs.coerceIn(MIN_OFFSET_MS, MAX_OFFSET_MS))
    }

    fun getAudioOffsetMs(): Int = nativeGetOffsetMs()

    fun isRunning(): Boolean = nativeIsRunning()

    fun getUnderrunCount(): Long = nativeGetUnderrunCount()

    fun getRecoveryCount(): Long = nativeGetRecoveryCount()

    fun getGrantedSampleRate(): Int = nativeGetGrantedSampleRate()

    fun getGrantedFramesPerBurst(): Int = nativeGetGrantedFramesPerBurst()

    fun isExclusiveMode(): Boolean = nativeIsExclusiveMode()

    private fun requestFocusAndStart(): Boolean {
        if (hasFocus) {
            nativeSetDucked(false)
            nativeSetMuted(false)
            return if (nativeIsRunning()) true else startOutput()
        }
        return when (audioManager.requestAudioFocus(audioFocusRequest)) {
            AudioManager.AUDIOFOCUS_REQUEST_GRANTED -> {
                hasFocus = true
                nativeSetDucked(false)
                nativeSetMuted(false)
                startOutput()
            }
            AudioManager.AUDIOFOCUS_REQUEST_DELAYED -> {
                hasFocus = false
                Log.i(TAG, "AudioFocus delayed; deferring Oboe start until AUDIOFOCUS_GAIN")
                false
            }
            else -> {
                hasFocus = false
                Log.w(TAG, "AudioFocus request denied; Oboe stream not started")
                false
            }
        }
    }

    private fun abandonAudioFocus() {
        audioManager.abandonAudioFocusRequest(audioFocusRequest)
        hasFocus = false
    }

    private fun startOutput(): Boolean {
        val discardInterruptedAudio = interruptedGeneration == currentGenerationId
        val started = nativeStart(currentGenerationId, discardInterruptedAudio)
        if (started) interruptedGeneration = null
        return started
    }

    private external fun nativeStart(generationId: Int, discardInterruptedAudio: Boolean): Boolean
    private external fun nativeStop()
    private external fun nativeSetGeneration(generationId: Int)
    private external fun nativeSetOffsetMs(offsetMs: Int)
    private external fun nativeGetOffsetMs(): Int
    private external fun nativeSetMuted(muted: Boolean)
    private external fun nativeSetDucked(ducked: Boolean)
    private external fun nativeIsRunning(): Boolean
    private external fun nativeGetUnderrunCount(): Long
    private external fun nativeGetRecoveryCount(): Long
    private external fun nativeGetGrantedSampleRate(): Int
    private external fun nativeGetGrantedFramesPerBurst(): Int
    private external fun nativeIsExclusiveMode(): Boolean
}
