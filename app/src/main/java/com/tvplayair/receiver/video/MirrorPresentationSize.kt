package com.tvplayair.receiver.video

import kotlin.math.abs

/**
 * A vendor decoder can report a hardware-scaled output rectangle as a crop.
 * Keep the source aspect when the reported aspect diverges beyond normal padding.
 */
internal fun mirrorPresentationSize(
    sourceWidth: Int,
    sourceHeight: Int,
    codedWidth: Int,
    codedHeight: Int,
    cropWidth: Int,
    cropHeight: Int
): Pair<Int, Int>? {
    val sourceValid = sourceWidth > 0 && sourceHeight > 0
    val codedValid = codedWidth > 0 && codedHeight > 0
    val cropValid = codedValid && cropWidth in 1..codedWidth && cropHeight in 1..codedHeight
    if (!sourceValid) {
        return when {
            cropValid -> cropWidth to cropHeight
            codedValid -> codedWidth to codedHeight
            else -> null
        }
    }
    if (cropValid && sameMirrorAspect(cropWidth, cropHeight, sourceWidth, sourceHeight)) {
        return cropWidth to cropHeight
    }
    if (codedValid && sameMirrorAspect(codedWidth, codedHeight, sourceWidth, sourceHeight)) {
        return codedWidth to codedHeight
    }
    return sourceWidth to sourceHeight
}

private fun sameMirrorAspect(width: Int, height: Int, sourceWidth: Int, sourceHeight: Int): Boolean =
    abs((width.toDouble() * sourceHeight) / (height.toDouble() * sourceWidth) - 1.0) <= 0.05
