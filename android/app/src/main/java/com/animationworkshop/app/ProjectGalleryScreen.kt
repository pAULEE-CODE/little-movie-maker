package com.animationworkshop.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Slider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateSetOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit

private val GalleryInk = androidx.compose.ui.graphics.Color(0xFF23413C)
private val GalleryTeal = androidx.compose.ui.graphics.Color(0xFF31A996)
private val GalleryCoral = androidx.compose.ui.graphics.Color(0xFFFF5B59)
private val GalleryCream = androidx.compose.ui.graphics.Color(0xFFFFFDF5)

internal data class ProjectGalleryItem(
    val project: AnimationProject,
    val frameCount: Int,
    val thumbnailFile: java.io.File?,
    val fps: Int = 8,
    val previewFrames: List<AnimationFrame> = emptyList()
)
internal enum class ProjectGalleryView { LARGE, SMALL, LIST }
internal enum class ProjectAction(val label: String) {
    EDIT("Edit / 編輯"), PREVIEW("Preview / 預覽"), RENAME("Rename / 改名"), DUPLICATE("Duplicate / 複製"),
    DELETE("Delete / 刪除"), EXPORT("Export Movie / 匯出影片"), SHARE("Share / 分享")
}

@Composable
internal fun ProjectGalleryScreen(
    projects: List<ProjectGalleryItem>,
    activeId: String?,
    busy: Boolean,
    message: String?,
    viewMode: ProjectGalleryView,
    onViewModeChange: (ProjectGalleryView) -> Unit,
    onOpen: (AnimationProject) -> Unit,
    onCreate: (String) -> Unit,
    onRename: (AnimationProject, String) -> Unit,
    onDuplicate: (AnimationProject, String) -> Unit,
    onDelete: (AnimationProject) -> Unit,
    onBack: () -> Unit,
    allFolders: List<AnimationFolder> = emptyList(),
    currentFolderId: String? = null,
    onOpenFolder: (AnimationFolder) -> Unit = {},
    onOpenParentFolder: () -> Unit = {},
    onOpenModes: () -> Unit = {},
    onCreateFolder: (String, String?) -> Unit = { _, _ -> },
    onRenameFolder: (AnimationFolder, String) -> Unit = { _, _ -> },
    onMoveProjects: (Set<String>, String?) -> Unit = { _, _ -> },
    onMergeProjects: (List<String>, String, Int, String?) -> Unit = { _, _, _, _ -> },
    onBatchExport: (List<AnimationProject>) -> Unit = {},
    onProjectAction: (AnimationProject, ProjectAction) -> Unit = { _, _ -> },
    onDeleteProjects: (List<AnimationProject>) -> Unit = {}
) {
    var dialog by remember { mutableStateOf<GalleryDialog?>(null) }
    var selectionMode by remember(currentFolderId) { mutableStateOf(false) }
    val selectedProjectIds = remember(currentFolderId) { mutableStateSetOf<String>() }
    var folderDialog by remember { mutableStateOf<FolderDialog?>(null) }
    var mergeOrder by remember { mutableStateOf<List<String>>(emptyList()) }
    var mergeFps by remember { mutableIntStateOf(8) }
    var showMoveFolder by remember { mutableStateOf(false) }
    var showBatchDelete by remember { mutableStateOf(false) }
    var moveTargetFolder by remember { mutableStateOf<String?>(currentFolderId) }
    val selectedProjects = projects.filter { it.project.id in selectedProjectIds }
    val folderById = allFolders.associateBy { it.id }
    val currentFolder = currentFolderId?.let(folderById::get)
    val folderLocation = buildList {
        var next = currentFolder
        val visited = mutableSetOf<String>()
        while (next != null && visited.add(next.id)) {
            add(next.name)
            next = next.parentId?.let(folderById::get)
        }
    }.asReversed().joinToString(" / ").ifBlank { "Root" }
    val galleryListState = rememberLazyListState()
    var previewProjectId by remember(projects) { mutableStateOf(projects.firstOrNull()?.project?.id) }

    LaunchedEffect(viewMode, projects, galleryListState) {
        if (viewMode == ProjectGalleryView.LARGE) {
            snapshotFlow { galleryListState.firstVisibleItemIndex }.collectLatest { index ->
                previewProjectId = projects.getOrNull(index)?.project?.id
            }
        } else {
            previewProjectId = null
        }
    }
    Column(Modifier.fillMaxSize().background(GalleryCream).padding(horizontal = 18.dp, vertical = 14.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("← Home") }
                TextButton(onClick = onOpenModes) { Text("MODES") }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("My Little Movies", fontSize = 24.sp, color = GalleryInk, fontWeight = FontWeight.ExtraBold)
                Text("我的小電影", fontSize = 17.sp, color = GalleryTeal, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.size(68.dp))
        }
        Button(
            onClick = { dialog = GalleryDialog.Create },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp).height(58.dp),
            colors = ButtonDefaults.buttonColors(containerColor = GalleryCoral),
            shape = RoundedCornerShape(18.dp)
        ) { Text("＋  New Movie / 新增電影", fontSize = 19.sp, fontWeight = FontWeight.Bold) }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Location: $folderLocation", color = GalleryInk, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            Row {
                if (currentFolder != null) TextButton(onClick = onOpenParentFolder) { Text("↑ Parent") }
                TextButton(onClick = { folderDialog = FolderDialog.Create }) { Text("＋ Folder") }
            }
        }
        val childFolders = allFolders.filter { it.parentId == currentFolderId }
        if (childFolders.isNotEmpty()) {
            LazyRow(Modifier.fillMaxWidth().height(48.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                items(childFolders, key = { "folder-${it.id}" }) { folder ->
                    FolderChip(folder, onOpen = { onOpenFolder(folder) }, onRename = { folderDialog = FolderDialog.Rename(folder) })
                }
            }
        }
        if (selectionMode) {
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("${selectedProjectIds.size} selected", color = GalleryInk, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton(onClick = { selectionMode = false; selectedProjectIds.clear() }) { Text("Done") }
                TextButton(onClick = { showMoveFolder = true }) { Text("Move") }
                TextButton(onClick = {
                    mergeOrder = selectedProjects.map { it.project.id }
                    mergeFps = selectedProjects.firstOrNull()?.fps ?: 8
                    dialog = GalleryDialog.Merge
                }, enabled = selectedProjectIds.size >= 2) { Text("Merge") }
                TextButton(onClick = { onBatchExport(selectedProjects.map { it.project }) }, enabled = selectedProjects.isNotEmpty() && !busy) { Text("Export") }
                TextButton(onClick = { showBatchDelete = true }, enabled = selectedProjects.isNotEmpty(), colors = ButtonDefaults.textButtonColors(contentColor = GalleryCoral)) { Text("Delete") }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ProjectGalleryView.entries.forEach { option ->
                Button(
                    onClick = { onViewModeChange(option) },
                    modifier = Modifier.weight(1f).height(46.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 2.dp, vertical = 4.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (viewMode == option) GalleryTeal else androidx.compose.ui.graphics.Color(0xFFDCEFEA),
                        contentColor = if (viewMode == option) androidx.compose.ui.graphics.Color.White else GalleryInk
                    ),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text(
                        when (option) {
                            ProjectGalleryView.LARGE -> "Large / 大圖"
                            ProjectGalleryView.SMALL -> "Small / 小圖"
                            ProjectGalleryView.LIST -> "List / 清單"
                        },
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                }
            }
        }
        if (busy) CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally).padding(10.dp), color = GalleryTeal)
        message?.let { Text(it, color = GalleryCoral, modifier = Modifier.padding(top = 8.dp)) }
        if (projects.isEmpty() && childFolders.isEmpty() && !busy) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { Text("Your movie shelf is ready for a story!\n準備好開始創作了！", color = GalleryInk, fontSize = 19.sp, fontWeight = FontWeight.Bold) }
        } else {
            when (viewMode) {
                ProjectGalleryView.SMALL -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 150.dp),
                    modifier = Modifier.weight(1f).fillMaxWidth().padding(top = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(projects, key = { it.project.id }) { item ->
                        ProjectCard(item, activeId == item.project.id, !busy, viewMode, item.project.id in selectedProjectIds, selectionMode,
                            onOpen = { onOpen(item.project) },
                            onRename = { dialog = GalleryDialog.Rename(item.project) },
                            onDuplicate = { dialog = GalleryDialog.Duplicate(item.project) },
                            onDelete = { dialog = GalleryDialog.Delete(item.project) },
                            onToggleSelect = { if (item.project.id in selectedProjectIds) selectedProjectIds.remove(item.project.id) else selectedProjectIds.add(item.project.id) },
                            onLongSelect = { selectionMode = true; selectedProjectIds.add(item.project.id) },
                            onAction = { action -> onProjectAction(item.project, action) })
                    }
                }
                ProjectGalleryView.LARGE, ProjectGalleryView.LIST -> {
                    if (viewMode == ProjectGalleryView.LARGE) {
                        projects.firstOrNull { it.project.id == previewProjectId }?.let { GalleryMotionPreview(it) }
                    }
                    LazyColumn(
                        state = galleryListState,
                        modifier = Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(if (viewMode == ProjectGalleryView.LIST) 4.dp else 12.dp)
                    ) {
                        items(projects, key = { it.project.id }) { item ->
                            ProjectCard(item, activeId == item.project.id, !busy, viewMode, item.project.id in selectedProjectIds, selectionMode,
                                onOpen = { onOpen(item.project) },
                                onRename = { dialog = GalleryDialog.Rename(item.project) },
                                onDuplicate = { dialog = GalleryDialog.Duplicate(item.project) },
                                onDelete = { dialog = GalleryDialog.Delete(item.project) },
                                onToggleSelect = { if (item.project.id in selectedProjectIds) selectedProjectIds.remove(item.project.id) else selectedProjectIds.add(item.project.id) },
                                onLongSelect = { selectionMode = true; selectedProjectIds.add(item.project.id) },
                                onAction = { action -> onProjectAction(item.project, action) })
                        }
                    }
                }
            }
        }
    }

    when (val activeDialog = dialog) {
        GalleryDialog.Create -> NameDialog(
            title = "New Movie / 新增電影", initial = "My Little Movie ${projects.size + 1}", confirm = "Create",
            onDismiss = { dialog = null }, onConfirm = { onCreate(it); dialog = null }
        )
        is GalleryDialog.Rename -> NameDialog(
            title = "Rename Movie / 重新命名", initial = activeDialog.project.name, confirm = "Save",
            onDismiss = { dialog = null }, onConfirm = { onRename(activeDialog.project, it); dialog = null }
        )
        is GalleryDialog.Duplicate -> NameDialog(
            title = "Copy Movie / 複製電影", initial = "Copy of ${activeDialog.project.name}", confirm = "Copy",
            onDismiss = { dialog = null }, onConfirm = { onDuplicate(activeDialog.project, it); dialog = null }
        )
        is GalleryDialog.Delete -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("Delete this movie? / 刪除此電影？") },
            text = { Text("${activeDialog.project.name}\nIts photos will be removed from this device.") },
            confirmButton = { TextButton(onClick = { onDelete(activeDialog.project); dialog = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("Cancel") } }
        )
        GalleryDialog.Merge -> MergeDialog(
            projects = projects.filter { item -> item.project.id in mergeOrder },
            order = mergeOrder,
            fps = mergeFps,
            currentFolderId = currentFolderId,
            allFolders = allFolders,
            onOrderChange = { mergeOrder = it },
            onFpsChange = { mergeFps = it },
            onDismiss = { dialog = null },
            onConfirm = { name, orderedIds, fps, folderId -> onMergeProjects(orderedIds, name, fps, folderId); dialog = null }
        )
        null -> Unit
    }

    when (val activeFolderDialog = folderDialog) {
        FolderDialog.Create -> NameDialog("New Folder / 新增資料夾", "New Folder", "Create", { folderDialog = null }) { name -> onCreateFolder(name, currentFolderId); folderDialog = null }
        is FolderDialog.Rename -> NameDialog("Rename Folder / 重新命名", activeFolderDialog.folder.name, "Save", { folderDialog = null }) { name -> onRenameFolder(activeFolderDialog.folder, name); folderDialog = null }
        null -> Unit
    }

    if (showMoveFolder) {
        AlertDialog(
            onDismissRequest = { showMoveFolder = false },
            title = { Text("Move selected movies") },
            text = {
                Column {
                    TextButton(onClick = { moveTargetFolder = null }) { Text(if (moveTargetFolder == null) "✓ Root folder" else "Root folder") }
                    allFolders.forEach { folder ->
                        TextButton(onClick = { moveTargetFolder = folder.id }) { Text(if (moveTargetFolder == folder.id) "✓ ${folder.name}" else folder.name) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { onMoveProjects(selectedProjectIds.toSet(), moveTargetFolder); showMoveFolder = false; selectionMode = false; selectedProjectIds.clear() }) { Text("Move") } },
            dismissButton = { TextButton(onClick = { showMoveFolder = false }) { Text("Cancel") } }
        )
    }
    if (showBatchDelete) AlertDialog(
        onDismissRequest = { showBatchDelete = false },
        title = { Text("Delete ${selectedProjects.size} movies?") },
        text = { Text("The selected movies and their photographs will be removed from this device.") },
        confirmButton = { TextButton(onClick = { onDeleteProjects(selectedProjects.map { it.project }); showBatchDelete = false; selectionMode = false; selectedProjectIds.clear() }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { showBatchDelete = false }) { Text("Cancel") } }
    )
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun ProjectCard(
    item: ProjectGalleryItem,
    selected: Boolean,
    enabled: Boolean,
    viewMode: ProjectGalleryView,
    multiSelected: Boolean,
    selectionMode: Boolean,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    onToggleSelect: () -> Unit,
    onLongSelect: () -> Unit,
    onAction: (ProjectAction) -> Unit
) {
    var actionsExpanded by remember(item.project.id, viewMode) { mutableStateOf(false) }
    val thumb by produceState<android.graphics.Bitmap?>(initialValue = null, item.thumbnailFile?.absolutePath) {
        value = if (viewMode == ProjectGalleryView.LIST) null else item.thumbnailFile?.let { file -> withContext(Dispatchers.IO) { decodeSampledFrame(file, 240, 180) } }
    }
    fun dispatchAction(action: ProjectAction) {
        when (action) {
            ProjectAction.RENAME -> onRename()
            ProjectAction.DUPLICATE -> onDuplicate()
            ProjectAction.DELETE -> onDelete()
            else -> onAction(action)
        }
    }
    Card(
        modifier = Modifier.fillMaxWidth().combinedClickable(
            enabled = enabled,
            onClick = { if (selectionMode) onToggleSelect() else if (viewMode == ProjectGalleryView.LIST) actionsExpanded = true else onOpen() },
            onLongClick = onLongSelect
        ),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = when { multiSelected -> androidx.compose.ui.graphics.Color(0xFFFFEDC8); selected -> androidx.compose.ui.graphics.Color(0xFFE0F4ED); else -> androidx.compose.ui.graphics.Color.White }),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
    ) {
        when (viewMode) {
            ProjectGalleryView.LARGE -> Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                ProjectThumbnail(item, thumb, Modifier.size(98.dp, 76.dp))
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    ProjectNameAndInfo(item, 18)
                ProjectActions(enabled, onRename, onDuplicate, onDelete)
                Box {
                    TextButton(onClick = { actionsExpanded = true }, enabled = enabled) { Text("•••", color = GalleryTeal) }
                    DropdownMenu(expanded = actionsExpanded, onDismissRequest = { actionsExpanded = false }) {
                        ProjectAction.entries.forEach { action -> DropdownMenuItem(text = { Text(action.label) }, onClick = { actionsExpanded = false; dispatchAction(action) }) }
                    }
                }
                }
                Text("›", color = GalleryTeal, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            }
            ProjectGalleryView.SMALL -> Column(Modifier.fillMaxWidth().padding(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                ProjectThumbnail(item, thumb, Modifier.fillMaxWidth().height(68.dp))
                Text(item.project.name, color = GalleryInk, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                Text("${item.frameCount} frames / 張", color = GalleryInk, fontSize = 11.sp, maxLines = 1, modifier = Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onOpen, enabled = enabled, modifier = Modifier.weight(1f).height(44.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 2.dp)) { Text("Open / 開啟", fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1) }
                    Box {
                        TextButton(onClick = { actionsExpanded = true }, enabled = enabled, modifier = Modifier.size(48.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) { Text("•••", fontWeight = FontWeight.ExtraBold, color = GalleryTeal) }
                        DropdownMenu(expanded = actionsExpanded, onDismissRequest = { actionsExpanded = false }) {
                            ProjectAction.entries.forEach { action -> DropdownMenuItem(text = { Text(action.label) }, onClick = { actionsExpanded = false; dispatchAction(action) }) }
                        }
                    }
                }
            }
            ProjectGalleryView.LIST -> Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (multiSelected) Text("✓", color = GalleryTeal, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(end = 8.dp))
                Column(Modifier.weight(1f)) {
                    Text(item.project.name, color = GalleryInk, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
                    Text("${item.frameCount} frames · ${DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(item.project.modifiedAt))}", color = GalleryInk.copy(alpha = .78f), fontSize = 11.sp, maxLines = 1)
                }
                Box {
                    Text("•••", color = GalleryTeal, modifier = Modifier.clickable { actionsExpanded = true }.padding(10.dp))
                    DropdownMenu(expanded = actionsExpanded, onDismissRequest = { actionsExpanded = false }) {
                    ProjectAction.entries.forEach { action -> DropdownMenuItem(text = { Text(action.label) }, onClick = { actionsExpanded = false; dispatchAction(action) }) }
                    }
                }
            }
        }
    }
}

@Composable
private fun GalleryMotionPreview(item: ProjectGalleryItem) {
    var index by remember(item.project.id) { mutableIntStateOf(0) }
    val frame = item.previewFrames.getOrNull(index.coerceIn(0, (item.previewFrames.size - 1).coerceAtLeast(0)))
    val bitmap by produceState<android.graphics.Bitmap?>(initialValue = null, frame?.file?.absolutePath) {
        value = frame?.let { withContext(Dispatchers.IO) { decodeSampledFrame(it.file, 480, 320) } }
    }
    LaunchedEffect(item.project.id, item.previewFrames, item.fps) {
        if (item.previewFrames.isEmpty()) return@LaunchedEffect
        index = 0
        while (isActive) {
            val holdMillis = (1000L * (item.previewFrames[index].duration.coerceIn(1, 10)) / item.fps.coerceIn(1, 24)).coerceAtLeast(1L)
            delay(holdMillis)
            index = (index + 1) % item.previewFrames.size
        }
    }
    Card(
        Modifier.fillMaxWidth().padding(top = 8.dp),
        colors = CardDefaults.cardColors(containerColor = GalleryInk),
        shape = RoundedCornerShape(18.dp)
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Preview / 預覽", color = androidx.compose.ui.graphics.Color.White, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold)
                Text(item.project.name, color = androidx.compose.ui.graphics.Color.White.copy(alpha = .85f), fontSize = 12.sp, maxLines = 1)
            }
            Text("${item.fps} FPS", color = androidx.compose.ui.graphics.Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        Box(Modifier.fillMaxWidth().height(132.dp).background(androidx.compose.ui.graphics.Color.Black), contentAlignment = Alignment.Center) {
            when {
                item.frameCount == 0 -> Text("This movie is ready for its first frame", color = androidx.compose.ui.graphics.Color.White.copy(alpha = .8f))
                bitmap != null -> Image(bitmap!!.asImageBitmap(), contentDescription = "Animated preview of ${item.project.name}", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                else -> Text("Preview photo unavailable", color = androidx.compose.ui.graphics.Color.White.copy(alpha = .7f))
            }
        }
        Text("Frame ${index + 1} of ${item.frameCount}", color = androidx.compose.ui.graphics.Color.White.copy(alpha = .8f), fontSize = 11.sp, modifier = Modifier.align(Alignment.End).padding(horizontal = 12.dp, vertical = 6.dp))
    }
}

@Composable
private fun ProjectThumbnail(item: ProjectGalleryItem, bitmap: android.graphics.Bitmap?, modifier: Modifier) {
    Box(modifier.clip(RoundedCornerShape(12.dp)).background(androidx.compose.ui.graphics.Color(0xFFE8F2F4)), contentAlignment = Alignment.Center) {
        if (bitmap != null) Image(bitmap.asImageBitmap(), contentDescription = "${item.project.name} thumbnail / 縮圖", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Text("🎬", fontSize = 30.sp)
    }
}

@Composable
private fun ProjectNameAndInfo(item: ProjectGalleryItem, nameSize: Int) {
    Text(item.project.name, color = GalleryInk, fontSize = nameSize.sp, fontWeight = FontWeight.ExtraBold, maxLines = 2)
    Text("${item.frameCount} frames / 張  ·  ${DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(item.project.modifiedAt))}", color = GalleryInk, fontSize = 12.sp, modifier = Modifier.padding(top = 3.dp), maxLines = 2)
}

@Composable
private fun ProjectActions(enabled: Boolean, onRename: () -> Unit, onDuplicate: () -> Unit, onDelete: () -> Unit, compact: Boolean = false) {
    Row(horizontalArrangement = Arrangement.spacedBy(if (compact) 0.dp else 2.dp), verticalAlignment = Alignment.CenterVertically) {
        if (compact) {
            IconButton(onClick = onRename, enabled = enabled) { Icon(Icons.Filled.Edit, contentDescription = "Rename / 改名", tint = GalleryTeal) }
            IconButton(
                onClick = onDuplicate,
                enabled = enabled,
                modifier = Modifier.semantics { contentDescription = "Copy / 複製"; role = androidx.compose.ui.semantics.Role.Button }
            ) {
                androidx.compose.foundation.Canvas(Modifier.size(22.dp)) {
                    val sw = 2.dp.toPx()
                    drawRoundRect(GalleryTeal, topLeft = androidx.compose.ui.geometry.Offset(size.width * .28f, size.height * .12f), size = androidx.compose.ui.geometry.Size(size.width * .58f, size.height * .62f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(sw), style = androidx.compose.ui.graphics.drawscope.Stroke(sw))
                    drawRoundRect(GalleryTeal, topLeft = androidx.compose.ui.geometry.Offset(size.width * .12f, size.height * .28f), size = androidx.compose.ui.geometry.Size(size.width * .58f, size.height * .62f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(sw), style = androidx.compose.ui.graphics.drawscope.Stroke(sw))
                }
            }
        } else {
            TextButton(onClick = onRename, enabled = enabled, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 5.dp)) { Text("Rename / 改名", fontSize = 11.sp, maxLines = 1) }
            TextButton(onClick = onDuplicate, enabled = enabled, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 5.dp)) { Text("Copy / 複製", fontSize = 11.sp, maxLines = 1) }
        }
        IconButton(onClick = onDelete, enabled = enabled, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Filled.Delete, contentDescription = "Delete movie / 刪除電影", tint = GalleryCoral)
        }
    }
}

@Composable
private fun NameDialog(title: String, initial: String, confirm: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember(title, initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(value = name, onValueChange = { name = it.take(60) }, label = { Text("Movie name / 電影名稱") }, singleLine = true) },
        confirmButton = { TextButton(onClick = { onConfirm(name) }, enabled = name.isNotBlank()) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun FolderChip(folder: AnimationFolder, onOpen: () -> Unit, onRename: () -> Unit) {
    var expanded by remember(folder.id) { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(androidx.compose.ui.graphics.Color(0xFFE5F3EF))) {
        TextButton(onClick = onOpen, contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 12.dp, end = 4.dp)) { Text("📁 ${folder.name}", color = GalleryInk, maxLines = 1) }
        Box {
            Text("⋮", color = GalleryTeal, modifier = Modifier.clickable { expanded = true }.padding(horizontal = 10.dp, vertical = 8.dp))
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                DropdownMenuItem(text = { Text("Open") }, onClick = { expanded = false; onOpen() })
                DropdownMenuItem(text = { Text("Rename") }, onClick = { expanded = false; onRename() })
            }
        }
    }
}

@Composable
private fun MergeDialog(
    projects: List<ProjectGalleryItem>,
    order: List<String>,
    fps: Int,
    currentFolderId: String?,
    allFolders: List<AnimationFolder>,
    onOrderChange: (List<String>) -> Unit,
    onFpsChange: (Int) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: (String, List<String>, Int, String?) -> Unit
) {
    var name by remember { mutableStateOf("Merged Movie") }
    var destinationFolder by remember(currentFolderId) { mutableStateOf(currentFolderId) }
    val itemsById = projects.associateBy { it.project.id }
    var reordered by remember(order) { mutableStateOf(order) }
    val ordered = reordered.mapNotNull(itemsById::get)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Merge movies / 合併電影") },
        text = {
            Column {
                Text("${ordered.sumOf { it.frameCount }} total frames")
                ordered.forEachIndexed { index, item ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(item.project.name, modifier = Modifier.weight(1f), maxLines = 1)
                        TextButton(onClick = { if (index > 0) { val updated = reordered.toMutableList().also { java.util.Collections.swap(it, index, index - 1) }; reordered = updated; onOrderChange(updated) } }, enabled = index > 0) { Text("↑") }
                        TextButton(onClick = { if (index < reordered.lastIndex) { val updated = reordered.toMutableList().also { java.util.Collections.swap(it, index, index + 1) }; reordered = updated; onOrderChange(updated) } }, enabled = index < reordered.lastIndex) { Text("↓") }
                    }
                }
                Text("Playback speed: $fps FPS", fontWeight = FontWeight.Bold)
                Slider(value = fps.toFloat(), onValueChange = { onFpsChange(it.toInt().coerceIn(1, 24)) }, valueRange = 1f..24f, steps = 22)
                Text("Save in", fontWeight = FontWeight.Bold)
                TextButton(onClick = { destinationFolder = null }) { Text(if (destinationFolder == null) "✓ Root" else "Root") }
                allFolders.forEach { folder -> TextButton(onClick = { destinationFolder = folder.id }) { Text(if (destinationFolder == folder.id) "✓ ${folder.name}" else folder.name) } }
                OutlinedTextField(value = name, onValueChange = { name = it.take(60) }, label = { Text("New movie name") }, singleLine = true)
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(name, reordered, fps, destinationFolder) }, enabled = name.isNotBlank() && reordered.size >= 2) { Text("Create merged movie") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

private sealed interface FolderDialog {
    data object Create : FolderDialog
    data class Rename(val folder: AnimationFolder) : FolderDialog
}

private sealed interface GalleryDialog {
    data object Create : GalleryDialog
    data class Rename(val project: AnimationProject) : GalleryDialog
    data class Duplicate(val project: AnimationProject) : GalleryDialog
    data class Delete(val project: AnimationProject) : GalleryDialog
    data object Merge : GalleryDialog
}
