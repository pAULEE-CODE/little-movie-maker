package com.animationworkshop.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.os.SystemClock
import android.util.LruCache
import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateSetOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val PreviewBackground = Color(0xFF182522)
private val PreviewTeal = Color(0xFF31A996)
private val PreviewCoral = Color(0xFFFF5B59)

private sealed interface MovieExportState {
    data object Idle : MovieExportState
    data class Running(val percent: Int) : MovieExportState
    data class Complete(val movie: ExportedMovie) : MovieExportState
    data class Failed(val message: String) : MovieExportState
}

private object FrameBitmapCache : LruCache<String, Bitmap>(12 * 1024) {
    override fun sizeOf(key: String, value: Bitmap): Int = (value.allocationByteCount / 1024).coerceAtLeast(1)
}

@Composable
internal fun AnimationPreviewScreen(
    projectId: String,
    frames: List<AnimationFrame>,
    fps: Int,
    confirmFrameDeletion: Boolean,
    timelineZoomLevel: Int,
    settingsMessage: String?,
    mode: ModeDestination,
    exportRequest: Int,
    onExportRequestHandled: () -> Unit,
    onMovieExported: (ExportedMovie) -> Unit,
    onExportAbandoned: () -> Unit,
    onOpenModes: () -> Unit,
    onFpsChange: (Int) -> Unit,
    onSaveFps: (Int) -> Unit,
    onMoveFrame: (String, Int) -> Unit,
    onDuplicateFrame: (String, (String?) -> Unit) -> Unit,
    onDeleteFrames: (Set<String>) -> Unit,
    onSaveDeleteConfirmation: (Boolean) -> Unit,
    onSaveTimelineZoom: (Int) -> Unit,
    onDurationChange: (String, Int) -> Unit,
    onSetAllDurations: (Int) -> Unit,
    onSplitMovie: (Int, String, String) -> Unit,
    onBackToCamera: () -> Unit
) {
    val frameCount = frames.size
    var selectedId by remember { mutableStateOf(frames.firstOrNull()?.id) }
    var isPlaying by remember { mutableStateOf(false) }
    var pendingDeleteIds by remember { mutableStateOf<Set<String>?>(null) }
    var doNotAskAgain by remember { mutableStateOf(false) }
    var selectionMode by remember { mutableStateOf(false) }
    var rangeSelectionMode by remember { mutableStateOf(false) }
    val selectedIds = remember { mutableStateSetOf<String>() }
    var rangeAnchor by remember { mutableIntStateOf(0) }
    var splitCutIndex by remember { mutableIntStateOf(1) }
    var splitPartAName by remember(projectId) { mutableStateOf("Part A") }
    var splitPartBName by remember(projectId) { mutableStateOf("Part B") }
    var showSplitDialog by remember { mutableStateOf(false) }
    var allDuration by remember { mutableIntStateOf(1) }
    var confirmApplyAll by remember { mutableStateOf(false) }
    val selectedIndex = frames.indexOfFirst { it.id == selectedId }.let { if (it < 0) 0 else it }
    val selectedFrame = frames.getOrNull(selectedIndex)
    val latestSelectedId by rememberUpdatedState(selectedId)
    val latestOnSelect by rememberUpdatedState<(String?) -> Unit>(newValue = { id -> selectedId = id })
    val listState = rememberLazyListState()
    val view = LocalView.current
    val context = LocalContext.current
    val exportScope = androidx.compose.runtime.rememberCoroutineScope()
    var showExportConfirmation by remember { mutableStateOf(false) }
    var exportState by remember { mutableStateOf<MovieExportState>(MovieExportState.Idle) }
    var exportJob by remember { mutableStateOf<Job?>(null) }

    fun applyDeletion(ids: Set<String>) {
        if (ids.isEmpty()) return
        selectedId = FrameSequenceOperations.selectedAfterDeletion(frames, frames.filterNot { it.id in ids }, selectedIndex, ids)
        selectedIds.clear()
        selectionMode = false
        rangeSelectionMode = false
        onDeleteFrames(ids)
    }

    fun requestDelete(ids: Set<String>) {
        if (ids.isEmpty()) return
        isPlaying = false
        if (confirmFrameDeletion) pendingDeleteIds = ids else applyDeletion(ids)
    }

    DisposableEffect(view, isPlaying) {
        view.keepScreenOn = isPlaying
        onDispose { view.keepScreenOn = false }
    }

    LaunchedEffect(frames, projectId) {
        if (frames.none { it.id == selectedId }) selectedId = frames.firstOrNull()?.id
        selectedIds.retainAll(frames.mapTo(mutableSetOf()) { it.id })
    }

    LaunchedEffect(exportRequest) {
        if (exportRequest > 0) {
            showExportConfirmation = true
            onExportRequestHandled()
        }
    }

    LaunchedEffect(isPlaying, fps, frames) {
        if (!isPlaying || frameCount <= 1) return@LaunchedEffect

        val rate = fps.coerceIn(1, 24)
        fun durationNanos(frame: AnimationFrame) = 1_000_000_000L * frame.duration.coerceIn(1, 10) / rate
        var index = selectedIndex
        var nextDeadline = SystemClock.elapsedRealtimeNanos() + durationNanos(frames[index])
        while (isActive) {
            val remainingNanos = nextDeadline - SystemClock.elapsedRealtimeNanos()
            if (remainingNanos > 0L) {
                val waitMillis = ((remainingNanos + 999_999L) / 1_000_000L).coerceAtLeast(1L)
                delay(waitMillis)
            }

            val now = SystemClock.elapsedRealtimeNanos()
            while (now >= nextDeadline) {
                index = (index + 1) % frameCount
                latestOnSelect(frames[index].id)
                nextDeadline += durationNanos(frames[index])
            }
        }
    }

    LaunchedEffect(selectedId, frameCount, timelineZoomLevel) {
        if (frameCount > 0) listState.animateScrollToItem(selectedIndex.coerceIn(0, frameCount - 1))
    }

    Column(
        modifier = Modifier.fillMaxSize().background(PreviewBackground).windowInsetsPadding(WindowInsets.statusBars).padding(horizontal = 16.dp, vertical = 6.dp).padding(bottom = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(Modifier.fillMaxWidth().height(48.dp)) {
            Text(
                "‹  Camera",
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.align(Alignment.CenterStart).clip(RoundedCornerShape(16.dp)).clickable(onClick = onBackToCamera).padding(horizontal = 10.dp, vertical = 8.dp)
            )
            Button(
                onClick = onOpenModes,
                modifier = Modifier.align(Alignment.Center).height(42.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 30.dp, vertical = 7.dp),
                colors = ButtonDefaults.buttonColors(containerColor = PreviewTeal)
            ) { Text("MODES", fontSize = 15.sp, fontWeight = FontWeight.ExtraBold) }
        }

        Box(
            modifier = Modifier.fillMaxWidth().weight(1f).padding(top = 8.dp, bottom = 8.dp)
                .clip(RoundedCornerShape(20.dp)).background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            if (frameCount == 0) {
                Text(
                    "No photos yet\nGo back to the camera and take your first frame!",
                    color = Color.White.copy(alpha = .85f),
                    textAlign = TextAlign.Center,
                    fontSize = 16.sp,
                    modifier = Modifier.padding(28.dp)
                )
            } else {
                FramePhoto(
                    file = frames[selectedIndex.coerceIn(0, frameCount - 1)].file,
                    contentDescription = "Frame ${selectedIndex + 1}",
                    modifier = Modifier.fillMaxSize().padding(8.dp),
                    contentScale = ContentScale.Fit,
                    maximumDimensionPx = 1024
                )
            }
        }

        Text(
            if (frameCount == 0) "Frame 0 of 0" else "Frame ${selectedIndex + 1} of $frameCount   ·   ×${selectedFrame?.duration ?: 1}",
            color = Color.White,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        if (frameCount > 0) {
            val movieSeconds = frames.sumOf { it.duration.coerceIn(1, 10) } / fps.coerceIn(1, 24).toDouble()
            Text("Movie length: ${String.format(Locale.US, "%.1f", movieSeconds)} seconds", color = Color.White.copy(alpha = .75f), fontSize = 13.sp, modifier = Modifier.padding(bottom = 4.dp))
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = {
                    isPlaying = false
                    selectedId = frames.getOrNull(selectedIndex - 1)?.id
                },
                modifier = Modifier.weight(1f),
                enabled = frameCount > 0 && selectedIndex > 0,
                colors = ButtonDefaults.buttonColors(containerColor = PreviewTeal)
            ) { Text("‹ Previous", maxLines = 1) }

            Button(
                onClick = { isPlaying = !isPlaying },
                modifier = Modifier.weight(1f),
                enabled = frameCount > 1,
                colors = ButtonDefaults.buttonColors(containerColor = PreviewCoral)
            ) { Text(if (isPlaying) "Pause Ⅱ" else "Play ▶", maxLines = 1, fontWeight = FontWeight.Bold) }

            Button(
                onClick = {
                    isPlaying = false
                    selectedId = frames.getOrNull(selectedIndex + 1)?.id
                },
                modifier = Modifier.weight(1f),
                enabled = frameCount > 0 && selectedIndex < frameCount - 1,
                colors = ButtonDefaults.buttonColors(containerColor = PreviewTeal)
            ) { Text("Next ›", maxLines = 1) }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Speed", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(12.dp))
            Slider(
                value = fps.coerceIn(1, 24).toFloat(),
                onValueChange = { onFpsChange(it.roundToInt().coerceIn(1, 24)) },
                onValueChangeFinished = { onSaveFps(fps.coerceIn(1, 24)) },
                valueRange = 1f..24f,
                steps = 22,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            Text("$fps FPS", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
        if (settingsMessage != null) {
            Text(settingsMessage, color = Color(0xFFFFD3CC), fontSize = 12.sp, modifier = Modifier.padding(bottom = 2.dp))
        }

        if (frameCount == 0) {
            Box(Modifier.fillMaxWidth().height(112.dp), contentAlignment = Alignment.Center) {
                Text("Your photos will appear here", color = Color.White.copy(alpha = .65f), fontSize = 14.sp)
            }
        } else {
            LazyRow(
                state = listState,
                modifier = Modifier.fillMaxWidth().height(if (selectionMode) 142.dp else 112.dp)
                    .pointerInput(selectionMode, rangeSelectionMode, frames) {
                        if (selectionMode && rangeSelectionMode) {
                            detectDragGestures(
                                onDragStart = { start ->
                                    val index = listState.layoutInfo.visibleItemsInfo.firstOrNull { start.x >= it.offset && start.x < it.offset + it.size }?.index
                                    if (index != null) {
                                        rangeAnchor = index
                                        selectedIds.addAll(FrameSequenceOperations.rangeSelection(frames.map { it.id }, rangeAnchor, index))
                                        selectedId = frames.getOrNull(index)?.id
                                    }
                                },
                                onDrag = { change, _ ->
                                    val index = listState.layoutInfo.visibleItemsInfo.firstOrNull { change.position.x >= it.offset && change.position.x < it.offset + it.size }?.index
                                    if (index != null) {
                                        selectedIds.addAll(FrameSequenceOperations.rangeSelection(frames.map { it.id }, rangeAnchor, index))
                                        selectedId = frames.getOrNull(index)?.id
                                    }
                                    change.consume()
                                }
                            )
                        }
                    },
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                itemsIndexed(frames, key = { _, frame -> frame.id }) { index, frame ->
                    FrameThumbnail(
                        frame = frame,
                        frameNumber = index + 1,
                        selected = index == selectedIndex,
                        multiSelected = frame.id in selectedIds,
                        width = when (timelineZoomLevel.coerceIn(0, 2)) { 0 -> 64.dp; 2 -> 128.dp; else -> 92.dp },
                        onClick = {
                            isPlaying = false
                            if (selectionMode) {
                                if (frame.id in selectedIds) selectedIds.remove(frame.id) else selectedIds.add(frame.id)
                                selectedId = frame.id
                                rangeAnchor = index
                            } else selectedId = frame.id
                        },
                        onLongClick = {
                            isPlaying = false
                            selectionMode = true
                            rangeSelectionMode = false
                            selectedIds.clear()
                            selectedIds.add(frame.id)
                            selectedId = frame.id
                            rangeAnchor = index
                        }
                    )
                }
            }
        }

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Timeline", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { onSaveTimelineZoom((timelineZoomLevel - 1).coerceAtLeast(0)) }, enabled = timelineZoomLevel > 0) { Text("− Zoom") }
                Text("${timelineZoomLevel + 1}/3", color = Color.White.copy(alpha = .8f), fontSize = 12.sp)
                TextButton(onClick = { onSaveTimelineZoom((timelineZoomLevel + 1).coerceAtMost(2)) }, enabled = timelineZoomLevel < 2) { Text("+ Zoom") }
            }
        }

        if (selectionMode) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("${selectedIds.size} selected", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton(onClick = { rangeSelectionMode = !rangeSelectionMode }) { Text(if (rangeSelectionMode) "Scroll" else "Select range") }
                TextButton(onClick = { selectionMode = false; rangeSelectionMode = false; selectedIds.clear() }) { Text("Done") }
                Button(onClick = { requestDelete(selectedIds.toSet()) }, enabled = selectedIds.isNotEmpty(), colors = ButtonDefaults.buttonColors(containerColor = PreviewCoral)) { Text("Delete selected") }
            }
        }

    if (selectedFrame != null) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(onClick = { onMoveFrame(selectedFrame.id, -1) }, enabled = selectedIndex > 0, modifier = Modifier.weight(1f), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp, vertical = 8.dp)) { Text("← Move", maxLines = 1) }
            Button(onClick = { onMoveFrame(selectedFrame.id, 1) }, enabled = selectedIndex < frameCount - 1, modifier = Modifier.weight(1f), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp, vertical = 8.dp)) { Text("Move →", maxLines = 1) }
            Button(onClick = { onDuplicateFrame(selectedFrame.id) { duplicateId -> if (duplicateId != null) selectedId = duplicateId } }, modifier = Modifier.weight(1f), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp, vertical = 8.dp)) { Text("Copy", maxLines = 1) }
            Button(onClick = { isPlaying = false; requestDelete(setOf(selectedFrame.id)) }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = PreviewCoral), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp, vertical = 8.dp)) { Text("Delete", maxLines = 1) }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Hold", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(8.dp))
            Button(onClick = { onDurationChange(selectedFrame.id, selectedFrame.duration - 1) }, enabled = selectedFrame.duration > 1, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp)) { Text("−") }
            Text("×${selectedFrame.duration}", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 12.dp))
            Button(onClick = { onDurationChange(selectedFrame.id, selectedFrame.duration + 1) }, enabled = selectedFrame.duration < 10, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp)) { Text("+") }
            Spacer(Modifier.width(8.dp))
            Text("frames", color = Color.White.copy(alpha = .75f), fontSize = 13.sp)
        }
        if (frameCount > 1) {
            TextButton(onClick = { splitCutIndex = (selectedIndex + 1).coerceIn(1, frameCount - 1); showSplitDialog = true }, modifier = Modifier.align(Alignment.Start)) {
                Text("Split Movie / 分割影片")
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("All frames", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Button(onClick = { allDuration = (allDuration - 1).coerceAtLeast(1) }, enabled = allDuration > 1, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 3.dp)) { Text("−") }
            Text("×$allDuration", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Button(onClick = { allDuration = (allDuration + 1).coerceAtMost(10) }, enabled = allDuration < 10, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 3.dp)) { Text("+") }
            Button(onClick = { confirmApplyAll = true }, enabled = frameCount > 0, modifier = Modifier.weight(1f), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp, vertical = 6.dp)) { Text("Apply to all", maxLines = 1) }
        }
    }

    if (!confirmFrameDeletion) {
        TextButton(onClick = { onSaveDeleteConfirmation(true) }, modifier = Modifier.align(Alignment.Start)) { Text("Turn delete confirmation on") }
    }
    Button(
        onClick = { showExportConfirmation = true },
        enabled = frameCount > 0 && exportState !is MovieExportState.Running,
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp).height(54.dp),
        shape = RoundedCornerShape(18.dp),
        colors = ButtonDefaults.buttonColors(containerColor = PreviewCoral),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 18.dp, vertical = 8.dp)
    ) {
        Text("🎞  Export Movie", fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
    }
    }

    val framesToDelete = frames.filter { it.id in pendingDeleteIds.orEmpty() }
    if (confirmApplyAll && frameCount > 0) {
        AlertDialog(
            onDismissRequest = { confirmApplyAll = false },
            title = { Text("Change every frame?") },
            text = { Text("Set all $frameCount photographs to ×$allDuration duration?") },
            confirmButton = { TextButton(onClick = { confirmApplyAll = false; onSetAllDurations(allDuration) }) { Text("Apply") } },
            dismissButton = { TextButton(onClick = { confirmApplyAll = false }) { Text("Cancel") } }
        )
    }
    if (pendingDeleteIds != null && framesToDelete.isNotEmpty() && confirmFrameDeletion) {
        AlertDialog(
            onDismissRequest = { pendingDeleteIds = null; doNotAskAgain = false },
            title = { Text(if (framesToDelete.size == 1) "Delete this frame?" else "Delete ${framesToDelete.size} frames?") },
            text = {
                Column {
                    Text(if (framesToDelete.size == 1) "This photo will be removed from your animation." else "These photos will be removed from your animation.")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = doNotAskAgain, onCheckedChange = { doNotAskAgain = it })
                        Text("Do not ask again / 不再詢問")
                    }
                }
            },
            confirmButton = { TextButton(onClick = { val ids = pendingDeleteIds.orEmpty(); pendingDeleteIds = null; if (doNotAskAgain) onSaveDeleteConfirmation(false); doNotAskAgain = false; applyDeletion(ids) }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { pendingDeleteIds = null; doNotAskAgain = false }) { Text("Keep") } }
        )
    }
    if (pendingDeleteIds != null && framesToDelete.isNotEmpty() && !confirmFrameDeletion) {
        LaunchedEffect(pendingDeleteIds) { val ids = pendingDeleteIds.orEmpty(); pendingDeleteIds = null; applyDeletion(ids) }
    }

    if (showSplitDialog && frameCount > 1) {
        AlertDialog(
            onDismissRequest = { showSplitDialog = false },
            title = { Text("Split this movie / 分割影片") },
            text = {
                Column {
                    Text("Part A uses frames 1–$splitCutIndex. Part B starts at frame ${splitCutIndex + 1}. The original movie stays unchanged.")
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(splitCutIndex - 1, splitCutIndex).forEachIndexed { side, frameIndex ->
                            val cutFrame = frames.getOrNull(frameIndex)
                            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(if (side == 0) "Last in Part A" else "First in Part B", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                if (cutFrame != null) FramePhoto(
                                    file = cutFrame.file,
                                    contentDescription = "Frame ${frameIndex + 1} at the split point",
                                    modifier = Modifier.fillMaxWidth().height(72.dp).padding(top = 4.dp).clip(RoundedCornerShape(8.dp)).background(Color.Black),
                                    contentScale = ContentScale.Fit,
                                    maximumDimensionPx = 240
                                )
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { splitCutIndex = (splitCutIndex - 1).coerceAtLeast(1) }, enabled = splitCutIndex > 1) { Text("‹ Cut") }
                        Text("${splitCutIndex} | ${splitCutIndex + 1}", fontWeight = FontWeight.Bold)
                        TextButton(onClick = { splitCutIndex = (splitCutIndex + 1).coerceAtMost(frameCount - 1) }, enabled = splitCutIndex < frameCount - 1) { Text("Cut ›") }
                    }
                    OutlinedTextField(value = splitPartAName, onValueChange = { splitPartAName = it.take(60) }, label = { Text("Part A name") }, singleLine = true)
                    OutlinedTextField(value = splitPartBName, onValueChange = { splitPartBName = it.take(60) }, label = { Text("Part B name") }, singleLine = true)
                }
            },
            confirmButton = { TextButton(onClick = { showSplitDialog = false; onSplitMovie(splitCutIndex, splitPartAName, splitPartBName) }, enabled = splitPartAName.isNotBlank() && splitPartBName.isNotBlank()) { Text("Create both movies") } },
            dismissButton = { TextButton(onClick = { showSplitDialog = false }) { Text("Cancel") } }
        )
    }

    if (showExportConfirmation) {
        ExportConfirmationDialog(
            frames = frames,
            fps = fps,
            onCancel = { showExportConfirmation = false; onExportAbandoned() },
            onExport = { configuration ->
                showExportConfirmation = false
                exportState = MovieExportState.Running(0)
                exportJob = exportScope.launch {
                    try {
                        val movie = withContext(Dispatchers.IO) {
                            Mp4Exporter.export(context, frames, configuration, projectId) { percent ->
                                withContext(Dispatchers.Main) { exportState = MovieExportState.Running(percent) }
                            }
                        }
                        exportState = MovieExportState.Complete(movie)
                        onMovieExported(movie)
                    } catch (_: CancellationException) {
                        exportState = MovieExportState.Idle
                    } catch (exception: Exception) {
                        exportState = MovieExportState.Failed(exception.message ?: "The movie could not be exported.")
                    }
                }
            }
        )
    }

    when (val state = exportState) {
        MovieExportState.Idle -> Unit
        is MovieExportState.Running -> AlertDialog(
            onDismissRequest = { exportJob?.cancel() },
            title = { Text("Making your movie…") },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Text("${state.percent}% complete", modifier = Modifier.padding(top = 12.dp))
                }
            },
            confirmButton = { TextButton(onClick = { exportJob?.cancel() }) { Text("Cancel") } }
        )
        is MovieExportState.Complete -> AlertDialog(
            onDismissRequest = { exportState = MovieExportState.Idle },
            title = { Text("Movie saved!") },
            text = { Text("${state.movie.fileName}\nSaved in Movies/AnimationWorkshop") },
            confirmButton = {
                TextButton(onClick = {
                    runCatching {
                        val intent = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(state.movie.uri, "video/mp4")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(intent, "Open movie with"))
                    }.onFailure { exportState = MovieExportState.Failed("No video player could open this movie.") }
                }) { Text("Open Movie") }
            },
            dismissButton = { TextButton(onClick = { exportState = MovieExportState.Idle }) { Text("Done") } }
        )
        is MovieExportState.Failed -> AlertDialog(
            onDismissRequest = { exportState = MovieExportState.Idle },
            title = { Text("Export could not finish") },
            text = { Text(state.message) },
            confirmButton = { TextButton(onClick = { exportState = MovieExportState.Idle; onExportAbandoned() }) { Text("OK") } }
        )
    }
}

@Composable
private fun ExportConfirmationDialog(
    frames: List<AnimationFrame>,
    fps: Int,
    onCancel: () -> Unit,
    onExport: (ExportConfiguration) -> Unit
) {
    val result by produceState<Result<ExportConfiguration>?>(null, frames, fps) {
        value = withContext(Dispatchers.IO) { runCatching { Mp4Exporter.inspect(frames, fps) } }
    }
    val seconds = frames.sumOf { it.duration.coerceIn(1, 10) } / fps.coerceIn(1, 24).toDouble()
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Export Movie") },
        text = {
            Column {
                Text("Estimated duration: ${String.format(Locale.US, "%.2f", seconds)} seconds")
                val configuration = result?.getOrNull()
                if (configuration != null) {
                    Text("Resolution: ${configuration.width} × ${configuration.height}", modifier = Modifier.padding(top = 6.dp))
                    Text("Animation speed: ${configuration.projectFps} FPS", modifier = Modifier.padding(top = 4.dp))
                    if (configuration.encoderFps != configuration.projectFps) {
                        Text(
                            "Video stream: ${configuration.encoderFps} FPS. Frame timing is distributed to preserve the animation speed.",
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                } else if (result == null) {
                    Text("Checking video encoder…", modifier = Modifier.padding(top = 8.dp))
                } else {
                    Text(result?.exceptionOrNull()?.message ?: "No compatible video encoder is available.", modifier = Modifier.padding(top = 8.dp))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { result?.getOrNull()?.let(onExport) }, enabled = result?.isSuccess == true) { Text("Export") }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } }
    )
}

@Composable
private fun FrameThumbnail(
    frame: AnimationFrame,
    frameNumber: Int,
    selected: Boolean,
    multiSelected: Boolean,
    width: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Column(
        modifier = Modifier.width(width).height(106.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (multiSelected) Color(0xFFFFB64D) else if (selected) PreviewTeal else Color(0xFF2B3A36))
            .border(
                width = if (selected || multiSelected) 3.dp else 1.dp,
                color = if (selected || multiSelected) Color.White else Color.White.copy(alpha = .25f),
                shape = RoundedCornerShape(12.dp)
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        FramePhoto(
            file = frame.file,
            contentDescription = "Frame $frameNumber thumbnail",
            modifier = Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(7.dp)).background(Color.Black),
            contentScale = ContentScale.Fit,
            maximumDimensionPx = 360
        )
        if (multiSelected) {
            Text("✓", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.align(Alignment.End).padding(5.dp))
        }
        Text(
            "$frameNumber   ×${frame.duration}",
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            modifier = Modifier.padding(top = 2.dp)
        )
    }
}

@Composable
private fun FramePhoto(
    file: File,
    contentDescription: String,
    modifier: Modifier,
    contentScale: ContentScale,
    maximumDimensionPx: Int
) {
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.roundToPx() }.coerceAtLeast(1).coerceAtMost(maximumDimensionPx)
        val heightPx = with(density) { maxHeight.roundToPx() }.coerceAtLeast(1).coerceAtMost(maximumDimensionPx)
        val decoded by produceState(false to null as Bitmap?, file.absolutePath, widthPx, heightPx) {
            val result = withContext(Dispatchers.IO) { decodeSampledFrame(file, widthPx, heightPx) }
            value = true to result
        }
        val bitmap = decoded.second
        if (bitmap != null) {
            Image(
                bitmap = bitmap!!.asImageBitmap(),
                contentDescription = contentDescription,
                modifier = Modifier.fillMaxSize(),
                contentScale = contentScale
            )
        } else {
            Text(if (decoded.first) "Photo unavailable" else "Loading…", color = Color.White.copy(alpha = .65f), fontSize = 12.sp)
        }
    }
}

internal fun decodeSampledFrame(file: File, targetWidth: Int, targetHeight: Int): Bitmap? {
    if (!file.isFile) return null
    val cacheKey = "${file.absolutePath}:$targetWidth:$targetHeight"
    synchronized(FrameBitmapCache) { FrameBitmapCache.get(cacheKey)?.let { return it } }
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    val scaleRatio = maxOf(
        bounds.outWidth.toFloat() / targetWidth.coerceAtLeast(1),
        bounds.outHeight.toFloat() / targetHeight.coerceAtLeast(1)
    )
    val wholeRatio = ceil(scaleRatio).toInt().coerceAtLeast(1)
    var sampleSize = 1
    while (sampleSize <= wholeRatio / 2) sampleSize *= 2

    val decoded = BitmapFactory.decodeFile(
        file.absolutePath,
        BitmapFactory.Options().apply { inSampleSize = sampleSize.coerceAtLeast(1) }
    ) ?: return null

    val orientation = runCatching {
        ExifInterface(file.absolutePath).getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL
        )
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
    val transform = Matrix()
    when (orientation) {
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> transform.setScale(-1f, 1f)
        ExifInterface.ORIENTATION_ROTATE_180 -> transform.setRotate(180f)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> transform.setScale(1f, -1f)
        ExifInterface.ORIENTATION_TRANSPOSE -> {
            transform.setRotate(90f)
            transform.postScale(-1f, 1f)
        }
        ExifInterface.ORIENTATION_ROTATE_90 -> transform.setRotate(90f)
        ExifInterface.ORIENTATION_TRANSVERSE -> {
            transform.setRotate(-90f)
            transform.postScale(-1f, 1f)
        }
        ExifInterface.ORIENTATION_ROTATE_270 -> transform.setRotate(270f)
        else -> return decoded.also { bitmap -> synchronized(FrameBitmapCache) { FrameBitmapCache.put(cacheKey, bitmap) } }
    }
    val corrected = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, transform, true).also { correctedBitmap ->
        if (correctedBitmap !== decoded) decoded.recycle()
    }
    synchronized(FrameBitmapCache) { FrameBitmapCache.put(cacheKey, corrected) }
    return corrected
}
