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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.TextButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.graphics.graphicsLayer
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
    rotationDegrees: Int,
    effects: MovieEffects = MovieEffects(),
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
    onRotateMovie: (Int) -> Unit,
    onAdjustFrameDurations: (Set<String>, Int) -> Unit,
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
    val holdTargetFrames = if (selectionMode && selectedIds.isNotEmpty()) frames.filter { it.id in selectedIds } else listOfNotNull(selectedFrame)
    val holdTargetIds = holdTargetFrames.mapTo(linkedSetOf()) { it.id }
    val holdDurations = holdTargetFrames.map { it.duration }.distinct()
    val holdDurationLabel = if (holdDurations.size == 1) "×${holdDurations.single()}" else "Mixed"
    val latestSelectedId by rememberUpdatedState(selectedId)
    val latestOnSelect by rememberUpdatedState<(String?) -> Unit>(newValue = { id -> selectedId = id })
    val listState = rememberLazyListState()
    val view = LocalView.current
    val context = LocalContext.current
    val exportScope = androidx.compose.runtime.rememberCoroutineScope()
    var showExportConfirmation by remember { mutableStateOf(false) }
    var showMoreMenu by remember { mutableStateOf(false) }
    var showEditorTools by remember { mutableStateOf(false) }
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

    LaunchedEffect(isPlaying, fps, frames, effects) {
        val timeline = buildMovieTimeline(frames, effects)
        if (!isPlaying || timeline.size <= 1) return@LaunchedEffect
        val rate = fps.coerceIn(1, 24)
        fun durationNanos(entry: TimedMovieFrame) = 1_000_000_000L * entry.durationTicks / rate
        var index = timeline.indexOfFirst { it.frame.id == selectedId }.coerceAtLeast(0)
        var nextDeadline = SystemClock.elapsedRealtimeNanos() + durationNanos(timeline[index])
        while (isActive) {
            val remainingNanos = nextDeadline - SystemClock.elapsedRealtimeNanos()
            if (remainingNanos > 0L) {
                val waitMillis = ((remainingNanos + 999_999L) / 1_000_000L).coerceAtLeast(1L)
                delay(waitMillis)
            }

            val now = SystemClock.elapsedRealtimeNanos()
            while (now >= nextDeadline) {
                index = (index + 1) % timeline.size
                latestOnSelect(timeline[index].frame.id)
                nextDeadline += durationNanos(timeline[index])
            }
        }
    }

    LaunchedEffect(selectedId, frameCount, timelineZoomLevel) {
        if (frameCount > 0) listState.animateScrollToItem(selectedIndex.coerceIn(0, frameCount - 1))
    }

    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).windowInsetsPadding(WindowInsets.statusBars).padding(horizontal = 14.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(Modifier.fillMaxWidth().height(48.dp)) {
            LocalizedText(
                "‹  Camera",
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.align(Alignment.CenterStart).clip(RoundedCornerShape(16.dp)).clickable(onClick = onBackToCamera).padding(horizontal = 10.dp, vertical = 8.dp)
            )
            Button(
                onClick = onOpenModes,
                modifier = Modifier.align(Alignment.Center).height(42.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 30.dp, vertical = 7.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) { LocalizedText("MODES", fontSize = 15.sp, fontWeight = FontWeight.ExtraBold) }
        }

        Box(
            modifier = Modifier.fillMaxWidth().weight(1f).padding(top = 8.dp, bottom = 8.dp)
                .clip(RoundedCornerShape(20.dp)).background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            if (frameCount == 0) {
                LocalizedText(
                    "No photos yet\nGo back to the camera and take your first frame!",
                    color = Color.White.copy(alpha = .85f),
                    textAlign = TextAlign.Center,
                    fontSize = 16.sp,
                    modifier = Modifier.padding(28.dp)
                )
            } else {
                FramePhoto(
                    file = frames[selectedIndex.coerceIn(0, frameCount - 1)].file,
                    contentDescription = uiText("Frame ${selectedIndex + 1}"),
                    modifier = Modifier.fillMaxSize().padding(8.dp),
                    contentScale = ContentScale.Fit,
                    maximumDimensionPx = 1024,
                    rotationDegrees = rotationDegrees,
                    effects = effects
                )
            }
        }

        LocalizedText(
            if (frameCount == 0) "Frame 0 of 0" else "Frame ${selectedIndex + 1} of $frameCount   ·   ×${selectedFrame?.duration ?: 1}",
            color = MaterialTheme.colorScheme.onBackground,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        if (frameCount > 0) {
            val movieSeconds = movieDurationSeconds(frames, effects, fps)
            LocalizedText("Movie length: ${String.format(Locale.US, "%.1f", movieSeconds)} seconds", color = MaterialTheme.colorScheme.onBackground.copy(alpha = .75f), fontSize = 13.sp, modifier = Modifier.padding(bottom = 4.dp))
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
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) { LocalizedText("‹ Previous", maxLines = 1) }

            Button(
                onClick = {
                    if (!isPlaying && effects.timeline == TimelineEffectType.REVERSE) selectedId = frames.lastOrNull()?.id
                    isPlaying = !isPlaying
                },
                modifier = Modifier.weight(1f),
                enabled = frameCount > 1,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) { LocalizedText(if (isPlaying) "Pause Ⅱ" else "Play ▶", maxLines = 1, fontWeight = FontWeight.Bold) }

            Button(
                onClick = {
                    isPlaying = false
                    selectedId = frames.getOrNull(selectedIndex + 1)?.id
                },
                modifier = Modifier.weight(1f),
                enabled = frameCount > 0 && selectedIndex < frameCount - 1,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) { LocalizedText("Next ›", maxLines = 1) }
        }

        if (settingsMessage != null) {
            LocalizedText(settingsMessage, color = MaterialTheme.colorScheme.error, fontSize = 11.sp, maxLines = 1, modifier = Modifier.padding(bottom = 2.dp))
        }

        if (frameCount == 0) {
            Box(Modifier.fillMaxWidth().height(112.dp), contentAlignment = Alignment.Center) {
                LocalizedText("Your photos will appear here", color = Color.White.copy(alpha = .65f), fontSize = 14.sp)
            }
        } else {
            LazyRow(
                state = listState,
                modifier = Modifier.fillMaxWidth().height(when (timelineZoomLevel.coerceIn(0, 2)) { 0 -> 86.dp; 2 -> 142.dp; else -> 112.dp })
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
                        rotationDegrees = rotationDegrees,
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
            LocalizedText(if (selectionMode) "${selectedIds.size} selected" else "Timeline", color = MaterialTheme.colorScheme.onBackground, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { onSaveTimelineZoom((timelineZoomLevel - 1).coerceAtLeast(0)) }, enabled = timelineZoomLevel > 0) { LocalizedText("− Zoom") }
                LocalizedText("${timelineZoomLevel + 1}/3", color = MaterialTheme.colorScheme.onBackground.copy(alpha = .8f), fontSize = 12.sp)
                TextButton(onClick = { onSaveTimelineZoom((timelineZoomLevel + 1).coerceAtMost(2)) }, enabled = timelineZoomLevel < 2) { LocalizedText("+ Zoom") }
                Box {
                    TextButton(onClick = { showMoreMenu = true }) { LocalizedText("More / 更多") }
                    DropdownMenu(expanded = showMoreMenu, onDismissRequest = { showMoreMenu = false }) {
                        DropdownMenuItem(text = { LocalizedText("Editor tools / 編輯工具") }, onClick = { showMoreMenu = false; showEditorTools = true })
                        DropdownMenuItem(text = { LocalizedText("Export Movie / 匯出影片") }, enabled = frameCount > 0, onClick = { showMoreMenu = false; showExportConfirmation = true })
                    }
                }
            }
        }

        if (selectionMode) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                LocalizedText("Select", color = MaterialTheme.colorScheme.onBackground, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton(onClick = { rangeSelectionMode = !rangeSelectionMode }) { LocalizedText(if (rangeSelectionMode) "Scroll" else "Range") }
                TextButton(onClick = { selectionMode = false; rangeSelectionMode = false; selectedIds.clear() }) { LocalizedText("Done") }
                Button(onClick = { requestDelete(selectedIds.toSet()) }, enabled = selectedIds.isNotEmpty(), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 5.dp), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { LocalizedText("Delete") }
            }
        }

    if (selectedFrame != null) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(onClick = { onDuplicateFrame(selectedFrame.id) { duplicateId -> if (duplicateId != null) selectedId = duplicateId } }, modifier = Modifier.weight(1f), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp, vertical = 8.dp)) { LocalizedText("Copy", maxLines = 1) }
            Button(onClick = { isPlaying = false; requestDelete(setOf(selectedFrame.id)) }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp, vertical = 8.dp)) { LocalizedText("Delete", maxLines = 1) }
        }
    }

    if (!confirmFrameDeletion) {
        TextButton(onClick = { onSaveDeleteConfirmation(true) }, modifier = Modifier.align(Alignment.Start)) { LocalizedText("Turn delete confirmation on") }
    }
    if (showEditorTools) AlertDialog(
        onDismissRequest = { showEditorTools = false },
        title = { LocalizedText("More tools / 更多工具") },
        text = {
            Column(modifier = Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LocalizedText("Speed · $fps FPS", fontWeight = FontWeight.Bold)
                Slider(value = fps.coerceIn(1,24).toFloat(), onValueChange = { onFpsChange(it.roundToInt().coerceIn(1,24)) }, onValueChangeFinished = { onSaveFps(fps.coerceIn(1,24)) }, valueRange = 1f..24f, steps = 22)
                LocalizedText("Rotate movie · ${rotationDegrees}°", fontWeight = FontWeight.Bold)
                Button(onClick = { onRotateMovie((rotationDegrees + 270) % 360) }, modifier = Modifier.fillMaxWidth()) { LocalizedText("Rotate Anticlockwise 90° / 逆時針旋轉90度") }
                if (selectedFrame != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LocalizedText("Move frame", modifier = Modifier.weight(1f))
                        TextButton(onClick = { onMoveFrame(selectedFrame.id, -1) }, enabled = selectedIndex > 0) { LocalizedText("←") }
                        TextButton(onClick = { onMoveFrame(selectedFrame.id, 1) }, enabled = selectedIndex < frameCount - 1) { LocalizedText("→") }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LocalizedText("Selected hold · $holdDurationLabel (${holdTargetIds.size})", modifier = Modifier.weight(1f))
                        TextButton(onClick = { onAdjustFrameDurations(holdTargetIds, -1) }, enabled = holdTargetFrames.any { it.duration > 1 }) { LocalizedText("−") }
                        TextButton(onClick = { onAdjustFrameDurations(holdTargetIds, 1) }, enabled = holdTargetFrames.any { it.duration < 10 }) { LocalizedText("+") }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LocalizedText("All holds · ×$allDuration", modifier = Modifier.weight(1f))
                    TextButton(onClick = { allDuration = (allDuration - 1).coerceAtLeast(1) }, enabled = allDuration > 1) { LocalizedText("−") }
                    TextButton(onClick = { allDuration = (allDuration + 1).coerceAtMost(10) }, enabled = allDuration < 10) { LocalizedText("+") }
                    TextButton(onClick = { showEditorTools = false; confirmApplyAll = true }, enabled = frameCount > 0) { LocalizedText("Apply") }
                }
                if (frameCount > 1) TextButton(onClick = { showEditorTools = false; splitCutIndex = (selectedIndex + 1).coerceIn(1, frameCount - 1); showSplitDialog = true }) { LocalizedText("Split Movie / 分割影片") }
                TextButton(onClick = { showEditorTools = false; showExportConfirmation = true }, enabled = frameCount > 0) { LocalizedText("Export Movie / 匯出影片") }
            }
        },
        confirmButton = { TextButton(onClick = { showEditorTools = false }) { LocalizedText("Done") } }
    )
    }

    val framesToDelete = frames.filter { it.id in pendingDeleteIds.orEmpty() }
    if (confirmApplyAll && frameCount > 0) {
        AlertDialog(
            onDismissRequest = { confirmApplyAll = false },
            title = { LocalizedText("Change every frame?") },
            text = { LocalizedText("Set all $frameCount photographs to ×$allDuration duration?") },
            confirmButton = { TextButton(onClick = { confirmApplyAll = false; onSetAllDurations(allDuration) }) { LocalizedText("Apply") } },
            dismissButton = { TextButton(onClick = { confirmApplyAll = false }) { LocalizedText("Cancel") } }
        )
    }
    if (pendingDeleteIds != null && framesToDelete.isNotEmpty() && confirmFrameDeletion) {
        AlertDialog(
            onDismissRequest = { pendingDeleteIds = null; doNotAskAgain = false },
            title = { LocalizedText(if (framesToDelete.size == 1) "Delete this frame?" else "Delete ${framesToDelete.size} frames?") },
            text = {
                Column {
                    LocalizedText(if (framesToDelete.size == 1) "This photo will be removed from your animation." else "These photos will be removed from your animation.")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = doNotAskAgain, onCheckedChange = { doNotAskAgain = it })
                        LocalizedText("Do not ask again / 不再詢問")
                    }
                }
            },
            confirmButton = { TextButton(onClick = { val ids = pendingDeleteIds.orEmpty(); pendingDeleteIds = null; if (doNotAskAgain) onSaveDeleteConfirmation(false); doNotAskAgain = false; applyDeletion(ids) }) { LocalizedText("Delete") } },
            dismissButton = { TextButton(onClick = { pendingDeleteIds = null; doNotAskAgain = false }) { LocalizedText("Keep") } }
        )
    }
    if (pendingDeleteIds != null && framesToDelete.isNotEmpty() && !confirmFrameDeletion) {
        LaunchedEffect(pendingDeleteIds) { val ids = pendingDeleteIds.orEmpty(); pendingDeleteIds = null; applyDeletion(ids) }
    }

    if (showSplitDialog && frameCount > 1) {
        AlertDialog(
            onDismissRequest = { showSplitDialog = false },
            title = { LocalizedText("Split this movie / 分割影片") },
            text = {
                Column {
                    LocalizedText("Part A uses frames 1–$splitCutIndex. Part B starts at frame ${splitCutIndex + 1}. The original movie stays unchanged.")
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(splitCutIndex - 1, splitCutIndex).forEachIndexed { side, frameIndex ->
                            val cutFrame = frames.getOrNull(frameIndex)
                            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                                LocalizedText(if (side == 0) "Last in Part A" else "First in Part B", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                if (cutFrame != null) FramePhoto(
                                    file = cutFrame.file,
                                    contentDescription = uiText("Frame ${frameIndex + 1} at the split point"),
                                    modifier = Modifier.fillMaxWidth().height(72.dp).padding(top = 4.dp).clip(RoundedCornerShape(8.dp)).background(Color.Black),
                                    contentScale = ContentScale.Fit,
                                    maximumDimensionPx = 240
                                )
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { splitCutIndex = (splitCutIndex - 1).coerceAtLeast(1) }, enabled = splitCutIndex > 1) { LocalizedText("‹ Cut") }
                        LocalizedText("${splitCutIndex} | ${splitCutIndex + 1}", fontWeight = FontWeight.Bold)
                        TextButton(onClick = { splitCutIndex = (splitCutIndex + 1).coerceAtMost(frameCount - 1) }, enabled = splitCutIndex < frameCount - 1) { LocalizedText("Cut ›") }
                    }
                    OutlinedTextField(value = splitPartAName, onValueChange = { splitPartAName = it.take(60) }, label = { LocalizedText("Part A name") }, singleLine = true)
                    OutlinedTextField(value = splitPartBName, onValueChange = { splitPartBName = it.take(60) }, label = { LocalizedText("Part B name") }, singleLine = true)
                }
            },
            confirmButton = { TextButton(onClick = { showSplitDialog = false; onSplitMovie(splitCutIndex, splitPartAName, splitPartBName) }, enabled = splitPartAName.isNotBlank() && splitPartBName.isNotBlank()) { LocalizedText("Create both movies") } },
            dismissButton = { TextButton(onClick = { showSplitDialog = false }) { LocalizedText("Cancel") } }
        )
    }

    if (showExportConfirmation) {
        ExportConfirmationDialog(
            frames = frames,
            fps = fps,
            rotationDegrees = rotationDegrees,
            effects = effects,
            onCancel = { showExportConfirmation = false; onExportAbandoned() },
            onExport = { configuration ->
                showExportConfirmation = false
                exportState = MovieExportState.Running(0)
                exportJob = exportScope.launch {
                    try {
                        val movie = withContext(Dispatchers.IO) {
                            Mp4Exporter.export(context, frames, configuration, projectId, rotationDegrees, effects) { percent ->
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
            title = { LocalizedText("Making your movie…") },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    LocalizedText("${state.percent}% complete", modifier = Modifier.padding(top = 12.dp))
                }
            },
            confirmButton = { TextButton(onClick = { exportJob?.cancel() }) { LocalizedText("Cancel") } }
        )
        is MovieExportState.Complete -> AlertDialog(
            onDismissRequest = { exportState = MovieExportState.Idle },
            title = { LocalizedText("Movie saved!") },
            text = { LocalizedText("${state.movie.fileName}\nSaved in Movies/AnimationWorkshop") },
            confirmButton = {
                TextButton(onClick = {
                    runCatching {
                        val intent = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(state.movie.uri, "video/mp4")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(intent, "Open movie with"))
                    }.onFailure { exportState = MovieExportState.Failed("No video player could open this movie.") }
                }) { LocalizedText("Open Movie") }
            },
            dismissButton = { TextButton(onClick = { exportState = MovieExportState.Idle }) { LocalizedText("Done") } }
        )
        is MovieExportState.Failed -> AlertDialog(
            onDismissRequest = { exportState = MovieExportState.Idle },
            title = { LocalizedText("Export could not finish") },
            text = { LocalizedText(state.message) },
            confirmButton = { TextButton(onClick = { exportState = MovieExportState.Idle; onExportAbandoned() }) { LocalizedText("OK") } }
        )
    }
}

@Composable
private fun ExportConfirmationDialog(
    frames: List<AnimationFrame>,
    fps: Int,
    rotationDegrees: Int,
    effects: MovieEffects,
    onCancel: () -> Unit,
    onExport: (ExportConfiguration) -> Unit
) {
    val result by produceState<Result<ExportConfiguration>?>(null, frames, fps, rotationDegrees) {
        value = withContext(Dispatchers.IO) { runCatching { Mp4Exporter.inspect(frames, fps, rotationDegrees) } }
    }
    val seconds = movieDurationSeconds(frames, effects, fps)
    AlertDialog(
        onDismissRequest = onCancel,
        title = { LocalizedText("Export Movie") },
        text = {
            Column {
                LocalizedText("Estimated duration: ${String.format(Locale.US, "%.2f", seconds)} seconds")
                val configuration = result?.getOrNull()
                if (configuration != null) {
                    LocalizedText("Resolution: ${configuration.width} × ${configuration.height}", modifier = Modifier.padding(top = 6.dp))
                    LocalizedText("Animation speed: ${configuration.projectFps} FPS", modifier = Modifier.padding(top = 4.dp))
                    if (configuration.encoderFps != configuration.projectFps) {
                        LocalizedText(
                            "Video stream: ${configuration.encoderFps} FPS. Frame timing is distributed to preserve the animation speed.",
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                } else if (result == null) {
                    LocalizedText("Checking video encoder…", modifier = Modifier.padding(top = 8.dp))
                } else {
                    LocalizedText(result?.exceptionOrNull()?.message ?: "No compatible video encoder is available.", modifier = Modifier.padding(top = 8.dp))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { result?.getOrNull()?.let(onExport) }, enabled = result?.isSuccess == true) { LocalizedText("Export") }
        },
        dismissButton = { TextButton(onClick = onCancel) { LocalizedText("Cancel") } }
    )
}

@Composable
private fun FrameThumbnail(
    frame: AnimationFrame,
    frameNumber: Int,
    selected: Boolean,
    multiSelected: Boolean,
    width: androidx.compose.ui.unit.Dp,
    rotationDegrees: Int,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Column(
        modifier = Modifier.width(width).height(if (width < 80.dp) 86.dp else if (width > 100.dp) 142.dp else 112.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (multiSelected) Color(0xFFFFB64D) else if (selected) MaterialTheme.colorScheme.primary else Color(0xFF2B3A36))
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
            contentDescription = uiText("Frame $frameNumber thumbnail"),
            modifier = Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(7.dp)).background(Color.Black),
            contentScale = ContentScale.Fit,
            maximumDimensionPx = 480,
            rotationDegrees = rotationDegrees
        )
        if (multiSelected) {
            LocalizedText("✓", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.align(Alignment.End).padding(5.dp))
        }
        LocalizedText(
            "$frameNumber   ×${frame.duration}",
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            modifier = Modifier.padding(top = 2.dp)
        )
    }
}

@Composable
internal fun FramePhoto(
    file: File,
    contentDescription: String,
    modifier: Modifier,
    contentScale: ContentScale,
    maximumDimensionPx: Int,
    rotationDegrees: Int = 0,
    effects: MovieEffects = MovieEffects()
) {
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.roundToPx() }.coerceAtLeast(1).coerceAtMost(maximumDimensionPx)
        val heightPx = with(density) { maxHeight.roundToPx() }.coerceAtLeast(1).coerceAtMost(maximumDimensionPx)
        val decoded by produceState<Triple<Boolean, Bitmap?, Boolean>>(Triple(false, null, false), file.absolutePath, widthPx, heightPx, rotationDegrees, effects) {
            val result = withContext(Dispatchers.IO) {
                val source = decodeSampledFrame(file, widthPx, heightPx, rotationDegrees)
                if (source == null) null to false
                else {
                    val effected = renderImageEffect(source, effects)
                    effected to (effected !== source)
                }
            }
            value = Triple(true, result.first, result.second)
        }
        val bitmap = decoded.second
        val ownsBitmap = decoded.third
        DisposableEffect(bitmap, ownsBitmap) {
            onDispose { if (ownsBitmap) bitmap?.takeIf { !it.isRecycled }?.recycle() }
        }
        if (bitmap != null) {
            Image(
                bitmap = bitmap!!.asImageBitmap(),
                contentDescription = contentDescription,
                modifier = Modifier.fillMaxSize(),
                contentScale = contentScale
            )
        } else {
            LocalizedText(if (decoded.first) "Photo unavailable" else "Loading…", color = Color.White.copy(alpha = .65f), fontSize = 12.sp)
        }
    }
}

internal fun decodeSampledFrame(file: File, targetWidth: Int, targetHeight: Int, rotationDegrees: Int = 0, cacheResult: Boolean = true): Bitmap? {
    if (!file.isFile) return null
    val cacheKey = "${file.absolutePath}:$targetWidth:$targetHeight:${rotationDegrees.mod(360)}"
    if (cacheResult) synchronized(FrameBitmapCache) { FrameBitmapCache.get(cacheKey)?.let { return it } }
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
        else -> Unit
    }
    val corrected = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, transform, true).also { correctedBitmap ->
        if (correctedBitmap !== decoded) decoded.recycle()
    }
    val movieRotation = ((rotationDegrees % 360) + 360) % 360
    val displayed = if (movieRotation == 0) corrected else {
        val movieTransform = android.graphics.Matrix().apply { postRotate(movieRotation.toFloat()) }
        Bitmap.createBitmap(corrected, 0, 0, corrected.width, corrected.height, movieTransform, true).also { rotated ->
            if (rotated !== corrected) corrected.recycle()
        }
    }
    if (cacheResult) synchronized(FrameBitmapCache) { FrameBitmapCache.put(cacheKey, displayed) }
    return displayed
}
