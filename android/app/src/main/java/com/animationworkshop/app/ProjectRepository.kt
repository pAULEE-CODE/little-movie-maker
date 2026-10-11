package com.animationworkshop.app

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

internal data class AnimationProject(
    val id: String,
    val name: String,
    val folder: File,
    val modifiedAt: Long
)

/** Owns the project catalog and keeps each animation's existing FrameStore data isolated. */
internal class ProjectRepository(context: Context) {
    private val appContext = context.applicationContext
    private val root = File(appContext.filesDir, "animation_projects")
    private val projectsFolder = File(root, "projects")
    private val catalog = AtomicFile(File(root, "projects.json"))
    private val preferences = appContext.getSharedPreferences("animation_workshop", Context.MODE_PRIVATE)
    private val legacyFolder = File(root, "my_first_animation")

    @Synchronized
    fun initialize(): List<AnimationProject> {
        if (!projectsFolder.exists() && !projectsFolder.mkdirs()) error("Could not create the project library")
        migrateLegacyProjectIfNeeded()
        var projects = readProjects()
        if (projects.isEmpty() && !catalog.baseFile.exists()) {
            projects = listOf(create("My First Movie / 我的第一部電影"))
        }
        val remembered = preferences.getString(KEY_ACTIVE_PROJECT, null)
        if (projects.none { it.id == remembered }) setActiveProjectId(projects.first().id)
        return projects
    }

    @Synchronized
    fun projects(): List<AnimationProject> {
        if (!projectsFolder.isDirectory) return emptyList()
        return readProjects()
    }

    fun activeProjectId(): String? = preferences.getString(KEY_ACTIVE_PROJECT, null)

    fun setActiveProjectId(id: String) {
        require(validId(id))
        check(preferences.edit().putString(KEY_ACTIVE_PROJECT, id).commit()) { "Could not save the active project" }
    }

    fun frameStore(project: AnimationProject): FrameStore = FrameStore(project.folder)

    @Synchronized
    fun create(name: String): AnimationProject {
        val id = UUID.randomUUID().toString()
        val folder = File(projectsFolder, id)
        if (!folder.mkdirs()) error("Could not create a new movie")
        val project = AnimationProject(id, normalizedName(name), folder, System.currentTimeMillis())
        try {
            writeProject(project)
            writeCatalog((readProjects() + project).distinctBy { it.id })
        } catch (failure: Throwable) {
            folder.deleteRecursively()
            throw failure
        }
        return project
    }

    @Synchronized
    fun rename(id: String, name: String): List<AnimationProject> {
        val current = requireProject(id)
        writeProject(current.copy(name = normalizedName(name), modifiedAt = System.currentTimeMillis()))
        return readProjects()
    }

    @Synchronized
    fun duplicate(id: String, name: String): List<AnimationProject> {
        val source = requireProject(id)
        val duplicateId = UUID.randomUUID().toString()
        val targetFolder = File(projectsFolder, duplicateId)
        val stagingFolder = File(projectsFolder, ".copy-$duplicateId")
        check(stagingFolder.mkdirs()) { "Could not prepare a copy of this movie" }
        try {
            copyDirectory(source.folder, stagingFolder)
            val duplicate = AnimationProject(duplicateId, normalizedName(name), targetFolder, System.currentTimeMillis())
            writeProject(duplicate.copy(folder = stagingFolder))
            check(stagingFolder.renameTo(targetFolder)) { "Could not finish copying this movie" }
            val saved = duplicate.copy(modifiedAt = System.currentTimeMillis())
            writeProject(saved)
            val updated = (readProjects() + saved).distinctBy { it.id }
            writeCatalog(updated)
            return updated
        } catch (failure: Throwable) {
            stagingFolder.deleteRecursively()
            targetFolder.deleteRecursively()
            throw failure
        }
    }

    @Synchronized
    fun delete(id: String): List<AnimationProject> {
        val project = requireProject(id)
        val trash = File(projectsFolder, ".delete-$id")
        check(!trash.exists()) { "A project cleanup is already in progress" }
        check(project.folder.renameTo(trash)) { "Could not safely remove this movie" }
        try {
            val updated = readProjects().filterNot { it.id == id }
            writeCatalog(updated)
            check(trash.deleteRecursively()) { "The project was removed from the gallery, but its files could not be cleaned up" }
            if (activeProjectId() == id) {
                val next = updated.firstOrNull()?.id
                if (next == null) preferences.edit().remove(KEY_ACTIVE_PROJECT).commit()
                else setActiveProjectId(next)
            }
            return updated
        } catch (failure: Throwable) {
            if (!project.folder.exists()) trash.renameTo(project.folder)
            throw failure
        }
    }

    private fun migrateLegacyProjectIfNeeded() {
        val existing = readProjects()
        if (existing.any { it.id == LEGACY_PROJECT_ID }) return
        if (!legacyFolder.isDirectory) return
        val staging = File(projectsFolder, ".migration-$LEGACY_PROJECT_ID")
        val destination = File(projectsFolder, LEGACY_PROJECT_ID)
        if (destination.isDirectory) {
            val recovered = readProject(destination) ?: return
            writeCatalog((existing + recovered).distinctBy { it.id })
            return
        }
        staging.deleteRecursively()
        check(staging.mkdirs()) { "Could not prepare the existing animation for migration" }
        try {
            copyDirectory(legacyFolder, staging)
            val migrated = AnimationProject(
                LEGACY_PROJECT_ID,
                readLegacyName() ?: "My First Movie / 我的第一部電影",
                destination,
                System.currentTimeMillis()
            )
            verifyCopiedTree(legacyFolder, staging)
            writeProject(migrated.copy(folder = staging))
            check(staging.renameTo(destination)) { "Could not finish migrating the existing animation" }
            val completed = migrated.copy(modifiedAt = latestModified(destination))
            writeProject(completed)
            writeCatalog((existing + completed).distinctBy { it.id })
        } catch (failure: Throwable) {
            staging.deleteRecursively()
            throw failure
        }
        // The original legacy folder intentionally remains untouched as a recovery copy.
    }

    private fun readLegacyName(): String? = runCatching {
        val properties = java.util.Properties()
        AtomicFile(File(legacyFolder, "project.properties")).openRead().use(properties::load)
        properties.getProperty("projectName")?.takeIf { it.isNotBlank() }
    }.getOrNull()

    private fun readProjects(): List<AnimationProject> {
        val fromCatalog = runCatching {
            val items = JSONObject(catalog.openRead().bufferedReader().use { it.readText() }).getJSONArray("projects")
            buildList {
                for (index in 0 until items.length()) {
                    val item = items.getJSONObject(index)
                    val id = item.getString("id")
                    if (!validId(id)) continue
                    readProject(File(projectsFolder, id))?.let(::add)
                }
            }
        }.getOrDefault(emptyList())
        val knownIds = fromCatalog.map { it.id }.toSet()
        val recovered = projectsFolder.listFiles()
            ?.filter { it.isDirectory && validId(it.name) && it.name !in knownIds }
            ?.mapNotNull(::readProject)
            .orEmpty()
        val result = (fromCatalog + recovered).distinctBy { it.id }
        if (result.size != fromCatalog.size) writeCatalog(result)
        return result
    }

    private fun readProject(folder: File): AnimationProject? = runCatching {
        val json = JSONObject(File(folder, PROJECT_FILE).readText())
        val id = json.getString("id")
        if (!validId(id) || folder.name != id) return null
        AnimationProject(id, normalizedName(json.optString("name")), folder, latestModified(folder).coerceAtLeast(json.optLong("modifiedAt")))
    }.getOrNull()

    private fun writeProject(project: AnimationProject) {
        val file = AtomicFile(File(project.folder, PROJECT_FILE))
        val output = file.startWrite()
        try {
            output.write(JSONObject()
                .put("id", project.id)
                .put("name", normalizedName(project.name))
                .put("modifiedAt", project.modifiedAt)
                .toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(output)
        } catch (failure: Throwable) {
            file.failWrite(output)
            throw failure
        }
    }

    private fun writeCatalog(projects: List<AnimationProject>) {
        if (!root.exists() && !root.mkdirs()) error("Could not save the project library")
        val array = JSONArray()
        projects.forEach { project ->
            array.put(JSONObject().put("id", project.id).put("name", project.name).put("modifiedAt", latestModified(project.folder)))
        }
        val output = catalog.startWrite()
        try {
            output.write(JSONObject().put("version", 1).put("projects", array).toString().toByteArray(Charsets.UTF_8))
            catalog.finishWrite(output)
        } catch (failure: Throwable) {
            catalog.failWrite(output)
            throw failure
        }
    }

    private fun requireProject(id: String): AnimationProject = readProjects().firstOrNull { it.id == id }
        ?: error("This movie is no longer available")

    private fun verifyCopiedTree(source: File, target: File) {
        val sourceFiles = source.walkTopDown().filter { it.isFile }.associateBy { it.relativeTo(source).path }
        val targetFiles = target.walkTopDown().filter { it.isFile }.associateBy { it.relativeTo(target).path }
        check(sourceFiles.keys == targetFiles.keys) { "The existing animation could not be fully copied" }
        sourceFiles.forEach { (relative, file) ->
            check(file.length() == targetFiles.getValue(relative).length()) { "A photo could not be safely migrated" }
        }
    }

    private fun copyDirectory(source: File, target: File) {
        source.walkTopDown().forEach { entry ->
            val relative = entry.relativeTo(source).path
            if (relative.isEmpty()) return@forEach
            val destination = File(target, relative)
            if (entry.isDirectory) {
                if (!destination.exists() && !destination.mkdirs()) error("Could not copy the animation folder")
            } else {
                destination.parentFile?.let { if (!it.exists() && !it.mkdirs()) error("Could not copy the animation folder") }
                entry.copyTo(destination, overwrite = false)
            }
        }
    }

    private fun latestModified(folder: File): Long = folder.walkTopDown().filter { it.isFile }.maxOfOrNull { it.lastModified() } ?: System.currentTimeMillis()

    private fun normalizedName(name: String): String = name.trim().take(60).ifBlank { "My Little Movie" }

    private fun validId(id: String): Boolean = id.matches(Regex("[a-zA-Z0-9-]{1,80}"))

    private companion object {
        const val KEY_ACTIVE_PROJECT = "activeProjectId"
        const val PROJECT_FILE = "project.json"
        const val LEGACY_PROJECT_ID = "legacy-my-first-movie"
    }
}

