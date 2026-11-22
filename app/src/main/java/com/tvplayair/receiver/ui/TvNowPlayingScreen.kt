package com.tvplayair.receiver.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.IconButton
import androidx.tv.material3.IconButtonDefaults
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.tvplayair.receiver.R
import com.tvplayair.receiver.control.DacpCommand
import com.tvplayair.receiver.session.RaopNowPlaying
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun TvNowPlayingScreen(
    metadata: RaopNowPlaying?,
    controlsAvailable: Boolean,
    onAudioCommand: (DacpCommand) -> Unit
) {
    val playFocus = remember { FocusRequester() }
    LaunchedEffect(controlsAvailable) {
        if (controlsAvailable) playFocus.requestFocus()
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(horizontal = 48.dp, vertical = 28.dp)
    ) {
        val coverSize = (maxHeight * 0.24f).coerceIn(72.dp, 144.dp)
        val titleStyle = if (maxWidth >= 640.dp) MaterialTheme.typography.displaySmall
            else MaterialTheme.typography.headlineMedium
        val artwork = metadata?.artwork
        val coverPixels = with(LocalDensity.current) { coverSize.roundToPx() }.coerceAtLeast(1)
        val cover = key(artwork, coverPixels) {
            val bitmap by produceState<ImageBitmap?>(initialValue = null) {
                if (artwork != null) {
                    value = withContext(Dispatchers.Default) {
                        decodeCover(artwork, coverPixels)
                    }
                }
            }
            bitmap
        }

        Column(Modifier.fillMaxSize()) {
            Spacer(Modifier.weight(3f))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (artwork != null) {
                    Box(Modifier.size(coverSize)) {
                        if (cover != null) {
                            Image(
                                bitmap = cover,
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)),
                                contentScale = ContentScale.Fit
                            )
                        }
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val track = metadata?.track
                    Text(
                        text = track?.title ?: stringResource(R.string.audio_airplay),
                        style = titleStyle,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    val details = listOfNotNull(track?.artist, track?.album).distinct()
                    if (details.isNotEmpty()) {
                        Text(
                            text = details.joinToString(" · "),
                            color = Color(0xFFB8B8B8),
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF2B2B2B)))
            if (controlsAvailable) {
                Row(
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                        .padding(top = 16.dp)
                        .background(Color(0xFF161616), RoundedCornerShape(22.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AudioControl(AudioAction.Previous, onAudioCommand)
                    AudioControl(AudioAction.PlayPause, onAudioCommand, Modifier.focusRequester(playFocus))
                    AudioControl(AudioAction.Next, onAudioCommand)
                }
            }
            Spacer(Modifier.weight(1f))
        }
    }
}

private fun decodeCover(artwork: ByteArray, coverPixels: Int): ImageBitmap? {
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(artwork, 0, artwork.size, options)
    val maxDimension = maxOf(options.outWidth, options.outHeight)
    if (maxDimension <= 0) return null
    val ratio = (maxDimension / coverPixels).coerceAtLeast(1)
    options.inSampleSize = Integer.highestOneBit(ratio)
    options.inJustDecodeBounds = false
    return BitmapFactory.decodeByteArray(artwork, 0, artwork.size, options)?.asImageBitmap()
}

private enum class AudioAction(val label: Int, val command: DacpCommand) {
    Previous(R.string.audio_previous, DacpCommand.PREVIOUS),
    PlayPause(R.string.audio_play_pause, DacpCommand.PLAY_PAUSE),
    Next(R.string.audio_next, DacpCommand.NEXT)
}

@Composable
private fun AudioControl(
    action: AudioAction,
    onAudioCommand: (DacpCommand) -> Unit,
    modifier: Modifier = Modifier
) {
    val label = stringResource(action.label)
    val primary = action == AudioAction.PlayPause
    IconButton(
        onClick = { onAudioCommand(action.command) },
        modifier = modifier.size(if (primary) 56.dp else 48.dp)
            .semantics { contentDescription = label },
        shape = IconButtonDefaults.shape(shape = CircleShape),
        scale = IconButtonDefaults.scale(focusedScale = 1.08f),
        colors = IconButtonDefaults.colors(
            containerColor = if (primary) Color(0xFFEAEAEA) else Color.Transparent,
            contentColor = if (primary) Color.Black else Color.White,
            focusedContainerColor = Color(0xFF9BE4CF),
            focusedContentColor = Color.Black,
            pressedContainerColor = Color.White,
            pressedContentColor = Color.Black
        )
    ) {
        val color = LocalContentColor.current
        Canvas(Modifier.size(24.dp)) {
            val unit = size.width / 24
            fun triangle(left: Float, right: Float) {
                val path = Path().apply {
                    moveTo(left * unit, 5 * unit)
                    lineTo(left * unit, 19 * unit)
                    lineTo(right * unit, 12 * unit)
                    close()
                }
                drawPath(path, color)
            }
            fun bar(x: Float, width: Float = 2f) {
                drawRect(color, Offset(x * unit, 5 * unit), Size(width * unit, 14 * unit))
            }
            when (action) {
                AudioAction.Previous -> {
                    triangle(19f, 7f)
                    bar(4f)
                }
                AudioAction.PlayPause -> {
                    triangle(4f, 14f)
                    bar(16f)
                    bar(20f)
                }
                AudioAction.Next -> {
                    triangle(5f, 17f)
                    bar(18f)
                }
            }
        }
    }
}
