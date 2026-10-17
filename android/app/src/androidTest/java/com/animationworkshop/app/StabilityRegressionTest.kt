package com.animationworkshop.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.MediaMetadataRetriever
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class StabilityRegressionTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun lightTrailsCompleteAtFiveTenAndTwentyFramesWithoutChangingSources() {
        val folder = File(context.cacheDir, "light-trails-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val sources = (0 until 20).map { index ->
                val file = File(folder, "source-$index.jpg")
                val bitmap = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888)
                Canvas(bitmap).drawCircle(20f + index * 11f, 120f, 12f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.rgb((index * 31) % 255, (index * 53) % 255, (index * 79) % 255)
                })
                FileOutputStream(file).use { stream -> assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, stream)) }
                bitmap.recycle()
                file
            }
            val originalHashes = sources.map(::sha256)
            listOf(5, 10, 20).forEach { count ->
                val output = File(folder, "stack-$count.jpg")
                LightTrailStacker.stack(sources.take(count), output)
                assertTrue("$count-frame output is missing", output.isFile && output.length() > 0L)
                val rendered = BitmapFactory.decodeFile(output.absolutePath)
                assertNotNull("$count-frame output is not a decodable JPEG", rendered)
                rendered?.recycle()
                assertEquals("Source photos changed during $count-frame stacking", originalHashes, sources.map(::sha256))
            }
        } finally {
            folder.deleteRecursively()
        }
    }

    @Test
    fun mp4ExportsFromTwoMoviesRemainPlayableAndDoNotRecyclePreviewCacheEntries() = runBlocking {
        val folder = File(context.cacheDir, "mp4-regression-${UUID.randomUUID()}").apply { mkdirs() }
        val exportedUris = mutableListOf<android.net.Uri>()
        try {
            listOf(8, 12).forEachIndexed { movieIndex, fps ->
                val movieFolder = File(folder, "movie-$movieIndex").apply { mkdirs() }
                val frames = (0 until 6).map { index ->
                    val file = File(movieFolder, "frame-${index.toString().padStart(3, '0')}.jpg")
                    val bitmap = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888)
                    Canvas(bitmap).apply {
                        drawColor(Color.rgb(index * 25, movieIndex * 80, 150))
                        drawCircle(40f + index * 35f, 120f, 20f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
                    }
                    FileOutputStream(file).use { stream -> assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 92, stream)) }
                    bitmap.recycle()
                    AnimationFrame("movie-$movieIndex-frame-$index", file)
                }
                val config = Mp4Exporter.inspect(frames, fps)
                val cachedPreview = decodeSampledFrame(frames.first().file, config.width, config.height)
                assertNotNull("Could not seed preview bitmap cache for movie $movieIndex", cachedPreview)
                val exported = Mp4Exporter.export(context, frames, config, "stability-$movieIndex") { }
                exportedUris += exported.uri

                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(context, exported.uri)
                    assertEquals("yes", retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO))
                    assertTrue((retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) > 0L)
                    val firstVideoFrame = retriever.getFrameAtTime(0L)
                    assertNotNull("Movie $movieIndex did not decode a video frame", firstVideoFrame)
                    firstVideoFrame?.recycle()
                } finally {
                    retriever.release()
                }

                assertFalse("Exporter recycled its cached preview bitmap", cachedPreview!!.isRecycled)
                val cacheHit = decodeSampledFrame(frames.first().file, config.width, config.height)
                assertTrue("Preview cache was invalidated during export", cacheHit === cachedPreview)
            }
        } finally {
            exportedUris.forEach { context.contentResolver.delete(it, null, null) }
            folder.deleteRecursively()
        }
    }

    @Test
    fun abandonedTimelapseSourcesAreRecoveredAfterProcessRestart() {
        val folder = File(context.cacheDir, "staging-recovery-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val staging = FrameStore(folder).newStagingCapture()
            val expected = writeTestPhoto(staging, Color.MAGENTA)

            val restartedStore = FrameStore(folder)
            val recovered = restartedStore.frames()

            assertEquals(1, recovered.size)
            assertTrue(recovered.first().file.isFile)
            assertEquals(expected, sha256(recovered.first().file))
            assertFalse(staging.exists())

            val failedStackSource = restartedStore.newStagingCapture()
            val failedStackHash = writeTestPhoto(failedStackSource, Color.CYAN)
            assertTrue(restartedStore.preserveStagingCapture(failedStackSource))
            val afterFailure = restartedStore.frames()
            assertEquals(2, afterFailure.size)
            assertEquals(failedStackHash, sha256(afterFailure.last().file))
        } finally {
            folder.deleteRecursively()
        }
    }

    private fun writeTestPhoto(file: File, color: Int): String {
        val bitmap = Bitmap.createBitmap(48, 32, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(color)
        FileOutputStream(file).use { stream -> assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, stream)) }
        bitmap.recycle()
        return sha256(file)
    }

    private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256")
        .digest(file.readBytes())
        .joinToString("") { "%02x".format(it) }
}
