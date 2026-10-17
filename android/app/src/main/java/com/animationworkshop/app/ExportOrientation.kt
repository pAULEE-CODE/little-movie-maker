package com.animationworkshop.app

/** Computes fixed 720p output dimensions after the project's 90-degree-step movie rotation. */
internal fun movieOutputDimensions(sourceWidth: Int, sourceHeight: Int, rotationDegrees: Int): Pair<Int, Int> {
    require(sourceWidth > 0 && sourceHeight > 0) { "Source image dimensions must be positive" }
    val normalizedRotation = ((rotationDegrees % 360) + 360) % 360
    require(normalizedRotation % 90 == 0) { "Movie rotation must use 90-degree steps" }
    val sourceIsPortrait = sourceWidth < sourceHeight
    val outputIsPortrait = if (normalizedRotation == 90 || normalizedRotation == 270) !sourceIsPortrait else sourceIsPortrait
    return if (outputIsPortrait) 720 to 1280 else 1280 to 720
}
