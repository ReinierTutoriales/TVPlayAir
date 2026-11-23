package com.tvplayair.receiver.diagnostics

internal const val PLAYBACK_DIAGNOSTICS_TAG = "TVPlayAirDiag"
internal const val FCUP_REQUEST_HEADER = "X-TVPlayAir-Fcup-Request"

/** Called on Media3's application thread. A late callback cannot resolve another item's incident. */
internal class DirectPlaybackDiagnostics(
    private val nowMs: () -> Long,
    private val emit: (String) -> Unit
) {
    private data class Incident(val task: Long, val startedMs: Long, val detail: String, var errors: Int = 1)

    private var id: Long? = null
    private var startedMs = 0L
    private var incident: Incident? = null

    fun start(playbackId: Long) {
        finish("replaced")
        id = playbackId
        startedMs = nowMs()
        emit("route=direct id=$playbackId event=prepare")
    }

    fun loadError(playbackId: Long?, task: Long, detail: String) {
        if (playbackId == null || id != playbackId) return
        val current = incident
        if (current != null) {
            current.errors++
            return
        }
        incident = Incident(task, nowMs(), detail)
        emit("route=direct id=$playbackId event=load_error task=$task $detail")
    }

    fun loadCompleted(playbackId: Long?, task: Long) {
        if (playbackId != null && id == playbackId && incident?.task == task) {
            resolve(playbackId, "load_completed")
        }
    }

    fun playing(playbackId: Long?) {
        if (playbackId != null && id == playbackId) resolve(playbackId, "playing")
    }

    fun finish(outcome: String, detail: String = "") {
        val playbackId = id ?: return
        resolve(playbackId, outcome)
        emit("route=direct id=$playbackId event=finish outcome=$outcome elapsedMs=${nowMs() - startedMs}$detail")
        id = null
    }

    private fun resolve(playbackId: Long, outcome: String) {
        val first = incident ?: return
        incident = null
        emit("route=direct id=$playbackId event=load_summary outcome=$outcome " +
            "task=${first.task} errors=${first.errors} elapsedMs=${nowMs() - first.startedMs} ${first.detail}")
    }
}
