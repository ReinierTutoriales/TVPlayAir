package com.tvplayair.receiver.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlin.math.min

internal data class ArtworkKey(val size: Int, val contentHash: Int)

internal fun ByteArray.artworkKey(): ArtworkKey = ArtworkKey(size, contentHashCode())

internal data class UniformArtwork(val image: ImageBitmap, val tint: Color)

internal fun calculateInSampleSize(width: Int, height: Int, targetPixels: Int): Int {
    if (width <= 0 || height <= 0 || targetPixels <= 0) return 1
    var sampleSize = 1
    val minSide = min(width, height)
    while (minSide / (sampleSize * 2) >= targetPixels) {
        sampleSize *= 2
    }
    return sampleSize
}

internal fun calculateCenterCropRect(width: Int, height: Int): IntArray {
    if (width <= 0 || height <= 0) return intArrayOf(0, 0, 0)
    val square = min(width, height)
    val left = (width - square) / 2
    val top = (height - square) / 2
    return intArrayOf(left, top, square)
}

internal fun decodeUniformArtwork(bytes: ByteArray, targetPixels: Int): UniformArtwork? {
    if (bytes.isEmpty() || targetPixels <= 0) return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    val options = BitmapFactory.Options().apply {
        inSampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, targetPixels)
        inJustDecodeBounds = false
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
    val squareBitmap = normalizeSquareBitmap(decoded, targetPixels)
    val tint = extractDominantTint(squareBitmap)
    return UniformArtwork(squareBitmap.asImageBitmap(), tint)
}

private fun normalizeSquareBitmap(source: Bitmap, targetPixels: Int): Bitmap {
    val width = source.width
    val height = source.height
    val rect = calculateCenterCropRect(width, height)
    val side = rect[2]
    if (side <= 0) return source

    val cropped = if (width == height) {
        source
    } else {
        Bitmap.createBitmap(source, rect[0], rect[1], side, side).also {
            if (it !== source) source.recycle()
        }
    }
    if (side <= targetPixels) return cropped
    return Bitmap.createScaledBitmap(cropped, targetPixels, targetPixels, true).also {
        if (it !== cropped) cropped.recycle()
    }
}

private fun extractDominantTint(bitmap: Bitmap): Color {
    val grid = 7
    var red = 0
    var green = 0
    var blue = 0
    var totalWeight = 0
    repeat(grid) { row ->
        repeat(grid) sample@{ column ->
            val pixel = bitmap.getPixel(
                (2 * column + 1) * bitmap.width / (2 * grid),
                (2 * row + 1) * bitmap.height / (2 * grid)
            )
            if (pixel ushr 24 < 128) return@sample
            val r = (pixel ushr 16) and 0xFF
            val g = (pixel ushr 8) and 0xFF
            val b = pixel and 0xFF
            val weight = 16 + maxOf(r, g, b) - minOf(r, g, b)
            red += r * weight
            green += g * weight
            blue += b * weight
            totalWeight += weight
        }
    }
    return if (totalWeight == 0) Color(0xFF212530) else Color(
        red = 18 + red * 57 / (255 * totalWeight),
        green = 19 + green * 57 / (255 * totalWeight),
        blue = 24 + blue * 57 / (255 * totalWeight)
    )
}
