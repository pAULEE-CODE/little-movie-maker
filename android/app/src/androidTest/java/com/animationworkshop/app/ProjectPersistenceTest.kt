package com.animationworkshop.app

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProjectPersistenceTest {
    private lateinit var base: Context
    private lateinit var filesRoot: File
    private lateinit var context: Context

    @Before
    fun setUp() {
        base = InstrumentationRegistry.getInstrumentation().targetContext
        filesRoot = File(base.cacheDir, "project-persistence-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val preferenceName = filesRoot.name
        context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = filesRoot
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                base.getSharedPreferences("$preferenceName-$name", mode)
        }
    }

    @After
    fun tearDown() {
        filesRoot.deleteRecursively()
        base.getSharedPreferences("${filesRoot.name}-animation_workshop", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun deletionPreferenceIsProjectScopedAndFrameDeletionPersistsOrder() {
        val repository = ProjectRepository(context)
        val projects = repository.initialize()
        val first = projects.first()
        val second = repository.create("Second movie")
        val firstStore = repository.frameStore(first)
        val secondStore = repository.frameStore(second)
        firstStore.saveDeleteConfirmationEnabled(false)
        assertFalse(FrameStore(first.folder).deleteConfirmationEnabled())
        assertTrue(secondStore.deleteConfirmationEnabled())

        val originalIds = captureTestFrames(firstStore, 4)
        firstStore.deleteFrames(setOf(originalIds[1], originalIds[2]))
        val reopenedFrames = FrameStore(first.folder).frames()
        assertEquals(listOf(originalIds[0], originalIds[3]), reopenedFrames.map { it.id })
        assertTrue(first.folder.listFiles { file -> file.extension == "jpg" }?.size == 2)
    }

    @Test
    fun folderOperationsPersistAndExistingProjectsStartAtRoot() {
        val repository = ProjectRepository(context)
        val project = repository.initialize().first()
        assertEquals(null, project.folderId)

        val outer = repository.createFolder("School").last()
        val inner = repository.createFolder("October", outer.id).last()
        repository.moveProjects(setOf(project.id), inner.id)

        val reopened = ProjectRepository(context).initialize()
        assertEquals(listOf(project.id), reopened.filter { it.folderId == inner.id }.map { it.id })
        assertEquals(listOf(inner.id), ProjectRepository(context).folders(outer.id).map { it.id })
        ProjectRepository(context).renameFolder(inner.id, "October Films")
        assertEquals("October Films", ProjectRepository(context).folders(outer.id).single().name)
    }

    @Test
    fun splitAndMergeCopyFramesWithoutChangingTheSource() {
        val repository = ProjectRepository(context)
        val source = repository.initialize().first()
        val sourceStore = repository.frameStore(source)
        captureTestFrames(sourceStore, 3)
        sourceStore.setDuration(sourceStore.frames()[1].id, 3)
        sourceStore.savePlaybackFps(12)

        val (partA, partB) = repository.splitProject(source.id, 2, "Part A", "Part B")
        assertEquals(3, repository.frameStore(source).frames().size)
        assertEquals(listOf(1, 3), repository.frameStore(partA).frames().map { it.duration })
        assertEquals(12, repository.frameStore(partA).playbackFps())
        assertEquals(1, repository.frameStore(partB).frames().size)

        val merged = repository.mergeProjects(listOf(partB.id, partA.id), "Reordered", 8)
        val mergedFrames = repository.frameStore(merged).frames()
        assertEquals(3, mergedFrames.size)
        assertNotEquals(repository.frameStore(partA).frames().first().id, mergedFrames.last().id)
        assertEquals(8, repository.frameStore(merged).playbackFps())
    }

    @Test
    fun legacyProjectMigrationKeepsTheOriginalRecoveryCopy() {
        val legacy = File(filesRoot, "animation_projects/my_first_animation").apply { check(mkdirs()) }
        File(legacy, "frame_000001.jpg").writeBytes(byteArrayOf(1, 2, 3))
        File(legacy, "project.properties").writeText("projectName=Legacy movie\n")

        val migrated = ProjectRepository(context).initialize().single { it.id == "legacy-my-first-movie" }
        assertEquals("Legacy movie", migrated.name)
        assertTrue(File(legacy, "frame_000001.jpg").isFile)
        assertEquals(1, ProjectRepository(context).frameStore(migrated).frames().size)
    }

    private fun captureTestFrames(store: FrameStore, count: Int): List<String> = buildList {
        repeat(count) { index ->
            val file = store.nextFrame()
            file.writeBytes(byteArrayOf(index.toByte(), 1, 2, 3))
            store.recordCapturedFrame(file)
            add(store.frames().last().id)
        }
    }
}
