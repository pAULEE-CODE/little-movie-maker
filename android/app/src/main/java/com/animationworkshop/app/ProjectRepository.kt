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
    val modifiedAt: Long,
    val folderId: String? = null
)

internal data class AnimationFolder(val id: String, val name: String, val parentId: String?)

/** Owns the project catalog and keeps each animation's existing FrameStore data isolated. */
internal class ProjectRepository(context: Context) {
    private val appContext = context.applicationContext
    private val root = File(appContext.filesDir, "animation_projects")
    private val projectsFolder = File(root, "projects")
    private val catalog = AtomicFile(File(root, "projects.json"))
    private val foldersFile = AtomicFile(File(root, "folders.json"))
    private val pendingOperation = AtomicFile(File(projectsFolder, ".pending-project-operation.json"))
    private val pendingDeletion = AtomicFile(File(projectsFolder, ".pending-project-deletion.json"))
    private val preferences = appContext.getSharedPreferences("animation_workshop", Context.MODE_PRIVATE)
    private val legacyFolder = File(root, "my_first_animation")

    @Synchronized
    fun initialize(): List<AnimationProject> {
        if (!projectsFolder.exists() && !projectsFolder.mkdirs()) error("Could not create the project library")
        recoverPendingProjectOperation()
        recoverPendingProjectDeletion()
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

    @Synchronized
    fun projectsInFolder(folderId: String?): List<AnimationProject> = projects().filter { it.folderId == folderId }

    @Synchronized
    fun folders(parentId: String? = null): List<AnimationFolder> = readFolders().filter { it.parentId == parentId }

    @Synchronized
    fun createFolder(name: String, parentId: String? = null): List<AnimationFolder> {
        val current = readFolders()
        require(parentId == null || current.any { it.id == parentId }) { "The selected folder is no longer available" }
        val folder = AnimationFolder(UUID.randomUUID().toString(), normalizedName(name), parentId)
        writeFolders(current + folder)
        return current + folder
    }

    @Synchronized
    fun renameFolder(id: String, name: String): List<AnimationFolder> {
        val current = readFolders()
        require(current.any { it.id == id }) { "The selected folder is no longer available" }
        val updated = current.map { if (it.id == id) it.copy(name = normalizedName(name)) else it }
        writeFolders(updated)
        return updated
    }

    @Synchronized
    fun moveProjects(projectIds: Set<String>, destinationFolderId: String?): List<AnimationProject> {
        val all = readProjects()
        require(destinationFolderId == null || readFolders().any { it.id == destinationFolderId }) { "The destination folder is no longer available" }
        require(projectIds.all { id -> all.any { it.id == id } }) { "One or more selected movies are no longer available" }
        val updated = all.map { if (it.id in projectIds) it.copy(folderId = destinationFolderId) else it }
        writeCatalog(updated)
        return updated
    }

    fun activeProjectId(): String? = preferences.getString(KEY_ACTIVE_PROJECT, null)

    fun setActiveProjectId(id: String) {
        require(validId(id))
        check(preferences.edit().putString(KEY_ACTIVE_PROJECT, id).commit()) { "Could not save the active project" }
    }

    fun frameStore(project: AnimationProject): FrameStore = FrameStore(project.folder)

    @Synchronized
    fun create(name: String, folderId: String? = null): AnimationProject {
        require(folderId == null || readFolders().any { it.id == folderId }) { "The selected folder is no longer available" }
        val id = UUID.randomUUID().toString()
        val folder = File(projectsFolder, id)
        if (!folder.mkdirs()) error("Could not create a new movie")
        val project = AnimationProject(id, normalizedName(name), folder, System.currentTimeMillis(), folderId)
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
            val duplicate = AnimationProject(duplicateId, normalizedName(name), targetFolder, System.currentTimeMillis(), source.folderId)
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
        return deleteProjects(setOf(id))
    }

    @Synchronized
    fun deleteProjects(ids: Set<String>): List<AnimationProject> {
        if (ids.isEmpty()) return readProjects()
        val current = readProjects()
        val selected = current.filter { it.id in ids }
        require(selected.size == ids.size) { "One or more selected movies are no longer available" }
        val operationId = UUID.randomUUID().toString()
        val trash = File(projectsFolder, ".delete-$operationId")
        check(trash.mkdirs()) { "Could not prepare these movies for deletion" }
        val markerOutput = pendingDeletion.startWrite()
        try {
            markerOutput.write(JSONObject().put("trash", trash.name).put("projectIds", JSONArray(ids)).toString().toByteArray(Charsets.UTF_8))
            pendingDeletion.finishWrite(markerOutput)
        } catch (failure: Throwable) {
            pendingDeletion.failWrite(markerOutput)
            trash.deleteRecursively()
            throw failure
        }
        try {
            selected.forEach { project ->
                check(project.folder.renameTo(File(trash, project.id))) { "Could not safely remove a selected movie" }
            }
            val updated = current.filterNot { it.id in ids }
            writeCatalog(updated)
            if (activeProjectId() in ids) {
                val next = updated.firstOrNull()?.id
                if (next == null) preferences.edit().remove(KEY_ACTIVE_PROJECT).commit()
                else setActiveProjectId(next)
            }
            trash.deleteRecursively()
            pendingDeletion.baseFile.delete()
            return updated
        } catch (failure: Throwable) {
            selected.forEach { project ->
                val moved = File(trash, project.id)
                if (moved.exists() && !project.folder.exists()) moved.renameTo(project.folder)
            }
            trash.deleteRecursively()
            pendingDeletion.baseFile.delete()
            throw failure
        }
    }

    @Synchronized
    fun splitProject(id: String, cutIndex: Int, firstName: String, secondName: String): Pair<AnimationProject, AnimationProject> {
        val source = requireProject(id)
        val frames = frameStore(source).frames()
        val (firstFrames, secondFrames) = FrameSequenceOperations.split(frames, cutIndex)
        val saved = createFromSequences(
            listOf(
                SequenceProject(firstName, firstFrames, frameStore(source).playbackFps(), source.folderId),
                SequenceProject(secondName, secondFrames, frameStore(source).playbackFps(), source.folderId)
            )
        )
        return saved[0] to saved[1]
    }

    @Synchronized
    fun mergeProjects(idsInOrder: List<String>, name: String, fps: Int, folderId: String? = null): AnimationProject {
        require(idsInOrder.size >= 2 && idsInOrder.distinct().size == idsInOrder.size) { "Choose at least two different movies to merge" }
        require(folderId == null || readFolders().any { it.id == folderId }) { "The destination folder is no longer available" }
        val all = readProjects().associateBy { it.id }
        val selected = idsInOrder.map { all[it] ?: error("A selected movie is no longer available") }
        val mergedFrames = selected.flatMap { frameStore(it).frames() }
        return createFromSequences(listOf(SequenceProject(name, mergedFrames, fps, folderId))).single()
    }

    private data class SequenceProject(val name: String, val frames: List<AnimationFrame>, val fps: Int, val folderId: String?)

    /** Copies every source photo into staging first and publishes the complete operation in one catalog write. */
    private fun createFromSequences(sequences: List<SequenceProject>): List<AnimationProject> {
        require(sequences.isNotEmpty())
        val folders = readFolders().map { it.id }.toSet()
        require(sequences.all { it.folderId == null || it.folderId in folders }) { "The destination folder is no longer available" }
        val ids = sequences.map { UUID.randomUUID().toString() }
        val staged = mutableListOf<AnimationProject>()
        try {
            sequences.forEachIndexed { index, sequence ->
                val id = ids[index]
                val stage = File(projectsFolder, ".stage-$id")
                val target = File(projectsFolder, id)
                check(stage.mkdirs()) { "Could not prepare a new movie" }
                FrameStore(stage).initializeFromFrames(sequence.frames, sequence.fps)
                val project = AnimationProject(id, normalizedName(sequence.name), target, System.currentTimeMillis(), sequence.folderId)
                writeProject(project.copy(folder = stage))
                staged += project
            }
            writePendingOperation(ids)
        } catch (failure: Throwable) {
            ids.forEach { File(projectsFolder, ".stage-$it").deleteRecursively() }
            throw failure
        }
        try {
            staged.forEachIndexed { index, project ->
                val stage = File(projectsFolder, ".stage-${project.id}")
                check(stage.renameTo(project.folder)) { "Could not finish saving the new movies" }
                writeProject(project)
            }
            val current = readProjects()
            writeCatalog((current + staged).distinctBy { it.id })
            pendingOperation.baseFile.delete()
            return staged
        } catch (failure: Throwable) {
            staged.forEach { project ->
                File(projectsFolder, ".stage-${project.id}").deleteRecursively()
                project.folder.deleteRecursively()
            }
            pendingOperation.baseFile.delete()
            throw failure
        }
    }

    private fun writePendingOperation(ids: List<String>) {
        val output = pendingOperation.startWrite()
        try {
            output.write(JSONObject().put("projectIds", JSONArray(ids)).toString().toByteArray(Charsets.UTF_8))
            pendingOperation.finishWrite(output)
        } catch (failure: Throwable) {
            pendingOperation.failWrite(output)
            throw failure
        }
    }

    private fun recoverPendingProjectOperation() {
        if (!pendingOperation.baseFile.isFile) return
        val ids = runCatching {
            val array = JSONObject(pendingOperation.openRead().bufferedReader().use { it.readText() }).getJSONArray("projectIds")
            (0 until array.length()).map { array.getString(it) }.filter(::validId)
        }.getOrDefault(emptyList())
        val catalogIds = runCatching {
            val array = JSONObject(catalog.openRead().bufferedReader().use { it.readText() }).getJSONArray("projects")
            (0 until array.length()).map { array.getJSONObject(it).getString("id") }.toSet()
        }.getOrDefault(emptySet())
        // A completed atomic catalog update means all staged projects were committed. Otherwise
        // remove every staged or renamed project from the interrupted operation.
        if (!ids.all { it in catalogIds }) {
            ids.forEach { id ->
                File(projectsFolder, ".stage-$id").deleteRecursively()
                if (id !in catalogIds) File(projectsFolder, id).deleteRecursively()
            }
        }
        pendingOperation.baseFile.delete()
    }

    private fun recoverPendingProjectDeletion() {
        if (!pendingDeletion.baseFile.isFile) return
        val pending = runCatching { JSONObject(pendingDeletion.openRead().bufferedReader().use { it.readText() }) }.getOrNull()
        val ids = pending?.optJSONArray("projectIds")?.let { array -> (0 until array.length()).map { array.optString(it) }.filter(::validId) }.orEmpty()
        val trashName = pending?.optString("trash")?.takeIf { it.startsWith(".delete-") && File(it).name == it }
        val trash = trashName?.let { File(projectsFolder, it) }
        val catalogIds = readProjectIdsFromCatalog()
        if (ids.isNotEmpty() && ids.none { it in catalogIds }) {
            trash?.deleteRecursively()
        } else {
            ids.forEach { id ->
                val original = File(projectsFolder, id)
                val moved = trash?.let { File(it, id) }
                if (moved?.exists() == true && !original.exists()) moved.renameTo(original)
            }
            trash?.deleteRecursively()
        }
        pendingDeletion.baseFile.delete()
    }

    private fun readProjectIdsFromCatalog(): Set<String> = runCatching {
        val array = JSONObject(catalog.openRead().bufferedReader().use { it.readText() }).getJSONArray("projects")
        (0 until array.length()).map { array.getJSONObject(it).getString("id") }.toSet()
    }.getOrDefault(emptySet())

    private fun readFolders(): List<AnimationFolder> = runCatching {
        val array = JSONObject(foldersFile.openRead().bufferedReader().use { it.readText() }).getJSONArray("folders")
        buildList {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                val id = item.getString("id")
                val parentId = item.optString("parentId").takeIf { it.isNotBlank() }
                if (validId(id) && (parentId == null || validId(parentId))) add(AnimationFolder(id, normalizedName(item.optString("name")), parentId))
            }
        }
    }.getOrDefault(emptyList())

    private fun writeFolders(folders: List<AnimationFolder>) {
        val array = JSONArray()
        folders.forEach { folder -> array.put(JSONObject().put("id", folder.id).put("name", normalizedName(folder.name)).put("parentId", folder.parentId)) }
        val output = foldersFile.startWrite()
        try {
            output.write(JSONObject().put("version", 1).put("folders", array).toString().toByteArray(Charsets.UTF_8))
            foldersFile.finishWrite(output)
        } catch (failure: Throwable) {
            foldersFile.failWrite(output)
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
                    readProject(File(projectsFolder, id), item.optString("folderId").takeIf { it.isNotBlank() }, overrideFolder = true)?.let(::add)
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

    private fun readProject(folder: File, folderId: String? = null, overrideFolder: Boolean = false): AnimationProject? = runCatching {
        val json = JSONObject(File(folder, PROJECT_FILE).readText())
        val id = json.getString("id")
        if (!validId(id) || folder.name != id) return null
        val savedFolderId = if (overrideFolder) folderId else json.optString("folderId").takeIf { it.isNotBlank() }
        AnimationProject(id, normalizedName(json.optString("name")), folder, latestModified(folder).coerceAtLeast(json.optLong("modifiedAt")), savedFolderId)
    }.getOrNull()

    private fun writeProject(project: AnimationProject) {
        val file = AtomicFile(File(project.folder, PROJECT_FILE))
        val output = file.startWrite()
        try {
            output.write(JSONObject()
                .put("id", project.id)
                .put("name", normalizedName(project.name))
                .put("modifiedAt", project.modifiedAt)
                .put("folderId", project.folderId)
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
            array.put(JSONObject().put("id", project.id).put("name", project.name).put("modifiedAt", latestModified(project.folder)).put("folderId", project.folderId))
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
