package com.animationworkshop.app

import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaMetadataRetriever
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.FileOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class MovieEffectsRenderingTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun eachColourTreatmentRendersWithoutMutatingItsSourcePhoto() {
        val source = Bitmap.createBitmap(5, 5, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(90, 130, 115)) }
        try {
            val original = IntArray(25).also { source.getPixels(it, 0, 5, 0, 0, 5, 5) }
            ImageEffectType.entries.filter { it != ImageEffectType.NONE }.forEach { treatment ->
                val rendered = renderImageEffect(source, MovieEffects(image = treatment))
                try {
                    assertNotSame("$treatment should create a derived image", source, rendered)
                    assertEquals(5, rendered.width)
                    assertEquals(5, rendered.height)
                    val output = IntArray(25).also { rendered.getPixels(it, 0, 5, 0, 0, 5, 5) }
                    assertNotEquals("$treatment should visibly transform the source", original.toList(), output.toList())
                    assertTrue("The source bitmap remains usable", !source.isRecycled)
                } finally {
                    rendered.recycle()
                }
            }
            assertSame(source, renderImageEffect(source, MovieEffects()))
            assertEquals(original.toList(), IntArray(25).also { source.getPixels(it, 0, 5, 0, 0, 5, 5) }.toList())
        } finally {
            source.recycle()
        }
    }

    @Test
    fun legacyProjectsDefaultToNoEffectsAndSavedEffectsSurviveStoreReopen() {
        val folder = File(context.cacheDir, "effects-settings-${UUID.randomUUID()}")
        try {
            val originalStore = FrameStore(folder)
            assertEquals(MovieEffects(), originalStore.movieEffects())
            val saved = MovieEffects(
                image = ImageEffectType.SEPIA,
                strength = .65f,
                timeline = TimelineEffectType.BOOMERANG,
                freezeFrameId = "student-frame-7",
                freezeMultiplier = 4
            )
            originalStore.saveMovieEffects(saved)
            assertEquals(saved, FrameStore(folder).movieEffects())
        } finally {
            folder.deleteRecursively()
        }
    }

    @Test
    fun imageAndTimelineTreatmentsComposeWithoutCreatingSourceFiles() {
        val frames = listOf(AnimationFrame("first", File("first.jpg")), AnimationFrame("last", File("last.jpg")))
        val effects = MovieEffects(image = ImageEffectType.SEPIA, strength = .7f, timeline = TimelineEffectType.BOOMERANG)
        val timeline = buildMovieTimeline(frames, effects)
        assertEquals(listOf("first", "last"), timeline.map { it.frame.id })
        assertEquals(listOf("first.jpg", "last.jpg"), timeline.map { it.frame.file.name })

        val source = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(80, 120, 160)) }
        val rendered = renderImageEffect(source, effects)
        try {
            assertNotSame(source, rendered)
            assertEquals(source.width, rendered.width)
            assertEquals(source.height, rendered.height)
            assertTrue(!source.isRecycled)
        } finally {
            rendered.recycle()
            source.recycle()
        }
    }

    @Test
    fun eachTreatmentAndTwoCombinationsExportPlayablePortraitAndLandscapeMovies() = runBlocking {
        val folder = File(context.cacheDir, "effects-export-${UUID.randomUUID()}").apply { mkdirs() }
        val exported = mutableListOf<android.net.Uri>()
        try {
            fun createFrames(prefix: String, width: Int, height: Int, count: Int): List<AnimationFrame> = (0 until count).map { index ->
                val file = File(folder, "$prefix-$index.jpg")
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                for (y in 0 until height) for (x in 0 until width) {
                    bitmap.setPixel(x, y, Color.rgb((x * 9 + index * 31) % 255, (y * 11 + index * 47) % 255, (x + y + 83) % 255))
                }
                FileOutputStream(file).use { stream -> check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, stream)) }
                bitmap.recycle()
                AnimationFrame("$prefix-$index", file, duration = 1)
            }

            val landscape = createFrames("landscape", 96, 64, 3)
            val portrait = createFrames("portrait", 64, 96, 3)
            val sourceFiles = landscape + portrait
            val sourceHashes = sourceFiles.associate { it.file to MessageDigest.getInstance("SHA-256").digest(it.file.readBytes()).toList() }
            val cases = buildList {
                ImageEffectType.entries.filter { it != ImageEffectType.NONE }.forEachIndexed { index, treatment ->
                    add(Triple(landscape.take(2), MovieEffects(image = treatment), if (index == 0) 90 else 0))
                }
                TimelineEffectType.entries.forEach { treatment -> add(Triple(landscape, MovieEffects(timeline = treatment), 0)) }
                add(Triple(landscape, MovieEffects(image = ImageEffectType.SEPIA, strength = .65f, timeline = TimelineEffectType.REVERSE), 0))
                add(Triple(portrait, MovieEffects(image = ImageEffectType.VIGNETTE, timeline = TimelineEffectType.BOOMERANG), 0))
            }
            cases.forEachIndexed { index, (frames, effects, rotation) ->
                val fps = 8
                val config = Mp4Exporter.inspect(frames, fps, rotation)
                val movie = Mp4Exporter.export(context, frames, config, "effects-$index-${UUID.randomUUID()}", rotation, effects) { }
                exported += movie.uri
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(context, movie.uri)
                    assertEquals("Effects case $index must contain video", "yes", retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO))
                    assertTrue("Effects case $index must have a duration", (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) > 0L)
                    val rendered = retriever.getFrameAtTime(0L)
                    assertTrue("Effects case $index must decode its first frame", rendered != null)
                    rendered?.recycle()
                    assertEquals(config.width.toString(), retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH))
                    assertEquals(config.height.toString(), retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT))
                } finally {
                    retriever.release()
                }
            }
            sourceHashes.forEach { (file, hash) -> assertEquals("Effect export changed original ${file.name}", hash, MessageDigest.getInstance("SHA-256").digest(file.readBytes()).toList()) }
        } finally {
            exported.forEach { context.contentResolver.delete(it, null, null) }
            folder.deleteRecursively()
        }
    }
}
