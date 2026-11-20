package com.tvplayair.receiver.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

@Composable
fun TvOverlayMenu(
    state: TvOverlayState,
    onToggleResolution: () -> Unit,
    onToggleHevc: () -> Unit,
    onAdjustAudioOffset: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    if (!state.visible) return

    BackHandler(enabled = true, onBack = onDismiss)

    val firstControlFocus = remember { FocusRequester() }
    LaunchedEffect(state.visible) {
        firstControlFocus.requestFocus()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0x66000000)),
        contentAlignment = Alignment.CenterEnd
    ) {
        Column(
            modifier = Modifier
                .width(360.dp)
                .fillMaxHeight()
                .background(Color(0xE614181F))
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "TVPlayAir",
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White
            )

            Button(
                onClick = onToggleResolution,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(firstControlFocus)
            ) {
                Text("Resolution: ${state.resolutionPreset.label}")
            }

            Button(
                onClick = onToggleHevc,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("HEVC 10-bit: ${if (state.hevcEnabled) "ON" else "OFF"}")
            }

            Text(
                text = "Audio Offset: ${state.audioOffsetMs} ms",
                style = MaterialTheme.typography.titleMedium,
                color = Color(0xFFD8DEE9)
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = { onAdjustAudioOffset(-TvOverlayState.OFFSET_STEP_MS) },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("-10 ms")
                }
                Button(
                    onClick = { onAdjustAudioOffset(TvOverlayState.OFFSET_STEP_MS) },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("+10 ms")
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0x66000000))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "VPU: ${state.presentationState.name}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFF88C0D0)
                )
                Text(
                    text = "Client: ${state.connectedClient ?: "Idle"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFD8DEE9)
                )
                Text(
                    text = "Oboe: ${if (state.oboeExclusive) "Exclusive" else "Shared"} " +
                        "${state.oboeSampleRate} Hz | underruns=${state.oboeUnderruns} " +
                        "rec=${state.oboeRecoveries}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFD8DEE9)
                )
                Text(
                    text = "IDR drops: ${state.mirrorDroppedNonIdr} | BT HID: " +
                        if (state.hidAvailable) "Ready" else "Off",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFD8DEE9)
                )
            }

            Button(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Close Menu")
            }
        }
    }
}
