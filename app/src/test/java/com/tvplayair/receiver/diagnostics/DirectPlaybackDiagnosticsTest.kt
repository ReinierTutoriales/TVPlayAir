package com.tvplayair.receiver.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectPlaybackDiagnosticsTest {
    @Test
    fun onlyTheMatchingLoadResolvesTheFirstIncident() {
        var elapsed = 0L
        val logs = mutableListOf<String>()
        val diagnostics = DirectPlaybackDiagnostics({ elapsed }, { logs.add(it) })
        diagnostics.start(8)
        diagnostics.loadError(8, 10, "http=504")
        diagnostics.loadError(8, 11, "http=502")
        diagnostics.loadCompleted(8, 11)
        diagnostics.playing(7)
        elapsed = 50
        diagnostics.loadCompleted(8, 10)

        assertEquals(3, logs.size)
        assertTrue(logs.last().endsWith("outcome=load_completed task=10 errors=2 elapsedMs=50 http=504"))
    }

    @Test
    fun replacementResolvesOldIncidentAndRejectsLateCallbacks() {
        val logs = mutableListOf<String>()
        val diagnostics = DirectPlaybackDiagnostics({ 0L }, { logs.add(it) })
        diagnostics.start(7)
        diagnostics.loadError(7, 10, "http=504")
        diagnostics.start(8)
        diagnostics.loadError(7, 11, "http=502")
        diagnostics.loadError(8, 12, "http=403")
        diagnostics.loadCompleted(7, 12)
        diagnostics.playing(8)
        diagnostics.finish("ended")
        diagnostics.finish("cleared")

        val summaries = logs.filter { "event=load_summary" in it }
        assertEquals(2, summaries.size)
        assertTrue(summaries[0].contains("id=7 event=load_summary outcome=replaced"))
        assertTrue(summaries[1].contains("id=8 event=load_summary outcome=playing"))
        assertEquals(2, logs.count { "event=finish" in it })
    }
}
