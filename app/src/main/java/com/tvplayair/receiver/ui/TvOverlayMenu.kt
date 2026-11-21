package com.tvplayair.receiver.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

private val ControlSurface = Color(0xFF151515)
private val PrimaryText = Color(0xFFF2F2F2)
private val SecondaryText = Color(0xFFB0B0B0)
private val FocusColor = Color(0xFFB8DDFF)

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
        if (state.showStandby) TvStandbyScreen(state, onOpenMenu)
        return
    }

    val initialFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        initialFocus.requestFocus()
    }

    Box(
        modifier = Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 22.dp),
        contentAlignment = if (state.showStandby) Alignment.Center else Alignment.CenterEnd
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 400.dp)
                .fillMaxWidth()
                .background(Color.Black, RoundedCornerShape(16.dp))
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Ajustes",
                modifier = Modifier.padding(bottom = 8.dp),
                style = MaterialTheme.typography.headlineSmall,
                color = PrimaryText
            )
            SettingRow("Receptor", state.receiverName, onCycleReceiverName, Modifier.focusRequester(initialFocus))
            SettingRow("Resolución anunciada", state.resolutionPreset.label, onToggleResolution)
            SettingRow("Códec anunciado", if (state.hevcEnabled) "HEVC" else "H.264", onToggleHevc)

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Desfase de audio", color = SecondaryText)
                Text("${state.audioOffsetMs} ms", color = PrimaryText)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TvControl(
                    onClick = { onAdjustAudioOffset(-TvOverlayState.OFFSET_STEP_MS) },
                    modifier = Modifier.weight(1f)
                ) { Text("−10 ms", Modifier.fillMaxWidth(), textAlign = TextAlign.Center) }
                TvControl(
                    onClick = { onAdjustAudioOffset(TvOverlayState.OFFSET_STEP_MS) },
                    modifier = Modifier.weight(1f)
                ) { Text("+10 ms", Modifier.fillMaxWidth(), textAlign = TextAlign.Center) }
            }
            TvControl(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text("Cerrar ajustes")
            }
        }
    }
}

@Composable
private fun TvStandbyScreen(state: TvOverlayState, onOpenMenu: () -> Unit) {
    val settingsFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        settingsFocus.requestFocus()
    }

    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black).padding(horizontal = 48.dp, vertical = 24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = state.receiverName,
                style = MaterialTheme.typography.displayMedium,
                color = PrimaryText,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = if (state.connectedClient != null) "Dispositivo conectado"
                else "Selecciona este receptor desde AirPlay.",
                color = SecondaryText,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center
            )
            TvControl(onClick = onOpenMenu, modifier = Modifier.padding(top = 8.dp).focusRequester(settingsFocus)) {
                Text("Ajustes")
            }
        }
    }
}

@Composable
private fun SettingRow(
    label: String,
    value: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    TvControl(onClick = onClick, modifier = modifier.fillMaxWidth()) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label)
            Text(value, Modifier.weight(1f), textAlign = TextAlign.End, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun TvControl(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp),
        shape = ButtonDefaults.shape(shape = RoundedCornerShape(10.dp)),
        scale = ButtonDefaults.scale(focusedScale = 1f),
        colors = ButtonDefaults.colors(
            containerColor = ControlSurface,
            contentColor = PrimaryText,
            focusedContainerColor = FocusColor,
            focusedContentColor = Color.Black,
            pressedContainerColor = Color.White,
            pressedContentColor = Color.Black
        ),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)
    ) {
        content()
    }
}
