package com.animationworkshop.app

import android.graphics.Bitmap
import kotlin.math.min
import kotlin.math.sqrt

/** Image and timeline treatments are project metadata; source photographs are never modified. */
internal enum class ImageEffectType { NONE, BLACK_WHITE, SEPIA, VIVID, POSTERISE, VIGNETTE }
internal enum class TimelineEffectType { NORMAL, REVERSE, BOOMERANG, SLOW_MOTION, FAST_MOTION }

internal data class MovieEffects(
    val image: ImageEffectType = ImageEffectType.NONE,
    val strength: Float = 1f,
    val timeline: TimelineEffectType = TimelineEffectType.NORMAL,
    val freezeFrameId: String? = null,
    val freezeMultiplier: Int = 3
) {
    fun normalized() = copy(
        strength = (strength.takeIf { it.isFinite() } ?: 1f).coerceIn(0f, 1f),
        freezeFrameId = freezeFrameId?.takeIf { it.isNotBlank() },
        freezeMultiplier = freezeMultiplier.coerceIn(2, 10)
    )
}

/** A reference into the original frame list with duration expressed as project-FPS ticks. */
internal data class TimedMovieFrame(val frame: AnimationFrame, val durationTicks: Int)

/** Shared preview/export timeline semantics. Lists contain references, never copied photo files. */
internal fun buildMovieTimeline(frames: List<AnimationFrame>, effects: MovieEffects): List<TimedMovieFrame> {
    if (frames.isEmpty()) return emptyList()
    val settings = effects.normalized()
    var ordered = when (settings.timeline) {
        TimelineEffectType.REVERSE -> frames.asReversed()
        TimelineEffectType.BOOMERANG -> frames + frames.dropLast(1).drop(1).asReversed()
        else -> frames
    }
    var base = ordered.map { frame ->
        val freeze = if (frame.id == settings.freezeFrameId) settings.freezeMultiplier else 1
        TimedMovieFrame(frame, frame.duration.coerceIn(1, 10) * freeze)
    }
    if (settings.timeline == TimelineEffectType.SLOW_MOTION) {
        base = base.map { it.copy(durationTicks = it.durationTicks * 2) }
    }
    if (settings.timeline != TimelineEffectType.FAST_MOTION) return base

    // Sample the virtual project-FPS timeline every other tick. This preserves relative holds,
    // avoids changing the project's FPS, and keeps memory bounded to at most one entry per frame.
    val totalTicks = base.sumOf { it.durationTicks.toLong() }
    if (totalTicks <= 0) return emptyList()
    val output = ArrayList<TimedMovieFrame>(base.size)
    var cumulativeEnd = base.first().durationTicks.toLong()
    var sourceIndex = 0
    var tick = 0L
    while (tick < totalTicks) {
        while (sourceIndex < base.lastIndex && tick >= cumulativeEnd) {
            sourceIndex++
            cumulativeEnd += base[sourceIndex].durationTicks
        }
        val source = base[sourceIndex].frame
        val last = output.lastOrNull()
        if (last?.frame?.id == source.id) output[output.lastIndex] = last.copy(durationTicks = last.durationTicks + 1)
        else output += TimedMovieFrame(source, 1)
        tick += 2
    }
    return output
}

/** Apply a single non-destructive pixel pass. Caller owns and recycles the returned bitmap. */
internal fun renderImageEffect(source: Bitmap, effects: MovieEffects): Bitmap {
    val settings = effects.normalized()
    if (settings.image == ImageEffectType.NONE) return source
    val width = source.width
    val height = source.height
    val pixels = IntArray(width * height)
    source.getPixels(pixels, 0, width, 0, 0, width, height)
    val amount = settings.strength
    val cx = (width - 1) / 2f
    val cy = (height - 1) / 2f
    val maxRadius = sqrt(cx * cx + cy * cy).coerceAtLeast(1f)
    val posterLevels = (10f - amount * 8f).toInt().coerceIn(2, 10)
    val vividSaturation = 1f + amount * 1.25f
    for (index in pixels.indices) {
        val original = pixels[index]
        val alpha = original ushr 24 and 0xff
        val r = original ushr 16 and 0xff
        val g = original ushr 8 and 0xff
        val b = original and 0xff
        var nr = r.toFloat()
        var ng = g.toFloat()
        var nb = b.toFloat()
        when (settings.image) {
            ImageEffectType.BLACK_WHITE -> {
                val gray = 0.299f * r + 0.587f * g + 0.114f * b
                nr = gray; ng = gray; nb = gray
            }
            ImageEffectType.SEPIA -> {
                val sr = min(255f, 0.393f * r + 0.769f * g + 0.189f * b)
                val sg = min(255f, 0.349f * r + 0.686f * g + 0.168f * b)
                val sb = min(255f, 0.272f * r + 0.534f * g + 0.131f * b)
                nr = r + (sr - r) * amount
                ng = g + (sg - g) * amount
                nb = b + (sb - b) * amount
            }
            ImageEffectType.VIVID -> {
                val gray = 0.299f * r + 0.587f * g + 0.114f * b
                nr = gray + (r - gray) * vividSaturation
                ng = gray + (g - gray) * vividSaturation
                nb = gray + (b - gray) * vividSaturation
            }
            ImageEffectType.POSTERISE -> {
                val step = 255f / (posterLevels - 1)
                nr = ((r / step).toInt() * step).coerceIn(0f, 255f)
                ng = ((g / step).toInt() * step).coerceIn(0f, 255f)
                nb = ((b / step).toInt() * step).coerceIn(0f, 255f)
            }
            ImageEffectType.VIGNETTE -> {
                val x = index % width - cx
                val y = index / width - cy
                val distance = (sqrt(x * x + y * y) / maxRadius).coerceIn(0f, 1f)
                val falloff = (1f - amount * 0.72f * distance * distance).coerceIn(0.18f, 1f)
                nr = r * falloff; ng = g * falloff; nb = b * falloff
            }
            ImageEffectType.NONE -> Unit
        }
        pixels[index] = (alpha shl 24) or
            (nr.toInt().coerceIn(0, 255) shl 16) or
            (ng.toInt().coerceIn(0, 255) shl 8) or
            nb.toInt().coerceIn(0, 255)
    }
    return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
        it.setPixels(pixels, 0, width, 0, 0, width, height)
    }
}

internal fun imageEffectLabel(effect: ImageEffectType): String = when (effect) {
    ImageEffectType.NONE -> "Original / 原始"
    ImageEffectType.BLACK_WHITE -> "Black & White / 黑白"
    ImageEffectType.SEPIA -> "Sepia / 復古棕褐"
    ImageEffectType.VIVID -> "Vivid Colour / 鮮豔色彩"
    ImageEffectType.POSTERISE -> "Posterise / 海報化"
    ImageEffectType.VIGNETTE -> "Vignette / 暗角"
}

internal fun timelineEffectLabel(effect: TimelineEffectType): String = when (effect) {
    TimelineEffectType.NORMAL -> "Normal / 正常"
    TimelineEffectType.REVERSE -> "Reverse / 反向"
    TimelineEffectType.BOOMERANG -> "Boomerang / 來回播放"
    TimelineEffectType.SLOW_MOTION -> "Slow Motion / 慢動作"
    TimelineEffectType.FAST_MOTION -> "Fast Motion / 快動作"
}

internal fun movieDurationSeconds(frames: List<AnimationFrame>, effects: MovieEffects, fps: Int): Double =
    buildMovieTimeline(frames, effects).sumOf { it.durationTicks.toLong() } / fps.coerceIn(1, 24).toDouble()
