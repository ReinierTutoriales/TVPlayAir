package com.tvplayair.receiver.ui

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.lerp
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun TvNowPlayingScreen(
    title: String?,
    artist: String?,
    album: String?,
    artwork: ByteArray?,
    controls: @Composable () -> Unit
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val coverSize = minOf(maxWidth * 0.39f, maxHeight * 0.77f, 340.dp)
        val horizontalInset = maxWidth * 0.055f
        val coverPixels = with(LocalDensity.current) { coverSize.roundToPx() }.coerceAtLeast(1)
        val artworkKey = remember(artwork) { artwork?.takeIf { it.isNotEmpty() }?.artworkKey() }
        var cover by remember { mutableStateOf<UniformArtwork?>(null) }
        LaunchedEffect(artworkKey, coverPixels) {
            val source = artwork?.takeIf { it.isNotEmpty() }
            if (source == null) {
                cover = null
            } else {
                val decoded = withContext(Dispatchers.Default) {
                    decodeUniformArtwork(source, coverPixels)
                }
                if (decoded != null) {
                    cover = decoded
                }
            }
        }
        val tint = cover?.tint ?: Color(0xFF212530)
        val compact = maxHeight < 360.dp || maxWidth < 640.dp
        val titleStyle = if (compact) MaterialTheme.typography.headlineMedium
            else MaterialTheme.typography.displaySmall

        Row(
            modifier = Modifier.fillMaxSize()
                .drawWithCache {
                    val backdrop = Brush.radialGradient(
                        0f to tint,
                        0.53f to lerp(Color.Black, tint, 0.40f),
                        1f to Color.Black,
                        center = Offset(size.width * 0.23f, size.height * 0.44f),
                        radius = size.width * 0.72f
                    )
                    onDrawBehind { drawRect(backdrop) }
                }
                .padding(horizontal = horizontalInset),
            horizontalArrangement = Arrangement.spacedBy(horizontalInset),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AlbumCover(cover?.image, Modifier.size(coverSize))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(if (compact) 14.dp else 26.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = title ?: stringResource(R.string.audio_airplay),
                        style = titleStyle,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    artist?.let {
                        Text(
                            text = it,
                            color = Color(0xFFD1D3D9),
                            style = MaterialTheme.typography.titleLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    album?.takeUnless { it == title || it == artist }?.let {
                        Text(
                            text = it,
                            color = Color(0xFFA9ADB7),
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                controls()
            }
        }
    }
}

@Composable
private fun AlbumCover(image: ImageBitmap?, modifier: Modifier) {
    val shape = RoundedCornerShape(18.dp)
    Box(
        modifier = modifier.clip(shape)
            .background(Color(0xFF16181E))
            .border(1.dp, Color.White.copy(alpha = 0.14f), shape),
        contentAlignment = Alignment.Center
    ) {
        Crossfade(targetState = image, label = "AlbumCoverCrossfade") { currentImage ->
            if (currentImage != null) {
                Image(
                    bitmap = currentImage,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Canvas(Modifier.size(44.dp)) {
                        val note = Color(0xFFA6A9B3)
                        val unit = size.width / 12f
                        drawLine(note, Offset(7f * unit, 2f * unit), Offset(7f * unit, 9f * unit), unit)
                        drawLine(note, Offset(7f * unit, 2f * unit), Offset(10f * unit, 3.5f * unit), unit)
                        drawOval(note, Offset(3f * unit, 8f * unit), Size(4.5f * unit, 3f * unit))
                    }
                }
            }
        }
    }
}

@Composable
internal fun TvMediaControlGroup(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Row(
        modifier = modifier.background(Color.Black.copy(alpha = 0.42f), CircleShape)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) { content() }
}

@Composable
internal fun TvMediaControl(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    enabled: Boolean = true,
    content: @Composable () -> Unit
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.size(if (primary) 64.dp else 52.dp)
            .semantics { contentDescription = label },
        shape = IconButtonDefaults.shape(shape = CircleShape),
        scale = IconButtonDefaults.scale(focusedScale = 1f),
        colors = IconButtonDefaults.colors(
            containerColor = if (primary) Color(0xFF303743) else Color.Transparent,
            contentColor = Color.White,
            focusedContainerColor = Color.White,
            focusedContentColor = Color.Black,
            pressedContainerColor = Color(0xFFB5CAEA),
            pressedContentColor = Color.Black
        )
    ) { content() }
}

internal enum class MediaGlyph { Previous, PlayPause, Next, Play, Pause }

@Composable
internal fun MediaControlGlyph(glyph: MediaGlyph) {
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
        when (glyph) {
            MediaGlyph.Previous -> {
                triangle(19f, 7f)
                bar(4f)
            }
            MediaGlyph.PlayPause -> {
                triangle(4f, 14f)
                bar(16f)
                bar(20f)
            }
            MediaGlyph.Next -> {
                triangle(5f, 17f)
                bar(18f)
            }
            MediaGlyph.Play -> triangle(8f, 20f)
            MediaGlyph.Pause -> {
                bar(6f, 4f)
                bar(14f, 4f)
            }
        }
    }
}
