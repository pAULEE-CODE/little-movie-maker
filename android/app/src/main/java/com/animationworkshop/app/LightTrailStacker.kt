package com.animationworkshop.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlin.math.min

/** Bounded-memory brightest-pixel composite for optional timelapse groups. */
internal object LightTrailStacker {
    fun stack(sourceFiles: List<File>, outputFile: File) {
        require(sourceFiles.size >= 2) { "Light trails need at least two source photos." }
        require(sourceFiles.all(File::isFile)) { "A light-trail source photo is missing." }
        val first = decodeSampledFrame(sourceFiles.first(), 1280, 1280, cacheResult = false)
            ?: error("The first light-trail source photo could not be decoded.")
        val portrait = first.height > first.width
        first.recycle()
        val width = if (portrait) 720 else 1280
        val height = if (portrait) 1280 else 720
        val accumulator = IntArray(width * height)
        val pixels = IntArray(width * height)
        val work = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(work)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        try {
            sourceFiles.forEach { file ->
                val bitmap = decodeSampledFrame(file, 1280, 1280, cacheResult = false)
                    ?: error("A light-trail source photo could not be decoded.")
                try {
                    canvas.drawColor(Color.BLACK)
                    val scale = min(width.toFloat() / bitmap.width, height.toFloat() / bitmap.height)
                    val scaledWidth = bitmap.width * scale
                    val scaledHeight = bitmap.height * scale
                    canvas.drawBitmap(bitmap, null, RectF(
                        (width - scaledWidth) / 2f,
                        (height - scaledHeight) / 2f,
                        (width + scaledWidth) / 2f,
                        (height + scaledHeight) / 2f
                    ), paint)
                    work.getPixels(pixels, 0, width, 0, 0, width, height)
                    for (index in pixels.indices) accumulator[index] = brightestChannelColor(accumulator[index], pixels[index])
                } finally {
                    bitmap.recycle()
                }
            }
            work.setPixels(accumulator, 0, width, 0, 0, width, height)
            val temporary = File(outputFile.parentFile, ".stack-${UUID.randomUUID()}.partial")
            try {
                FileOutputStream(temporary).use { stream ->
                    check(work.compress(Bitmap.CompressFormat.JPEG, 94, stream)) { "The light-trail image could not be encoded." }
                    stream.fd.sync()
                }
                check(temporary.renameTo(outputFile)) { "The completed light-trail frame could not be saved." }
            } finally {
                temporary.delete()
            }
        } catch (failure: Throwable) {
            outputFile.delete()
            throw failure
        } finally {
            work.recycle()
        }
    }

    internal fun brightestChannelColor(first: Int, second: Int): Int {
        val red = maxOf(first shr 16 and 0xff, second shr 16 and 0xff)
        val green = maxOf(first shr 8 and 0xff, second shr 8 and 0xff)
        val blue = maxOf(first and 0xff, second and 0xff)
        return (0xff shl 24) or (red shl 16) or (green shl 8) or blue
    }
}
