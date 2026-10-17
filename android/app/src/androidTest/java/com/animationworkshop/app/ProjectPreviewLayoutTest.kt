package com.animationworkshop.app

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ProjectPreviewLayoutTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun portraitAndLandscapeFramesUseMeasuredPreviewAreaAndKeepPlaying() {
        val folder = File(composeRule.activity.cacheDir, "preview-layout-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val landscape = writeFrame(File(folder, "landscape.jpg"), 640, 360, Color.CYAN)
            val portrait = writeFrame(File(folder, "portrait.jpg"), 1440, 2560, Color.MAGENTA)
            assertEquals(16f / 9f, previewFrameAspectRatio(landscape)!!, .01f)
            assertEquals(1440f / 2560f, previewFrameAspectRatio(portrait)!!, .01f)
            assertEquals(9f / 16f, previewFrameAspectRatio(portrait, 90)!!, .01f)

            val projectFolder = File(folder, "project").apply { mkdirs() }
            val item = ProjectGalleryItem(
                project = AnimationProject("preview-test", "Aspect Test", projectFolder, 0L),
                frameCount = 2,
                thumbnailFile = landscape,
                fps = 8,
                previewFrames = listOf(AnimationFrame("landscape", landscape), AnimationFrame("portrait", portrait))
            )
            composeRule.setContent { MaterialTheme { GalleryMotionPreview(item, animate = true) } }

            val panel = composeRule.onNodeWithTag("gallery-preview-panel", useUnmergedTree = true)
            val image = composeRule.onNodeWithTag("gallery-preview-image", useUnmergedTree = true)
            panel.assertExists()
            composeRule.waitUntil(5_000L) {
                runCatching { image.fetchSemanticsNode(); true }.getOrDefault(false)
            }
            image.assertExists()
            composeRule.onNodeWithTag("gallery-preview-frame-counter", useUnmergedTree = true)
                .assertTextContains("Frame 1 of 2", substring = true)
            val landscapePanelHeight = panel.fetchSemanticsNode().boundsInRoot.height
            val landscapeImageBounds = image.fetchSemanticsNode().boundsInRoot
            val landscapePanelBounds = panel.fetchSemanticsNode().boundsInRoot
            assertEquals(landscapePanelBounds.width, landscapeImageBounds.width, 1f)
            assertEquals(landscapePanelBounds.height, landscapeImageBounds.height, 1f)

            composeRule.waitUntil(5_000L) {
                runCatching {
                    composeRule.onNodeWithTag("gallery-preview-frame-counter", useUnmergedTree = true)
                        .assertTextContains("Frame 2 of 2", substring = true)
                    panel.fetchSemanticsNode().boundsInRoot.height > landscapePanelHeight + 1f
                }.getOrDefault(false)
            }
            val portraitPanelBounds = panel.fetchSemanticsNode().boundsInRoot
            val portraitImageBounds = image.fetchSemanticsNode().boundsInRoot
            assertEquals(portraitPanelBounds.width, portraitImageBounds.width, 1f)
            assertEquals(portraitPanelBounds.height, portraitImageBounds.height, 1f)
            assertTrue("Portrait frame should receive a taller preview panel", portraitPanelBounds.height > landscapePanelHeight)
        } finally {
            folder.deleteRecursively()
        }
    }

    private fun writeFrame(file: File, width: Int, height: Int, color: Int): File {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(color)
        FileOutputStream(file).use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it)) }
        bitmap.recycle()
        return file
    }
}
