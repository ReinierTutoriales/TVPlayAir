package com.tvplayair.receiver.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.tvplayair.receiver.R
import com.tvplayair.receiver.control.DacpCommand
import com.tvplayair.receiver.video.DirectPlaybackTelemetry
import java.util.Locale

private val ControlSurface = Color(0xFF151515)
private val PrimaryText = Color(0xFFF2F2F2)
private val SecondaryText = Color(0xFFB0B0B0)
private val FocusColor = Color(0xFF9BE4CF)

@Composable
fun TvOverlayMenu(
    state: TvOverlayState,
    onOpenMenu: () -> Unit,
    onCycleReceiverName: () -> Unit,
    onToggleResolution: () -> Unit,
    onToggleHevc: () -> Unit,
    onAdjustAudioOffset: (Int) -> Unit,
    onAudioCommand: (DacpCommand) -> Unit,
    onVideoToggle: () -> Unit,
    onPlaybackInteraction: () -> Unit,
    onDismiss: () -> Unit
) {
    if (!state.visible) {
        val remoteCommand = onAudioCommand.takeIf {
            state.audioGeneration?.let(state::canSendAudioControl) == true
        }
        when {
            state.showingDirectAudio || state.showingAudio -> {
                val direct = state.directAudio.takeIf { state.showingDirectAudio }
                val remote = state.nowPlaying.takeIf { direct == null }
                TvNowPlayingScreen(
                    title = direct?.title ?: remote?.track?.title,
                    artist = direct?.artist ?: remote?.track?.artist,
                    album = direct?.album ?: remote?.track?.album,
                    artwork = direct?.artwork ?: remote?.artwork
                ) {
                    if (direct != null && direct.durationSec.isFinite() && direct.durationSec > 0.0) {
                        PlaybackProgress(direct)
                        Text(
                            "${videoTime(direct.positionSec)} / ${videoTime(direct.durationSec)}",
                            color = SecondaryText,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    TvPlaybackControls(
                        telemetry = direct,
                        onToggle = onVideoToggle,
                        onAudioCommand = remoteCommand
                    )
                }
            }
            state.showingMirrorControls -> {
                Box(
                    modifier = Modifier.fillMaxSize().onPreviewKeyEvent {
                        if (it.type == KeyEventType.KeyDown) onPlaybackInteraction()
                        false
                    }.padding(bottom = 32.dp),
                    contentAlignment = Alignment.BottomCenter
                ) {
                    TvPlaybackControls(
                        telemetry = null,
                        onToggle = onVideoToggle,
                        onAudioCommand = remoteCommand
                    )
                }
            }
            state.showingVideoControls -> {
                state.videoControls?.let { TvVideoControls(it, onVideoToggle, onPlaybackInteraction) }
            }
            state.showStandby && !state.projectionHome -> TvStandbyScreen(state, onOpenMenu)
        }
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
            SettingRow("Límite de resolución", state.resolutionPreset.label, onToggleResolution)
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
private fun TvVideoControls(
    telemetry: DirectPlaybackTelemetry,
    onToggle: () -> Unit,
    onInteraction: () -> Unit
) {
    val hasDuration = telemetry.durationSec.isFinite() && telemetry.durationSec > 0.0
    Box(
        Modifier.fillMaxSize().onPreviewKeyEvent {
            if (it.type == KeyEventType.KeyDown) onInteraction()
            false
        },
        contentAlignment = Alignment.BottomCenter
    ) {
        Column(
            modifier = Modifier.fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xF5000000))))
                .padding(horizontal = 48.dp)
                .padding(top = 72.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                Text(
                    text = telemetry.title ?: stringResource(R.string.video_playback),
                    modifier = Modifier.weight(1f),
                    color = PrimaryText,
                    style = MaterialTheme.typography.headlineMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (hasDuration) {
                    Text(
                        "${videoTime(telemetry.positionSec)} / ${videoTime(telemetry.durationSec)}",
                        color = PrimaryText,
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            }
            if (hasDuration) PlaybackProgress(telemetry)
            TvPlaybackControls(telemetry, onToggle, Modifier.align(Alignment.CenterHorizontally))
            if (telemetry.isBuffering) {
                Text(
                    stringResource(R.string.video_loading),
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                    color = SecondaryText,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

@Composable
private fun PlaybackProgress(telemetry: DirectPlaybackTelemetry) {
    val progress = (telemetry.positionSec / telemetry.durationSec).toFloat()
        .takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
    Box(Modifier.fillMaxWidth().height(4.dp).background(Color.White.copy(alpha = 0.28f))) {
        Box(Modifier.fillMaxWidth(progress).height(4.dp).background(FocusColor))
    }
}

@Composable
private fun TvPlaybackControls(
    telemetry: DirectPlaybackTelemetry?,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    onAudioCommand: ((DacpCommand) -> Unit)? = null
) {
    val playFocus = remember { FocusRequester() }
    val canToggle = telemetry != null || onAudioCommand != null
    LaunchedEffect(canToggle) {
        if (canToggle) playFocus.requestFocus()
    }
    TvMediaControlGroup(modifier) {
        TvMediaControl(
            label = stringResource(R.string.audio_previous),
            enabled = onAudioCommand != null,
            onClick = { onAudioCommand?.invoke(DacpCommand.PREVIOUS) }
        ) { MediaControlGlyph(MediaGlyph.Previous) }

        val (label, glyph) = when (telemetry?.playWhenReady) {
            true -> R.string.video_pause to MediaGlyph.Pause
            false -> R.string.video_play to MediaGlyph.Play
            null -> R.string.audio_play_pause to MediaGlyph.PlayPause
        }
        TvMediaControl(
            label = stringResource(label),
            onClick = {
                if (telemetry != null) onToggle()
                else onAudioCommand?.invoke(DacpCommand.PLAY_PAUSE)
            },
            modifier = Modifier.focusRequester(playFocus),
            primary = true,
            enabled = canToggle
        ) { MediaControlGlyph(glyph) }

        TvMediaControl(
            label = stringResource(R.string.audio_next),
            enabled = onAudioCommand != null,
            onClick = { onAudioCommand?.invoke(DacpCommand.NEXT) }
        ) { MediaControlGlyph(MediaGlyph.Next) }
    }
}

private fun videoTime(seconds: Double): String {
    val total = seconds.toLong().coerceAtLeast(0L)
    return if (total >= 3600) {
        String.format(Locale.ROOT, "%d:%02d:%02d", total / 3600, total / 60 % 60, total % 60)
    } else {
        String.format(Locale.ROOT, "%d:%02d", total / 60, total % 60)
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
