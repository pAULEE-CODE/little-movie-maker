package com.animationworkshop.app

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
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
            composeRule.setContent { MaterialTheme { GalleryMotionPreview(item, animate = true, fixedPanelHeight = 160.dp) } }

            val panel = composeRule.onNodeWithTag("gallery-preview-panel", useUnmergedTree = true)
            val image = composeRule.onNodeWithTag("gallery-preview-image", useUnmergedTree = true)
            panel.assertExists()
            composeRule.waitUntil(5_000L) {
                runCatching { image.fetchSemanticsNode(); true }.getOrDefault(false)
            }
            image.assertExists()
            composeRule.onNodeWithTag("gallery-preview-frame-counter", useUnmergedTree = true)
                .assertTextContains("Frame 1 of 2", substring = true)
            val fixedPanelHeight = panel.fetchSemanticsNode().boundsInRoot.height
            val landscapeImageBounds = image.fetchSemanticsNode().boundsInRoot
            val landscapePanelBounds = panel.fetchSemanticsNode().boundsInRoot
            assertEquals(landscapePanelBounds.width, landscapeImageBounds.width, 1f)
            assertEquals(landscapePanelBounds.height, landscapeImageBounds.height, 1f)

            composeRule.waitUntil(5_000L) {
                runCatching {
                    composeRule.onNodeWithTag("gallery-preview-frame-counter", useUnmergedTree = true)
                        .assertTextContains("Frame 2 of 2", substring = true)
                    kotlin.math.abs(panel.fetchSemanticsNode().boundsInRoot.height - fixedPanelHeight) < 1f
                }.getOrDefault(false)
            }
            val portraitPanelBounds = panel.fetchSemanticsNode().boundsInRoot
            val portraitImageBounds = image.fetchSemanticsNode().boundsInRoot
            assertEquals(portraitPanelBounds.width, portraitImageBounds.width, 1f)
            assertEquals(portraitPanelBounds.height, portraitImageBounds.height, 1f)
            assertEquals("Playback aspect changes must not resize the fixed preview panel", fixedPanelHeight, portraitPanelBounds.height, 1f)
        } finally {
            folder.deleteRecursively()
        }
    }

    @Test
    fun fixedControlsStayInPlaceAcrossViewModesWhileCollectionScrollsAndChangesPreview() {
        val folder = File(composeRule.activity.cacheDir, "gallery-layout-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val landscape = writeFrame(File(folder, "landscape.jpg"), 640, 360, Color.CYAN)
            val portrait = writeFrame(File(folder, "portrait-large.jpg"), 1440, 2560, Color.MAGENTA)
            val projectItems = (0 until 18).map { index ->
                val projectFolder = File(folder, "movie-$index").apply { mkdirs() }
                ProjectGalleryItem(
                    project = AnimationProject("movie-$index", "Movie $index", projectFolder, index.toLong()),
                    frameCount = index + 1,
                    thumbnailFile = if (index % 2 == 0) landscape else portrait,
                    previewFrames = listOf(AnimationFrame("frame-$index", if (index % 2 == 0) landscape else portrait))
                )
            }
            var mode by mutableStateOf(ProjectGalleryView.LARGE)
            composeRule.setContent {
                MaterialTheme {
                    ProjectGalleryScreen(
                        projects = projectItems,
                        activeId = projectItems.first().project.id,
                        busy = false,
                        message = null,
                        viewMode = mode,
                        onViewModeChange = { mode = it },
                        onOpen = {},
                        onCreate = {},
                        onRename = { _, _ -> },
                        onDuplicate = { _, _ -> },
                        onDelete = {},
                        onBack = {},
                        allFolders = listOf(AnimationFolder("folder-one", "Sample Folder", null)),
                        folderProjectCounts = mapOf("folder-one" to 3)
                    )
                }
            }

            val fixedRegion = composeRule.onNodeWithTag("gallery-fixed-region", useUnmergedTree = true)
            val previewPanel = composeRule.onNodeWithTag("gallery-preview-panel", useUnmergedTree = true)
            val collection = composeRule.onNodeWithTag("gallery-collection", useUnmergedTree = true)
            val image = composeRule.onNodeWithTag("gallery-preview-image", useUnmergedTree = true)
            composeRule.waitUntil(5_000L) { runCatching { image.fetchSemanticsNode(); true }.getOrDefault(false) }
            fixedRegion.assertExists()
            composeRule.onNodeWithTag("gallery-new-movie-button", useUnmergedTree = true).assertExists()
            composeRule.onNodeWithTag("gallery-location-controls", useUnmergedTree = true).assertExists()
            collection.assertExists()
            composeRule.onNodeWithText("Sample Folder", substring = true).assertExists()

            val fixedBounds = fixedRegion.fetchSemanticsNode().boundsInRoot
            val previewBounds = previewPanel.fetchSemanticsNode().boundsInRoot
            val imageBounds = image.fetchSemanticsNode().boundsInRoot
            assertEquals(previewBounds.width, imageBounds.width, 1f)
            assertEquals(previewBounds.height, imageBounds.height, 1f)

            listOf("Small / 小圖", "List / 清單", "Large / 大圖").forEach { label ->
                composeRule.onNodeWithText(label, substring = true).performClick()
                composeRule.waitForIdle()
                val fixedAfterSwitch = fixedRegion.fetchSemanticsNode().boundsInRoot
                val previewAfterSwitch = previewPanel.fetchSemanticsNode().boundsInRoot
                assertEquals(fixedBounds.top, fixedAfterSwitch.top, 1f)
                assertEquals(fixedBounds.height, fixedAfterSwitch.height, 1f)
                assertEquals(previewBounds.height, previewAfterSwitch.height, 1f)
            }

            val firstPreviewDescription = image.fetchSemanticsNode().config[SemanticsProperties.ContentDescription].joinToString()
            collection.performTouchInput { swipeUp() }
            composeRule.waitUntil(5_000L) {
                runCatching {
                    image.fetchSemanticsNode().config[SemanticsProperties.ContentDescription]
                        .joinToString().let { it.isNotBlank() && it != firstPreviewDescription }
                }.getOrDefault(false)
            }
            assertEquals(fixedBounds.top, fixedRegion.fetchSemanticsNode().boundsInRoot.top, 1f)
            assertEquals(fixedBounds.height, fixedRegion.fetchSemanticsNode().boundsInRoot.height, 1f)
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
