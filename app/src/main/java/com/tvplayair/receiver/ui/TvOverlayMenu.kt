package com.tvplayair.receiver.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.font.FontWeight
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
import com.tvplayair.receiver.video.timelineSeekStep
import java.util.Locale

private val ControlSurface = Color(0xFF23262E)
private val PanelSurface = Color(0xF51A1D24)
private val PanelBorder = Color(0x24FFFFFF)
private val StandbyBackdrop = Brush.verticalGradient(listOf(Color(0xFF161C29), Color.Black))
private val PrimaryText = Color(0xFFF2F2F2)
private val SecondaryText = Color(0xFFB0B0B0)
private val VideoControlsScrim = Brush.verticalGradient(listOf(Color.Transparent, Color(0xE8000000)))
private val FocusColor = Color(0xFFF5F7FC)

@Composable
fun TvOverlayMenu(
    state: TvOverlayState,
    onOpenMenu: () -> Unit,
    onCycleReceiverName: () -> Unit,
    onToggleResolution: () -> Unit,
    onToggleHevc: () -> Unit,
    onToggleAutoOpenVisual: () -> Unit,
    onManageNotifications: () -> Unit,
    onToggleAutoStartReceiver: () -> Unit,
    onAdjustAudioOffset: (Int) -> Unit,
    onAudioCommand: (DacpCommand) -> Unit,
    onVideoToggle: () -> Unit,
    onSeekTo: (Double) -> Unit,
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
                        PlaybackProgress(direct, onSeekTo)
                        Text(
                            "${videoTime(direct.positionSec)} / ${videoTime(direct.durationSec)}",
                            color = SecondaryText,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    TvPlaybackControls(
                        telemetry = direct,
                        onToggle = onVideoToggle,
                        // Direct playback must never expose DACP controls from a previous audio session.
                        onAudioCommand = if (direct == null) remoteCommand else null
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
                state.videoControls?.let { TvVideoControls(it, onVideoToggle, onSeekTo, onPlaybackInteraction) }
            }
            state.showStandby && !state.projectionHome -> TvStandbyScreen(state, onOpenMenu)
        }
        return
    }

    val initialFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        initialFocus.requestFocus()
    }

    BoxWithConstraints(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = if (state.showStandby) Alignment.Center else Alignment.CenterEnd
    ) {
        val horizontalInset = (maxWidth * 0.05f).coerceIn(20.dp, 72.dp)
        val verticalInset = (maxHeight * 0.045f).coerceIn(12.dp, 40.dp)
        val panelWidth = (maxWidth - horizontalInset * 2).coerceIn(0.dp, 480.dp)
        val compact = maxHeight < 440.dp
        val shape = RoundedCornerShape(24.dp)
        Column(
            modifier = Modifier.padding(horizontal = horizontalInset, vertical = verticalInset)
                .widthIn(max = panelWidth)
                .fillMaxWidth()
                .background(PanelSurface, shape)
                .border(1.dp, PanelBorder, shape)
                .verticalScroll(rememberScrollState())
                .padding(if (compact) 16.dp else 24.dp),
            verticalArrangement = Arrangement.spacedBy(if (compact) 7.dp else 10.dp)
        ) {
            Text(
                text = "Ajustes",
                modifier = Modifier.padding(bottom = 8.dp),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
                color = PrimaryText
            )
            SettingRow("Receptor", state.receiverName, onCycleReceiverName, Modifier.focusRequester(initialFocus))
            SettingRow("Límite de resolución", state.resolutionPreset.label, onToggleResolution)
            SettingRow("Códec anunciado", if (state.hevcEnabled) "HEVC" else "H.264", onToggleHevc)
            SettingRow(
                "Iniciar receptor con el TV",
                if (state.autoStartReceiver) "Activado" else "Desactivado",
                onToggleAutoStartReceiver
            )
            SettingRow(stringResource(R.string.receiver_auto_open), stringResource(state.autoOpenSettingText), onToggleAutoOpenVisual)
            SettingRow(
                stringResource(R.string.receiver_notifications),
                stringResource(if (state.notificationsEnabled) R.string.receiver_setting_on else R.string.receiver_setting_off),
                onManageNotifications
            )

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
    onSeekTo: (Double) -> Unit,
    onInteraction: () -> Unit
) {
    val hasDuration = telemetry.durationSec.isFinite() && telemetry.durationSec > 0.0
    BoxWithConstraints(
        Modifier.fillMaxSize().onPreviewKeyEvent {
            if (it.type == KeyEventType.KeyDown) onInteraction()
            false
        },
        contentAlignment = Alignment.BottomCenter
    ) {
        val compact = maxHeight < 420.dp || maxWidth < 680.dp
        val horizontalInset = (maxWidth * 0.05f).coerceIn(20.dp, 72.dp)
        Column(
            modifier = Modifier.fillMaxWidth()
                .background(VideoControlsScrim)
                .padding(horizontal = horizontalInset)
                .padding(top = if (compact) 28.dp else 54.dp, bottom = if (compact) 16.dp else 32.dp),
            verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                val coverSize = if (compact) 48.dp else 60.dp
                val cover = rememberUniformArtwork(telemetry.artwork, coverSize)
                if (cover != null) AlbumCover(cover.image, Modifier.size(coverSize))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = telemetry.title ?: stringResource(R.string.video_playback),
                        color = PrimaryText,
                        style = if (compact) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    listOfNotNull(telemetry.artist, telemetry.album)
                        .distinct().filter { it != telemetry.title }
                        .takeIf { it.isNotEmpty() }?.let { details ->
                            Text(details.joinToString(" · "), color = SecondaryText,
                                style = MaterialTheme.typography.bodyMedium, maxLines = 1,
                                overflow = TextOverflow.Ellipsis)
                        }
                }
                if (hasDuration) {
                    Text(
                        "${videoTime(telemetry.positionSec)} / ${videoTime(telemetry.durationSec)}",
                        color = PrimaryText,
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            }
            if (hasDuration) PlaybackProgress(telemetry, onSeekTo)
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
private fun PlaybackProgress(telemetry: DirectPlaybackTelemetry, onSeekTo: (Double) -> Unit) {
    val progress = (telemetry.positionSec / telemetry.durationSec).toFloat()
        .takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
    val label = stringResource(R.string.video_timeline)
    var focused by remember { mutableStateOf(false) }
    val interaction = if (telemetry.canSeek) {
        Modifier.onPreviewKeyEvent { event ->
            val direction = when (event.key) {
                Key.DirectionLeft -> -1
                Key.DirectionRight -> 1
                else -> return@onPreviewKeyEvent false
            }
            if (event.type == KeyEventType.KeyDown) {
                timelineSeekStep(telemetry, direction)?.let(onSeekTo)
            }
            true
        }.onFocusChanged { focused = it.isFocused }.focusable()
    } else Modifier
    val trackShape = RoundedCornerShape(50)
    Box(
        Modifier.fillMaxWidth().height(24.dp).then(interaction).semantics {
            contentDescription = label
            progressBarRangeInfo = ProgressBarRangeInfo(progress, 0f..1f)
            if (telemetry.canSeek) {
                setProgress { fraction ->
                    if (!fraction.isFinite()) false else {
                        onSeekTo(fraction.coerceIn(0f, 1f) * telemetry.durationSec)
                        true
                    }
                }
            }
        },
        contentAlignment = Alignment.CenterStart
    ) {
        val trackHeight = if (focused) 8.dp else 4.dp
        Box(Modifier.fillMaxWidth().height(trackHeight).background(Color.White.copy(alpha = 0.24f), trackShape)) {
            Box(Modifier.fillMaxWidth(progress).height(trackHeight).background(
                if (focused) Color.White else FocusColor, trackShape
            ))
        }
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
        // Only expose remote transport commands when the active session supports DACP.
        if (onAudioCommand != null) {
            TvMediaControl(
                label = stringResource(R.string.audio_previous),
                onClick = { onAudioCommand(DacpCommand.PREVIOUS) }
            ) { MediaControlGlyph(MediaGlyph.Previous) }
        }

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

        if (onAudioCommand != null) {
            TvMediaControl(
                label = stringResource(R.string.audio_next),
                onClick = { onAudioCommand(DacpCommand.NEXT) }
            ) { MediaControlGlyph(MediaGlyph.Next) }
        }
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

    BoxWithConstraints(
        modifier = Modifier.fillMaxSize().background(StandbyBackdrop),
        contentAlignment = Alignment.Center
    ) {
        val inset = (maxWidth * 0.06f).coerceIn(20.dp, 72.dp)
        val compact = maxWidth < 680.dp || maxHeight < 390.dp
        val status = state.standbyStatusText
        val statusColor = when (status) {
            R.string.receiver_connected, R.string.receiver_airplay_ready -> Color(0xFF9FE5BE)
            R.string.receiver_unavailable, R.string.receiver_network_unavailable -> Color(0xFFFFBE98)
            else -> Color(0xFFACCBFF)
        }
        Column(
            modifier = Modifier.padding(horizontal = inset, vertical = 16.dp).widthIn(max = 620.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(if (compact) 14.dp else 22.dp)
        ) {
            Text(
                text = state.receiverName,
                style = if (compact) MaterialTheme.typography.headlineLarge else MaterialTheme.typography.displayMedium,
                fontWeight = FontWeight.SemiBold,
                color = PrimaryText,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Row(
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                horizontalArrangement = Arrangement.spacedBy(9.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(8.dp).background(statusColor, CircleShape))
                Text(
                    text = stringResource(status),
                    color = SecondaryText,
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center
                )
            }
            TvControl(onClick = onOpenMenu, modifier = Modifier.focusRequester(settingsFocus)) {
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
            Text(label, Modifier.weight(1.15f), maxLines = 2, overflow = TextOverflow.Ellipsis)
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
        modifier = modifier.heightIn(min = 52.dp),
        shape = ButtonDefaults.shape(shape = RoundedCornerShape(14.dp)),
        scale = ButtonDefaults.scale(focusedScale = 1.02f),
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
