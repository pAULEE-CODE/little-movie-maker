package com.animationworkshop.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.Slider
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
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
import java.io.File
import java.util.Date
import android.graphics.BitmapFactory
import android.media.ExifInterface
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit


internal data class ProjectGalleryItem(
    val project: AnimationProject,
    val frameCount: Int,
    val thumbnailFile: java.io.File?,
    val fps: Int = 8,
    val previewFrames: List<AnimationFrame> = emptyList(),
    val rotationDegrees: Int = 0
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
    automaticPreview: Boolean = true,
    onViewModeChange: (ProjectGalleryView) -> Unit,
    onOpen: (AnimationProject) -> Unit,
    onCreate: (String) -> Unit,
    onRename: (AnimationProject, String) -> Unit,
    onDuplicate: (AnimationProject, String) -> Unit,
    onDelete: (AnimationProject) -> Unit,
    onBack: () -> Unit,
    allFolders: List<AnimationFolder> = emptyList(),
    folderProjectCounts: Map<String, Int> = emptyMap(),
    currentFolderId: String? = null,
    onOpenFolder: (AnimationFolder) -> Unit = {},
    onOpenParentFolder: () -> Unit = {},
    onOpenModes: () -> Unit = {},
    onCreateFolder: (String, String?) -> Unit = { _, _ -> },
    onRenameFolder: (AnimationFolder, String) -> Unit = { _, _ -> },
    onMoveFolder: (AnimationFolder, String?) -> Unit = { _, _ -> },
    onDeleteFolder: (AnimationFolder, Boolean) -> Unit = { _, _ -> },
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
    var folderPendingDelete by remember { mutableStateOf<AnimationFolder?>(null) }
    var folderPendingMove by remember { mutableStateOf<AnimationFolder?>(null) }
    var folderMoveDestination by remember { mutableStateOf<String?>(null) }
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
    val childFolders = allFolders.filter { it.parentId == currentFolderId }
    var previewProjectId by remember(projects, activeId) { mutableStateOf(activeId ?: projects.firstOrNull()?.project?.id) }

    LaunchedEffect(projects, galleryListState) {
        snapshotFlow {
            galleryListState.layoutInfo.visibleItemsInfo
                .firstOrNull { it.key.toString().startsWith("movie:") }
                ?.key?.toString()?.removePrefix("movie:")
        }.collectLatest { visibleId ->
            if (visibleId != null) previewProjectId = visibleId
            else if (projects.none { it.project.id == previewProjectId }) previewProjectId = activeId ?: projects.firstOrNull()?.project?.id
        }
    }

    Column(
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)
            .background(MaterialTheme.colorScheme.background).padding(horizontal = 16.dp)
    ) {
        val previewItem = projects.firstOrNull { it.project.id == previewProjectId }
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            if (previewItem != null) GalleryMotionPreview(previewItem, animate = automaticPreview)
            else Card(Modifier.fillMaxWidth().height(168.dp), shape = RoundedCornerShape(18.dp)) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LocalizedText("Your movie shelf is ready for a story!\n準備好開始創作了！", textAlign = androidx.compose.ui.text.style.TextAlign.Center) }
            }
        }

        LazyColumn(
            state = galleryListState,
            modifier = Modifier.fillMaxWidth().weight(1f),
            verticalArrangement = Arrangement.spacedBy(if (viewMode == ProjectGalleryView.LIST) 4.dp else 10.dp)
        ) {
            item(key = "gallery-controls") {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = onBack) { LocalizedText("← Home") }
                            TextButton(onClick = onOpenModes) { LocalizedText("MODES") }
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            LocalizedText("My Little Movies", fontSize = 20.sp, color = MaterialTheme.colorScheme.onBackground, fontWeight = FontWeight.ExtraBold)
                            LocalizedText("我的小電影", fontSize = 14.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.size(68.dp))
                    }
                    Button(
                        onClick = { dialog = GalleryDialog.Create }, enabled = !busy,
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        shape = RoundedCornerShape(18.dp)
                    ) { LocalizedText("＋  New Movie / 新增電影", fontSize = 19.sp, fontWeight = FontWeight.Bold) }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        LocalizedText("Location: $folderLocation", color = MaterialTheme.colorScheme.onBackground, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                        Row {
                            if (currentFolder != null) TextButton(onClick = onOpenParentFolder) { LocalizedText("↑ Parent") }
                            TextButton(onClick = { folderDialog = FolderDialog.Create }) { LocalizedText("＋ Folder") }
                        }
                    }
                    if (childFolders.isNotEmpty()) LazyRow(
                        Modifier.fillMaxWidth().height(48.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically
                    ) {
                        items(childFolders, key = { "folder-${it.id}" }) { folder ->
                            FolderChip(folder, onOpen = { onOpenFolder(folder) }, onRename = { folderDialog = FolderDialog.Rename(folder) },
                                onMove = { folderPendingMove = folder; folderMoveDestination = folder.parentId }, onDelete = { folderPendingDelete = folder })
                        }
                    }
                    if (selectionMode) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        LocalizedText("${selectedProjectIds.size} selected", color = MaterialTheme.colorScheme.onBackground, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        TextButton(onClick = { selectionMode = false; selectedProjectIds.clear() }) { LocalizedText("Done") }
                        TextButton(onClick = { showMoveFolder = true }) { LocalizedText("Move") }
                        TextButton(onClick = { mergeOrder = selectedProjects.map { it.project.id }; mergeFps = selectedProjects.firstOrNull()?.fps ?: 8; dialog = GalleryDialog.Merge }, enabled = selectedProjectIds.size >= 2) { LocalizedText("Merge") }
                        TextButton(onClick = { onBatchExport(selectedProjects.map { it.project }) }, enabled = selectedProjects.isNotEmpty() && !busy) { LocalizedText("Export") }
                        TextButton(onClick = { showBatchDelete = true }, enabled = selectedProjects.isNotEmpty(), colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { LocalizedText("Delete") }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        ProjectGalleryView.entries.forEach { option ->
                            Button(
                                onClick = { onViewModeChange(option) }, modifier = Modifier.weight(1f).height(46.dp),
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 2.dp, vertical = 4.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = if (viewMode == option) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                                    contentColor = if (viewMode == option) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant),
                                shape = RoundedCornerShape(14.dp)
                            ) {
                                LocalizedText(when (option) {
                                    ProjectGalleryView.LARGE -> "Large / 大圖"
                                    ProjectGalleryView.SMALL -> "Small / 小圖"
                                    ProjectGalleryView.LIST -> "List / 清單"
                                }, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                            }
                        }
                    }
                    if (busy) CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally).padding(6.dp), color = MaterialTheme.colorScheme.primary)
                    message?.let { LocalizedText(it, color = MaterialTheme.colorScheme.error) }
                }
            }

            if (projects.isEmpty() && childFolders.isEmpty() && !busy) {
                item(key = "gallery-empty") { Box(Modifier.fillMaxWidth().padding(vertical = 28.dp), contentAlignment = Alignment.Center) { LocalizedText("Your movie shelf is ready for a story!\n準備好開始創作了！", color = MaterialTheme.colorScheme.onBackground, fontSize = 19.sp, fontWeight = FontWeight.Bold, textAlign = androidx.compose.ui.text.style.TextAlign.Center) } }
            }
            if (viewMode == ProjectGalleryView.SMALL) {
                items(projects.chunked(2), key = { row -> "movie:${row.first().project.id}" }) { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        row.forEach { item ->
                            ProjectCard(item, activeId == item.project.id, !busy, viewMode, item.project.id in selectedProjectIds, selectionMode,
                                onOpen = { onOpen(item.project) }, onRename = { dialog = GalleryDialog.Rename(item.project) },
                                onDuplicate = { dialog = GalleryDialog.Duplicate(item.project) }, onDelete = { dialog = GalleryDialog.Delete(item.project) },
                                onToggleSelect = { if (item.project.id in selectedProjectIds) selectedProjectIds.remove(item.project.id) else selectedProjectIds.add(item.project.id) },
                                onLongSelect = { selectionMode = true; selectedProjectIds.add(item.project.id) },
                                onAction = { action -> onProjectAction(item.project, action) }, modifier = Modifier.weight(1f))
                        }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            } else {
                items(projects, key = { "movie:${it.project.id}" }) { item ->
                    ProjectCard(item, activeId == item.project.id, !busy, viewMode, item.project.id in selectedProjectIds, selectionMode,
                        onOpen = { onOpen(item.project) }, onRename = { dialog = GalleryDialog.Rename(item.project) },
                        onDuplicate = { dialog = GalleryDialog.Duplicate(item.project) }, onDelete = { dialog = GalleryDialog.Delete(item.project) },
                        onToggleSelect = { if (item.project.id in selectedProjectIds) selectedProjectIds.remove(item.project.id) else selectedProjectIds.add(item.project.id) },
                        onLongSelect = { selectionMode = true; selectedProjectIds.add(item.project.id) },
                        onAction = { action -> onProjectAction(item.project, action) })
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
            title = { LocalizedText("Delete this movie? / 刪除此電影？") },
            text = { LocalizedText("${activeDialog.project.name}\nIts photos will be removed from this device.") },
            confirmButton = { TextButton(onClick = { onDelete(activeDialog.project); dialog = null }) { LocalizedText("Delete") } },
            dismissButton = { TextButton(onClick = { dialog = null }) { LocalizedText("Cancel") } }
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

    folderPendingDelete?.let { folder ->
        val hasContents = (folderProjectCounts[folder.id] ?: 0) > 0 || allFolders.any { it.parentId == folder.id }
        AlertDialog(
            onDismissRequest = { folderPendingDelete = null },
            title = { LocalizedText("Delete folder · ${folder.name}?") },
            text = { LocalizedText(if (hasContents) "This folder contains movies or folders. They will be moved to the parent folder; no movies will be deleted." else "This empty folder will be removed.") },
            confirmButton = {
                TextButton(onClick = { onDeleteFolder(folder, hasContents); folderPendingDelete = null }) {
                    LocalizedText(if (hasContents) "Move contents and delete" else "Delete folder")
                }
            },
            dismissButton = { TextButton(onClick = { folderPendingDelete = null }) { LocalizedText("Cancel") } }
        )
    }

    folderPendingMove?.let { folder ->
        fun isBelow(candidateId: String, ancestorId: String): Boolean {
            var node = allFolders.firstOrNull { it.id == candidateId }
            val seen = mutableSetOf<String>()
            while (node != null && seen.add(node.id)) {
                if (node.parentId == ancestorId) return true
                node = node.parentId?.let(folderById::get)
            }
            return false
        }
        AlertDialog(
            onDismissRequest = { folderPendingMove = null },
            title = { LocalizedText("Move folder · ${folder.name}") },
            text = {
                Column {
                    TextButton(onClick = { folderMoveDestination = null }) { LocalizedText(if (folderMoveDestination == null) "✓ Root / 根目錄" else "Root / 根目錄") }
                    allFolders.filter { it.id != folder.id && !isBelow(it.id, folder.id) }.forEach { destination ->
                        TextButton(onClick = { folderMoveDestination = destination.id }) {
                            LocalizedText(if (folderMoveDestination == destination.id) "✓ ${destination.name}" else destination.name)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { onMoveFolder(folder, folderMoveDestination); folderPendingMove = null }) { LocalizedText("Move") } },
            dismissButton = { TextButton(onClick = { folderPendingMove = null }) { LocalizedText("Cancel") } }
        )
    }

    if (showMoveFolder) {
        AlertDialog(
            onDismissRequest = { showMoveFolder = false },
            title = { LocalizedText("Move selected movies") },
            text = {
                Column {
                    TextButton(onClick = { folderDialog = FolderDialog.Create }) { LocalizedText("＋ New folder / 新增資料夾") }
                    TextButton(onClick = { moveTargetFolder = null }) { LocalizedText(if (moveTargetFolder == null) "✓ Root folder" else "Root folder") }
                    allFolders.forEach { folder ->
                        TextButton(onClick = { moveTargetFolder = folder.id }) { LocalizedText(if (moveTargetFolder == folder.id) "✓ ${folder.name}" else folder.name) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { onMoveProjects(selectedProjectIds.toSet(), moveTargetFolder); showMoveFolder = false; selectionMode = false; selectedProjectIds.clear() }) { LocalizedText("Move") } },
            dismissButton = { TextButton(onClick = { showMoveFolder = false }) { LocalizedText("Cancel") } }
        )
    }
    if (showBatchDelete) AlertDialog(
        onDismissRequest = { showBatchDelete = false },
        title = { LocalizedText("Delete ${selectedProjects.size} movies?") },
        text = { LocalizedText("The selected movies and their photographs will be removed from this device.") },
        confirmButton = { TextButton(onClick = { onDeleteProjects(selectedProjects.map { it.project }); showBatchDelete = false; selectionMode = false; selectedProjectIds.clear() }) { LocalizedText("Delete") } },
        dismissButton = { TextButton(onClick = { showBatchDelete = false }) { LocalizedText("Cancel") } }
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
    onAction: (ProjectAction) -> Unit,
    modifier: Modifier = Modifier
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
        modifier = modifier.fillMaxWidth().combinedClickable(
            enabled = enabled,
            onClick = { if (selectionMode) onToggleSelect() else if (viewMode == ProjectGalleryView.LIST) actionsExpanded = true else onOpen() },
            onLongClick = onLongSelect
        ),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = when { multiSelected -> MaterialTheme.colorScheme.tertiaryContainer; selected -> MaterialTheme.colorScheme.secondaryContainer; else -> MaterialTheme.colorScheme.surface }),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
    ) {
        when (viewMode) {
            ProjectGalleryView.LARGE -> Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                ProjectThumbnail(item, thumb, Modifier.size(98.dp, 76.dp))
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    ProjectNameAndInfo(item, 18)
                ProjectActions(enabled, onRename, onDuplicate, onDelete)
                Box {
                    TextButton(onClick = { actionsExpanded = true }, enabled = enabled) { LocalizedText("•••", color = MaterialTheme.colorScheme.primary) }
                    DropdownMenu(expanded = actionsExpanded, onDismissRequest = { actionsExpanded = false }) {
                        ProjectAction.entries.forEach { action -> DropdownMenuItem(text = { LocalizedText(action.label) }, onClick = { actionsExpanded = false; dispatchAction(action) }) }
                    }
                }
                }
                LocalizedText("›", color = MaterialTheme.colorScheme.primary, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            }
            ProjectGalleryView.SMALL -> Column(Modifier.fillMaxWidth().padding(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                ProjectThumbnail(item, thumb, Modifier.fillMaxWidth().height(68.dp))
                LocalizedText(item.project.name, color = MaterialTheme.colorScheme.onBackground, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                LocalizedText("${item.frameCount} frames / 張", color = MaterialTheme.colorScheme.onBackground, fontSize = 11.sp, maxLines = 1, modifier = Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onOpen, enabled = enabled, modifier = Modifier.weight(1f).height(44.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 2.dp)) { LocalizedText("Open / 開啟", fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1) }
                    Box {
                        TextButton(onClick = { actionsExpanded = true }, enabled = enabled, modifier = Modifier.size(48.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) { LocalizedText("•••", fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.primary) }
                        DropdownMenu(expanded = actionsExpanded, onDismissRequest = { actionsExpanded = false }) {
                            ProjectAction.entries.forEach { action -> DropdownMenuItem(text = { LocalizedText(action.label) }, onClick = { actionsExpanded = false; dispatchAction(action) }) }
                        }
                    }
                }
            }
            ProjectGalleryView.LIST -> Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (multiSelected) LocalizedText("✓", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(end = 8.dp))
                Column(Modifier.weight(1f)) {
                    LocalizedText(item.project.name, color = MaterialTheme.colorScheme.onBackground, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
                    LocalizedText("${item.frameCount} frames · ${DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(item.project.modifiedAt))}", color = MaterialTheme.colorScheme.onBackground.copy(alpha = .78f), fontSize = 11.sp, maxLines = 1)
                }
                Box {
                    LocalizedText("•••", color = MaterialTheme.colorScheme.primary, modifier = Modifier.clickable { actionsExpanded = true }.padding(10.dp))
                    DropdownMenu(expanded = actionsExpanded, onDismissRequest = { actionsExpanded = false }) {
                    ProjectAction.entries.forEach { action -> DropdownMenuItem(text = { LocalizedText(action.label) }, onClick = { actionsExpanded = false; dispatchAction(action) }) }
                    }
                }
            }
        }
    }
}

@Composable
internal fun GalleryMotionPreview(item: ProjectGalleryItem, animate: Boolean = true) {
    var index by remember(item.project.id) { mutableIntStateOf(0) }
    val frame = item.previewFrames.getOrNull(index.coerceIn(0, (item.previewFrames.size - 1).coerceAtLeast(0)))
    val frameAspectRatio by produceState(16f / 9f, frame?.file?.absolutePath, item.rotationDegrees) {
        value = frame?.let { current ->
            withContext(Dispatchers.IO) { previewFrameAspectRatio(current.file, item.rotationDegrees) }
        } ?: (16f / 9f)
    }
    LaunchedEffect(item.project.id, item.previewFrames, item.fps, item.rotationDegrees) {
        if (item.previewFrames.isEmpty()) return@LaunchedEffect
        if (!animate) {
            index = 0
            return@LaunchedEffect
        }
        index = 0
        while (isActive) {
            val holdMillis = (1000L * (item.previewFrames[index].duration.coerceIn(1, 10)) / item.fps.coerceIn(1, 24)).coerceAtLeast(1L)
            delay(holdMillis)
            index = (index + 1) % item.previewFrames.size
        }
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val maxPanelHeight = (maxHeight * .46f).coerceAtLeast(1.dp)
        val minPanelHeight = minOf(160.dp, maxPanelHeight)
        val panelHeight = (maxWidth / frameAspectRatio.coerceIn(.25f, 4f)).coerceIn(minPanelHeight, maxPanelHeight)
        Card(
            Modifier.fillMaxWidth().height(panelHeight).testTag("gallery-preview-panel"),
            colors = CardDefaults.cardColors(containerColor = androidx.compose.ui.graphics.Color.Black),
            shape = RoundedCornerShape(18.dp)
        ) {
            BoxWithConstraints(
                Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black),
                contentAlignment = Alignment.Center
            ) {
                val density = LocalDensity.current
                val targetWidth = with(density) { maxWidth.roundToPx() }.coerceAtLeast(1)
                val targetHeight = with(density) { maxHeight.roundToPx() }.coerceAtLeast(1)
                val bitmap by produceState<android.graphics.Bitmap?>(null, frame?.file?.absolutePath, targetWidth, targetHeight, item.rotationDegrees) {
                    value = frame?.let { current ->
                        withContext(Dispatchers.IO) {
                            decodeSampledFrame(current.file, targetWidth, targetHeight, item.rotationDegrees)
                        }
                    }
                }
                when {
                    item.frameCount == 0 -> LocalizedText("This movie is ready for its first frame", color = androidx.compose.ui.graphics.Color.White.copy(alpha = .8f))
                    bitmap != null -> Image(
                        bitmap!!.asImageBitmap(),
                        contentDescription = uiText("Animated preview frame ${index + 1} of ${item.frameCount} for ${item.project.name}"),
                        modifier = Modifier.fillMaxSize().testTag("gallery-preview-image"),
                        contentScale = ContentScale.Fit
                    )
                    else -> LocalizedText("Preview photo unavailable", color = androidx.compose.ui.graphics.Color.White.copy(alpha = .7f))
                }

                Row(
                    Modifier.align(Alignment.TopStart).fillMaxWidth().padding(8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top
                ) {
                    PreviewOverlayLabel(item.project.name, Modifier.weight(1f).padding(end = 8.dp), maxLines = 1)
                    PreviewOverlayLabel("${item.fps} FPS", maxLines = 1)
                }
                PreviewOverlayLabel(
                    "Frame ${index + 1} of ${item.frameCount}",
                    Modifier.align(Alignment.BottomEnd).padding(8.dp).testTag("gallery-preview-frame-counter"),
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun PreviewOverlayLabel(text: String, modifier: Modifier = Modifier, maxLines: Int) {
    LocalizedText(
        text,
        color = androidx.compose.ui.graphics.Color.White,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        maxLines = maxLines,
        modifier = modifier
            .clip(RoundedCornerShape(9.dp))
            .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = .58f))
            .padding(horizontal = 8.dp, vertical = 5.dp)
    )
}

internal fun previewFrameAspectRatio(file: File, rotationDegrees: Int = 0): Float? {
    if (!file.isFile) return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val orientation = runCatching {
        ExifInterface(file.absolutePath).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
    val exifSwapsDimensions = orientation == ExifInterface.ORIENTATION_TRANSPOSE ||
        orientation == ExifInterface.ORIENTATION_ROTATE_90 ||
        orientation == ExifInterface.ORIENTATION_TRANSVERSE ||
        orientation == ExifInterface.ORIENTATION_ROTATE_270
    val width = if (exifSwapsDimensions) bounds.outHeight else bounds.outWidth
    val height = if (exifSwapsDimensions) bounds.outWidth else bounds.outHeight
    val movieSwapsDimensions = ((rotationDegrees % 360) + 360) % 180 != 0
    return (if (movieSwapsDimensions) height.toFloat() / width else width.toFloat() / height).takeIf { it.isFinite() && it > 0f }
}

@Composable
private fun ProjectThumbnail(item: ProjectGalleryItem, bitmap: android.graphics.Bitmap?, modifier: Modifier) {
    Box(modifier.clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        if (bitmap != null) Image(bitmap.asImageBitmap(), contentDescription = uiText("${item.project.name} thumbnail / 縮圖"), modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else LocalizedText("🎬", fontSize = 30.sp)
    }
}

@Composable
private fun ProjectNameAndInfo(item: ProjectGalleryItem, nameSize: Int) {
    LocalizedText(item.project.name, color = MaterialTheme.colorScheme.onBackground, fontSize = nameSize.sp, fontWeight = FontWeight.ExtraBold, maxLines = 2)
    LocalizedText("${item.frameCount} frames / 張  ·  ${DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(item.project.modifiedAt))}", color = MaterialTheme.colorScheme.onBackground, fontSize = 12.sp, modifier = Modifier.padding(top = 3.dp), maxLines = 2)
}

@Composable
private fun ProjectActions(enabled: Boolean, onRename: () -> Unit, onDuplicate: () -> Unit, onDelete: () -> Unit, compact: Boolean = false) {
    val primary = MaterialTheme.colorScheme.primary
    val duplicateAccessibilityLabel = uiText("Copy / 複製")
    Row(horizontalArrangement = Arrangement.spacedBy(if (compact) 0.dp else 2.dp), verticalAlignment = Alignment.CenterVertically) {
        if (compact) {
            IconButton(onClick = onRename, enabled = enabled) { Icon(Icons.Filled.Edit, contentDescription = uiText("Rename / 改名"), tint = MaterialTheme.colorScheme.primary) }
            IconButton(
                onClick = onDuplicate,
                enabled = enabled,
                modifier = Modifier.semantics { contentDescription = duplicateAccessibilityLabel; role = androidx.compose.ui.semantics.Role.Button }
            ) {
                androidx.compose.foundation.Canvas(Modifier.size(22.dp)) {
                    val sw = 2.dp.toPx()
                    drawRoundRect(primary, topLeft = androidx.compose.ui.geometry.Offset(size.width * .28f, size.height * .12f), size = androidx.compose.ui.geometry.Size(size.width * .58f, size.height * .62f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(sw), style = androidx.compose.ui.graphics.drawscope.Stroke(sw))
                    drawRoundRect(primary, topLeft = androidx.compose.ui.geometry.Offset(size.width * .12f, size.height * .28f), size = androidx.compose.ui.geometry.Size(size.width * .58f, size.height * .62f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(sw), style = androidx.compose.ui.graphics.drawscope.Stroke(sw))
                }
            }
        } else {
            TextButton(onClick = onRename, enabled = enabled, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 5.dp)) { LocalizedText("Rename / 改名", fontSize = 11.sp, maxLines = 1) }
            TextButton(onClick = onDuplicate, enabled = enabled, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 5.dp)) { LocalizedText("Copy / 複製", fontSize = 11.sp, maxLines = 1) }
        }
        IconButton(onClick = onDelete, enabled = enabled, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Filled.Delete, contentDescription = uiText("Delete movie / 刪除電影"), tint = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun NameDialog(title: String, initial: String, confirm: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember(title, initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { LocalizedText(title) },
        text = { OutlinedTextField(value = name, onValueChange = { name = it.take(60) }, label = { LocalizedText("Movie name / 電影名稱") }, singleLine = true) },
        confirmButton = { TextButton(onClick = { onConfirm(name) }, enabled = name.isNotBlank()) { LocalizedText(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { LocalizedText("Cancel") } }
    )
}

@Composable
private fun FolderChip(folder: AnimationFolder, onOpen: () -> Unit, onRename: () -> Unit, onMove: () -> Unit, onDelete: () -> Unit) {
    var expanded by remember(folder.id) { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
        TextButton(onClick = onOpen, contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 12.dp, end = 4.dp)) { LocalizedText("📁 ${folder.name}", color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1) }
        Box {
            LocalizedText("⋮", color = MaterialTheme.colorScheme.primary, modifier = Modifier.clickable { expanded = true }.padding(horizontal = 10.dp, vertical = 8.dp))
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                DropdownMenuItem(text = { LocalizedText("Open") }, onClick = { expanded = false; onOpen() })
                DropdownMenuItem(text = { LocalizedText("Rename") }, onClick = { expanded = false; onRename() })
                DropdownMenuItem(text = { LocalizedText("Move folder") }, onClick = { expanded = false; onMove() })
                DropdownMenuItem(text = { LocalizedText("Delete folder") }, onClick = { expanded = false; onDelete() })
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
        title = { LocalizedText("Merge movies / 合併電影") },
        text = {
            Column {
                LocalizedText("${ordered.sumOf { it.frameCount }} total frames")
                ordered.forEachIndexed { index, item ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LocalizedText(item.project.name, modifier = Modifier.weight(1f), maxLines = 1)
                        TextButton(onClick = { if (index > 0) { val updated = reordered.toMutableList().also { java.util.Collections.swap(it, index, index - 1) }; reordered = updated; onOrderChange(updated) } }, enabled = index > 0) { LocalizedText("↑") }
                        TextButton(onClick = { if (index < reordered.lastIndex) { val updated = reordered.toMutableList().also { java.util.Collections.swap(it, index, index + 1) }; reordered = updated; onOrderChange(updated) } }, enabled = index < reordered.lastIndex) { LocalizedText("↓") }
                    }
                }
                LocalizedText("Playback speed: $fps FPS", fontWeight = FontWeight.Bold)
                Slider(value = fps.toFloat(), onValueChange = { onFpsChange(it.toInt().coerceIn(1, 24)) }, valueRange = 1f..24f, steps = 22)
                LocalizedText("Save in", fontWeight = FontWeight.Bold)
                TextButton(onClick = { destinationFolder = null }) { LocalizedText(if (destinationFolder == null) "✓ Root" else "Root") }
                allFolders.forEach { folder -> TextButton(onClick = { destinationFolder = folder.id }) { LocalizedText(if (destinationFolder == folder.id) "✓ ${folder.name}" else folder.name) } }
                OutlinedTextField(value = name, onValueChange = { name = it.take(60) }, label = { LocalizedText("New movie name") }, singleLine = true)
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(name, reordered, fps, destinationFolder) }, enabled = name.isNotBlank() && reordered.size >= 2) { LocalizedText("Create merged movie") } },
        dismissButton = { TextButton(onClick = onDismiss) { LocalizedText("Cancel") } }
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
