package com.animationworkshop.app

import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Properties
import java.util.UUID

internal data class AnimationFrame(val id: String, val file: File, val duration: Int = 1)

/** Keeps legacy JPEGs in place and stores editable sequence metadata beside them. */
internal class FrameStore(private val projectFolder: File) {
    private val manifest = AtomicFile(File(projectFolder, "frames.json"))
    private val pendingDelete = AtomicFile(File(projectFolder, ".pending-frame-deletion.json"))
    private val activeStagingCaptures = mutableSetOf<String>()

    @Synchronized
    fun frames(): List<AnimationFrame> {
        if (!projectFolder.exists()) projectFolder.mkdirs()
        recoverPendingDeletion()
        recoverAbandonedStagingCaptures()
        val entries = readManifest()
        val knownFiles = entries.map { it.file.name }.toSet()
        val unlisted = photoFiles().filterNot { it.name in knownFiles }.sortedBy { it.name }
        val combined = entries + unlisted.map { AnimationFrame(UUID.randomUUID().toString(), it) }
        if (combined != entries || !File(projectFolder, "frames.json").isFile) writeManifest(combined)
        return combined
    }

    @Synchronized
    fun nextFrame(): File {
        if (!projectFolder.exists()) projectFolder.mkdirs()
        val nextIndex = (photoFiles().maxOfOrNull { file ->
            file.nameWithoutExtension.substringAfter("frame_", "0").toIntOrNull() ?: 0
        } ?: 0) + 1
        return File(projectFolder, "frame_${nextIndex.toString().padStart(6, '0')}.jpg")
    }

    /** Temporary CameraX source file for grouped timelapse capture; it is not part of the movie. */
    @Synchronized
    fun newStagingCapture(): File {
        if (!projectFolder.exists() && !projectFolder.mkdirs()) error("Could not create the animation project folder")
        return File(projectFolder, ".capture-${UUID.randomUUID()}.tmp").also { activeStagingCaptures += it.name }
    }

    /** Keep a successfully captured source photo as an ordinary frame if stacking fails. */
    @Synchronized
    fun preserveStagingCapture(file: File): Boolean {
        activeStagingCaptures -= file.name
        if (file.parentFile != projectFolder || !file.isFile || file.length() <= 0L) return false
        val destination = nextFrame()
        if (!file.renameTo(destination)) return false
        // frames() discovers the renamed file even if writing the manifest fails.
        recordCapturedFrame(destination)
        return true
    }

    @Synchronized
    fun discardStagingCapture(file: File) {
        activeStagingCaptures -= file.name
        file.delete()
    }

    /** A process death during stacking leaves staged photos on disk; restore them on next launch. */
    private fun recoverAbandonedStagingCaptures() {
        val abandoned = projectFolder.listFiles { file ->
            file.isFile && file.name.startsWith(".capture-") && file.name.endsWith(".tmp") && file.name !in activeStagingCaptures
        }?.sortedWith(compareBy<File>({ it.lastModified() }, { it.name })).orEmpty()
        abandoned.forEach { file ->
            if (file.length() <= 0L) {
                file.delete()
            } else {
                val destination = nextFrame()
                file.renameTo(destination)
            }
        }
    }

    @Synchronized
    fun recordCapturedFrame(file: File) {
        val current = frames()
        if (file.parentFile == projectFolder && current.none { it.file.name == file.name }) {
            writeManifest(current + AnimationFrame(UUID.randomUUID().toString(), file))
        }
    }

    @Synchronized
    fun moveFrame(id: String, delta: Int): List<AnimationFrame> {
        val current = frames().toMutableList()
        val index = current.indexOfFirst { it.id == id }
        val target = index + delta.coerceIn(-1, 1)
        if (index >= 0 && target in current.indices) {
            val item = current.removeAt(index)
            current.add(target, item)
            writeManifest(current)
        }
        return current
    }

    @Synchronized
    fun setDuration(id: String, duration: Int): List<AnimationFrame> {
        val current = frames().map { if (it.id == id) it.copy(duration = duration.coerceIn(1, 10)) else it }
        writeManifest(current)
        return current
    }

    @Synchronized
    fun setAllDurations(duration: Int): List<AnimationFrame> {
        val updated = frames().map { it.copy(duration = duration.coerceIn(1, 10)) }
        writeManifest(updated)
        return updated
    }

    @Synchronized
    fun adjustDurations(ids: Set<String>, amount: Int): List<AnimationFrame> {
        val current = frames()
        if (ids.isEmpty() || amount == 0) return current
        val updated = FrameSequenceOperations.adjustDurations(current, ids, amount)
        writeManifest(updated)
        return updated
    }

    @Synchronized
    fun duplicateFrame(id: String): List<AnimationFrame> {
        val current = frames().toMutableList()
        val index = current.indexOfFirst { it.id == id }
        if (index < 0) return current
        val original = current[index]
        if (!original.file.isFile) return current
        val copy = File(projectFolder, "duplicate_${UUID.randomUUID()}.jpg")
        original.file.copyTo(copy, overwrite = false)
        current.add(index + 1, AnimationFrame(UUID.randomUUID().toString(), copy, original.duration))
        try {
            writeManifest(current)
        } catch (exception: Exception) {
            copy.delete()
            throw exception
        }
        return current
    }

    @Synchronized
    fun deleteFrame(id: String): List<AnimationFrame> {
        return deleteFrames(setOf(id))
    }

    @Synchronized
    fun deleteFrames(ids: Set<String>): List<AnimationFrame> {
        if (ids.isEmpty()) return frames()
        val current = frames().toMutableList()
        val selected = current.filter { it.id in ids }
        if (selected.isEmpty()) return current
        val output = pendingDelete.startWrite()
        try {
            output.write(JSONObject().put("frameIds", JSONArray(selected.map { it.id })).toString().toByteArray(Charsets.UTF_8))
            pendingDelete.finishWrite(output)
        } catch (failure: Throwable) {
            pendingDelete.failWrite(output)
            throw failure
        }
        recoverPendingDeletion()
        return readManifest()
    }

    @Synchronized
    fun deleteConfirmationEnabled(): Boolean = readProjectProperties().getProperty("confirmFrameDeletion")?.toBooleanStrictOrNull() ?: true

    @Synchronized
    fun saveDeleteConfirmationEnabled(enabled: Boolean) {
        updateProjectProperties { setProperty("confirmFrameDeletion", enabled.toString()) }
    }

    @Synchronized
    fun timelineZoomLevel(): Int = readProjectProperties().getProperty("timelineZoom")?.toIntOrNull()?.coerceIn(0, 2) ?: 1

    @Synchronized
    fun saveTimelineZoomLevel(level: Int) {
        updateProjectProperties { setProperty("timelineZoom", level.coerceIn(0, 2).toString()) }
    }

    @Synchronized
    fun initializeFromFrames(sourceFrames: List<AnimationFrame>, fps: Int, rotationDegrees: Int = 0) {
        check(frames().isEmpty()) { "A new movie project must be empty before frames are copied" }
        if (sourceFrames.isEmpty()) return
        val copied = mutableListOf<AnimationFrame>()
        try {
            sourceFrames.forEachIndexed { index, source ->
                check(source.file.isFile) { "A source photograph is missing" }
                val target = File(projectFolder, "frame_${(index + 1).toString().padStart(6, '0')}.jpg")
                source.file.copyTo(target, overwrite = false)
                copied += AnimationFrame(UUID.randomUUID().toString(), target, source.duration.coerceIn(1, 10))
            }
            writeManifest(copied)
            savePlaybackFps(fps)
            saveMovieRotationDegrees(rotationDegrees)
        } catch (failure: Throwable) {
            copied.forEach { it.file.delete() }
            throw failure
        }
    }

    private fun readProjectProperties(): Properties {
        val properties = Properties()
        runCatching { AtomicFile(File(projectFolder, "project.properties")).openRead().use(properties::load) }
        return properties
    }

    /** A small journal makes a multi-frame delete finish safely if Android stops the process midway. */
    private fun recoverPendingDeletion() {
        if (!pendingDelete.baseFile.isFile) return
        val ids = runCatching {
            val array = JSONObject(pendingDelete.openRead().bufferedReader().use { it.readText() }).getJSONArray("frameIds")
            (0 until array.length()).map { array.getString(it) }.toSet()
        }.getOrDefault(emptySet())
        val all = readManifest()
        val selected = all.filter { it.id in ids }
        val trashFolder = File(projectFolder, ".deleted/${pendingDelete.baseFile.nameWithoutExtension}")
        if (!trashFolder.exists() && !trashFolder.mkdirs()) error("Could not prepare the selected photos for deletion")
        selected.filter { it.file.isFile }.forEachIndexed { index, frame ->
            val trashFile = File(trashFolder, "${index}_${frame.file.name}")
            check(frame.file.renameTo(trashFile)) { "Could not safely remove a selected photo" }
        }
        writeManifest(all.filterNot { it.id in ids })
        if (readProjectProperties().getProperty("effect.freezeFrameId")?.let { it in ids } == true) {
            updateProjectProperties { remove("effect.freezeFrameId") }
        }
        trashFolder.deleteRecursively()
        pendingDelete.baseFile.delete()
    }

    private fun updateProjectProperties(change: Properties.() -> Unit) {
        if (!projectFolder.exists() && !projectFolder.mkdirs()) error("Could not create the animation project folder")
        val settingsFile = AtomicFile(File(projectFolder, "project.properties"))
        val properties = readProjectProperties().apply(change)
        val output = settingsFile.startWrite()
        try {
            properties.store(output, "Animation Workshop project settings")
            settingsFile.finishWrite(output)
        } catch (exception: Exception) {
            settingsFile.failWrite(output)
            throw exception
        }
    }

    @Synchronized
    fun playbackFps(): Int = readProjectProperties().getProperty("playbackFps")?.toIntOrNull()?.coerceIn(1, 24) ?: 8

    @Synchronized
    fun savePlaybackFps(fps: Int) {
        updateProjectProperties { setProperty("playbackFps", fps.coerceIn(1, 24).toString()) }
    }

    @Synchronized
    fun movieRotationDegrees(): Int = readProjectProperties()
        .getProperty("movieRotationDegrees")?.toIntOrNull()?.let { ((it % 360) + 360) % 360 } ?: 0

    @Synchronized
    fun saveMovieRotationDegrees(degrees: Int) {
        val normalized = ((degrees % 360) + 360) % 360
        require(normalized % 90 == 0) { "Movie rotation must use 90-degree steps" }
        updateProjectProperties { setProperty("movieRotationDegrees", normalized.toString()) }
    }

    @Synchronized
    fun movieEffects(): MovieEffects {
        val properties = readProjectProperties()
        val image = runCatching {
            ImageEffectType.valueOf(properties.getProperty("effect.image", ImageEffectType.NONE.name))
        }.getOrDefault(ImageEffectType.NONE)
        val timeline = runCatching {
            TimelineEffectType.valueOf(properties.getProperty("effect.timeline", TimelineEffectType.NORMAL.name))
        }.getOrDefault(TimelineEffectType.NORMAL)
        return MovieEffects(
            image = image,
            strength = properties.getProperty("effect.strength")?.toFloatOrNull() ?: 1f,
            timeline = timeline,
            freezeFrameId = properties.getProperty("effect.freezeFrameId"),
            freezeMultiplier = properties.getProperty("effect.freezeMultiplier")?.toIntOrNull() ?: 3
        ).normalized()
    }

    @Synchronized
    fun saveMovieEffects(effects: MovieEffects) {
        val settings = effects.normalized()
        updateProjectProperties {
            setProperty("effect.image", settings.image.name)
            setProperty("effect.strength", settings.strength.toString())
            setProperty("effect.timeline", settings.timeline.name)
            if (settings.freezeFrameId == null) remove("effect.freezeFrameId")
            else setProperty("effect.freezeFrameId", settings.freezeFrameId)
            setProperty("effect.freezeMultiplier", settings.freezeMultiplier.toString())
        }
    }

    private fun photoFiles(): List<File> = projectFolder.listFiles { file ->
        file.isFile && file.extension.equals("jpg", ignoreCase = true)
    }?.toList() ?: emptyList()

    private fun readManifest(): List<AnimationFrame> = runCatching {
        val root = JSONObject(manifest.openRead().bufferedReader().use { it.readText() })
        val array = root.getJSONArray("frames")
        buildList {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                val name = item.getString("file")
                if (File(name).name != name || !name.endsWith(".jpg", ignoreCase = true)) continue
                add(AnimationFrame(item.optString("id").ifBlank { UUID.randomUUID().toString() }, File(projectFolder, name), item.optInt("duration", 1).coerceIn(1, 10)))
            }
        }
    }.getOrElse {
        photoFiles().sortedBy { file -> file.name }.map { AnimationFrame(UUID.randomUUID().toString(), it) }
    }

    private fun writeManifest(frames: List<AnimationFrame>) {
        if (!projectFolder.exists() && !projectFolder.mkdirs()) error("Could not create the animation project folder")
        val array = JSONArray()
        frames.forEach { frame ->
            array.put(JSONObject().put("id", frame.id).put("file", frame.file.name).put("duration", frame.duration.coerceIn(1, 10)))
        }
        val output = manifest.startWrite()
        try {
            output.write(JSONObject().put("version", 1).put("frames", array).toString().toByteArray(Charsets.UTF_8))
            manifest.finishWrite(output)
        } catch (exception: Exception) {
            manifest.failWrite(output)
            throw exception
        }
    }
}
