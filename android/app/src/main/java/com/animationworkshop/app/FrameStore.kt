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

    @Synchronized
    fun frames(): List<AnimationFrame> {
        if (!projectFolder.exists()) projectFolder.mkdirs()
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
        val current = frames().toMutableList()
        val index = current.indexOfFirst { it.id == id }
        if (index < 0) return current
        val removed = current.removeAt(index)
        // A hidden subdirectory prevents a deleted legacy JPEG being rediscovered as an orphan.
        val trashFolder = File(projectFolder, ".deleted")
        val movedFile = if (removed.file.isFile) {
            if (!trashFolder.mkdirs() && !trashFolder.isDirectory) error("Could not prepare the deleted photo")
            File(trashFolder, "${UUID.randomUUID()}.jpg").also {
                if (!removed.file.renameTo(it)) error("Could not safely remove the selected photo")
            }
        } else null
        try {
            writeManifest(current)
            movedFile?.delete()
        } catch (exception: Exception) {
            if (movedFile != null) movedFile.renameTo(removed.file)
            throw exception
        }
        return current
    }

    @Synchronized
    fun playbackFps(): Int {
        val properties = Properties()
        runCatching { AtomicFile(File(projectFolder, "project.properties")).openRead().use(properties::load) }
        return properties.getProperty("playbackFps")?.toIntOrNull()?.coerceIn(1, 24) ?: 8
    }

    @Synchronized
    fun savePlaybackFps(fps: Int) {
        if (!projectFolder.exists() && !projectFolder.mkdirs()) error("Could not create the animation project folder")
        val settingsFile = AtomicFile(File(projectFolder, "project.properties"))
        val properties = Properties().apply { setProperty("playbackFps", fps.coerceIn(1, 24).toString()) }
        val output = settingsFile.startWrite()
        try {
            properties.store(output, "Animation Workshop project settings")
            settingsFile.finishWrite(output)
        } catch (exception: Exception) {
            settingsFile.failWrite(output)
            throw exception
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
