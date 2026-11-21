package com.tvplayair.receiver.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.tvplayair.receiver.video.PresentationState

private val BgTop = Color(0xFF000000)
private val BgMid = Color(0xFF050507)
private val BgBottom = Color(0xFF000000)
private val CardBg = Color(0xFF151517)
private val DrawerBg = Color(0xF50E0E10)
private val CardBorder = Color(0xFF242426)
private val AccentCyan = Color(0xFF38BDF8)
private val AccentGreen = Color(0xFF34D399)
private val TextPrimary = Color(0xFFF8FAFC)
private val TextSecondary = Color(0xFF94A3B8)
private val PillBg = Color(0xFF1C1C1E)

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
        if (state.presentationState == PresentationState.IDLE) {
            TvStandbyScreen(
                state = state,
                onOpenMenu = onOpenMenu,
                onCycleReceiverName = onCycleReceiverName
            )
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
            .background(Color(0x88040812))
            .padding(24.dp),
        contentAlignment = Alignment.CenterEnd
    ) {
        Column(
            modifier = Modifier
                .width(410.dp)
                .fillMaxHeight()
                .clip(RoundedCornerShape(24.dp))
                .background(DrawerBg)
                .border(1.dp, CardBorder, RoundedCornerShape(24.dp))
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "TVPlayAir",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary
                    )
                    Text(
                        text = "Configuración y telemetría",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
                StatusChip(
                    label = if (state.connectedClient != null) "Conectado" else "En espera",
                    dotColor = if (state.connectedClient != null) AccentGreen else AccentCyan
                )
            }

            SectionLabel("RECEPTOR EN RED")
            Button(
                onClick = onCycleReceiverName,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(firstControlFocus)
            ) {
                Text("Ubicación: ${state.receiverName}")
            }

            SectionLabel("VIDEO Y AUDIO")
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
                Text("Códec H.265 / HEVC: ${if (state.hevcEnabled) "Activado" else "Desactivado (H.264)"}")
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(PillBg)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Button(onClick = { onAdjustAudioOffset(-TvOverlayState.OFFSET_STEP_MS) }) {
                    Text("-10 ms")
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "Sincronía audio",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )
                    Text(
                        text = "${state.audioOffsetMs} ms",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                }
                Button(onClick = { onAdjustAudioOffset(TvOverlayState.OFFSET_STEP_MS) }) {
                    Text("+10 ms")
                }
            }

            SectionLabel("TELEMETRÍA EN VIVO")
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFF0A0A0C))
                    .border(1.dp, CardBorder, RoundedCornerShape(14.dp))
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                TelemetryRow("Motor VPU", state.presentationState.name, AccentCyan)
                TelemetryRow("Cliente", state.connectedClient ?: "Ninguno", TextPrimary)
                TelemetryRow(
                    "Audio Oboe",
                    "${if (state.oboeExclusive) "Exclusive" else "Shared"} · ${state.oboeSampleRate} Hz",
                    AccentGreen
                )
                TelemetryRow(
                    "Estabilidad",
                    "underruns=${state.oboeUnderruns} · rec=${state.oboeRecoveries} · IDR=${state.mirrorDroppedNonIdr}",
                    TextSecondary
                )
                TelemetryRow(
                    "Red / HID",
                    "${state.localIpAddress}:7000 · BT HID ${if (state.hidAvailable) "Listo" else "Off"}",
                    TextSecondary
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            Button(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Cerrar (Atrás)")
            }
        }
    }
}

@Composable
private fun TvStandbyScreen(
    state: TvOverlayState,
    onOpenMenu: () -> Unit,
    onCycleReceiverName: () -> Unit
) {
    val settingsFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        settingsFocus.requestFocus()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(BgTop, BgMid, BgBottom)))
            .padding(horizontal = 56.dp, vertical = 40.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Top bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(14.dp)
                            .clip(CircleShape)
                            .background(AccentCyan)
                    )
                    Text(
                        text = "TVPlayAir",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatusChip(
                        label = if (state.hevcEnabled) "4K · H.265 HDR" else "H.264",
                        dotColor = AccentCyan
                    )
                    StatusChip(
                        label = state.connectedClient?.let { "Conectado: $it" } ?: "AirPlay Activo",
                        dotColor = AccentGreen
                    )
                }
            }

            // Main two-card hero layout
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(28.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Left Hero Card: Receiver Name + 3-step iPhone guide + OK actions
                Column(
                    modifier = Modifier
                        .weight(1.35f)
                        .clip(RoundedCornerShape(24.dp))
                        .background(CardBg)
                        .border(1.dp, CardBorder, RoundedCornerShape(24.dp))
                        .padding(32.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = "RECEPTOR LISTO EN ESTA TV",
                        style = MaterialTheme.typography.labelMedium,
                        color = AccentCyan
                    )
                    Text(
                        text = state.receiverName,
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )

                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        GuideStep("1", "Conecta tu iPhone, iPad o Mac al mismo Wi-Fi")
                        GuideStep("2", "Abre Centro de control → Duplicar pantalla o AirPlay")
                        GuideStep("3", "Selecciona \"${state.receiverName}\" para transmitir")
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Button(
                            onClick = onOpenMenu,
                            modifier = Modifier.focusRequester(settingsFocus)
                        ) {
                            Text("Ajustes (OK)")
                        }
                        Button(onClick = onCycleReceiverName) {
                            Text("Cambiar habitación")
                        }
                    }
                }

                // Right Capabilities & Network Card
                Column(
                    modifier = Modifier
                        .weight(0.95f)
                        .clip(RoundedCornerShape(24.dp))
                        .background(CardBg)
                        .border(1.dp, CardBorder, RoundedCornerShape(24.dp))
                        .padding(28.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text(
                        text = "ESTADO DEL SISTEMA",
                        style = MaterialTheme.typography.labelMedium,
                        color = TextSecondary
                    )
                    TelemetryRow("Red local", "${state.localIpAddress}:7000", TextPrimary)
                    TelemetryRow("Resolución", state.resolutionPreset.label, AccentCyan)
                    TelemetryRow(
                        "Video nativo",
                        if (state.hevcEnabled) "HEVC 10-bit · SurfaceView" else "H.264 · SurfaceView",
                        TextPrimary
                    )
                    TelemetryRow("Audio", "Google Oboe · 48 kHz", AccentGreen)
                    TelemetryRow("Control sofá", "DACP Activo · BT HID ${if (state.hidAvailable) "Listo" else "Off"}", TextSecondary)
                    TelemetryRow("Segundo plano", "Servicio activo", AccentGreen)
                }
            }

            // Subtle footer hint
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Navega con D-Pad y pulsa OK para configurar",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
                Text(
                    text = "Puertos activos: 7000 · 5000 · 7100",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        color = AccentCyan
    )
}

@Composable
private fun StatusChip(label: String, dotColor: Color) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(PillBg)
            .border(1.dp, CardBorder, RoundedCornerShape(50))
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(dotColor)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = TextPrimary
        )
    }
}

@Composable
private fun GuideStep(number: String, text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(26.dp)
                .clip(CircleShape)
                .background(PillBg)
                .border(1.dp, AccentCyan, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = number,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = AccentCyan
            )
        }
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = TextPrimary
        )
    }
}

@Composable
private fun TelemetryRow(label: String, value: String, valueColor: Color) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = valueColor
        )
    }
}
