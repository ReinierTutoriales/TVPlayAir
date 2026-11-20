package com.tvplayair.receiver.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import com.tvplayair.receiver.video.PresentationState

@Composable
fun TvOverlayMenu(
    state: TvOverlayState,
    onOpenMenu: () -> Unit,
    onCycleReceiverName: () -> Unit,
    onToggleResolution: () -> Unit,
    onToggleHevc: () -> Unit,
    onAdjustAudioOffset: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    if (!state.visible) {
        if (state.presentationState == PresentationState.IDLE && state.connectedClient == null) {
            TvStandbyScreen(state = state, onOpenMenu = onOpenMenu)
        }
        return
    }

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
                .width(380.dp)
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
                onClick = onCycleReceiverName,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(firstControlFocus)
            ) {
                Text("Dispositivo: ${state.receiverName}")
            }

            Button(
                onClick = onToggleResolution,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Resolución: ${state.resolutionPreset.label}")
            }

            Button(
                onClick = onToggleHevc,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("HEVC 10-bit: ${if (state.hevcEnabled) "ON" else "OFF"}")
            }

            Text(
                text = "Offset de audio: ${state.audioOffsetMs} ms",
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
                    text = "Cliente: ${state.connectedClient ?: "En espera"}",
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
                        if (state.hidAvailable) "Listo" else "Off",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFD8DEE9)
                )
            }

            Button(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Cerrar")
            }
        }
    }
}

@Composable
private fun TvStandbyScreen(
    state: TvOverlayState,
    onOpenMenu: () -> Unit
) {
    val settingsFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        settingsFocus.requestFocus()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0C1017)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = state.receiverName,
                style = MaterialTheme.typography.displaySmall,
                color = Color.White
            )
            Text(
                text = "Listo para AirPlay  •  ${state.resolutionPreset.label}  •  " +
                    if (state.hevcEnabled) "H.265/HEVC" else "H.264",
                style = MaterialTheme.typography.titleMedium,
                color = Color(0xFF88C0D0)
            )
            Text(
                text = "Red local: ${state.localIpAddress}:7000",
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFF9AA7B8)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = onOpenMenu,
                modifier = Modifier.focusRequester(settingsFocus)
            ) {
                Text("Ajustes (OK)")
            }
        }
    }
}
