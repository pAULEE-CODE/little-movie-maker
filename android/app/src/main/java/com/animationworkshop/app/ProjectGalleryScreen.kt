package com.animationworkshop.app

import androidx.compose.foundation.Image
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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

internal data class ProjectGalleryItem(val project: AnimationProject, val frameCount: Int, val thumbnailFile: java.io.File?)
internal enum class ProjectGalleryView { LARGE, SMALL, LIST }

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
    onBack: () -> Unit
) {
    var dialog by remember { mutableStateOf<GalleryDialog?>(null) }
    Column(Modifier.fillMaxSize().background(GalleryCream).padding(horizontal = 18.dp, vertical = 14.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = onBack) { Text("← Home") }
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
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ProjectGalleryView.entries.forEach { option ->
                Button(
                    onClick = { onViewModeChange(option) },
                    modifier = Modifier.weight(1f).height(46.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 2.dp, vertical = 4.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = if (viewMode == option) GalleryTeal else GalleryInk),
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
        if (projects.isEmpty() && !busy) {
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
                        ProjectCard(item, activeId == item.project.id, !busy, viewMode,
                            onOpen = { onOpen(item.project) },
                            onRename = { dialog = GalleryDialog.Rename(item.project) },
                            onDuplicate = { dialog = GalleryDialog.Duplicate(item.project) },
                            onDelete = { dialog = GalleryDialog.Delete(item.project) })
                    }
                }
                ProjectGalleryView.LARGE, ProjectGalleryView.LIST -> LazyColumn(
                    Modifier.weight(1f).fillMaxWidth().padding(top = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(projects, key = { it.project.id }) { item ->
                        ProjectCard(item, activeId == item.project.id, !busy, viewMode,
                            onOpen = { onOpen(item.project) },
                            onRename = { dialog = GalleryDialog.Rename(item.project) },
                            onDuplicate = { dialog = GalleryDialog.Duplicate(item.project) },
                            onDelete = { dialog = GalleryDialog.Delete(item.project) })
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
        null -> Unit
    }
}

@Composable
private fun ProjectCard(item: ProjectGalleryItem, selected: Boolean, enabled: Boolean, viewMode: ProjectGalleryView, onOpen: () -> Unit, onRename: () -> Unit, onDuplicate: () -> Unit, onDelete: () -> Unit) {
    var actionsExpanded by remember(item.project.id, viewMode) { mutableStateOf(false) }
    val thumb by produceState<android.graphics.Bitmap?>(initialValue = null, item.thumbnailFile?.absolutePath) {
        value = item.thumbnailFile?.let { file -> withContext(Dispatchers.IO) { decodeSampledFrame(file, 240, 180) } }
    }
    Card(
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onOpen),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = if (selected) androidx.compose.ui.graphics.Color(0xFFE0F4ED) else androidx.compose.ui.graphics.Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
    ) {
        when (viewMode) {
            ProjectGalleryView.LARGE -> Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                ProjectThumbnail(item, thumb, Modifier.size(98.dp, 76.dp))
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    ProjectNameAndInfo(item, 18)
                    ProjectActions(enabled, onRename, onDuplicate, onDelete)
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
                            DropdownMenuItem(text = { Text("Rename / 改名") }, onClick = { actionsExpanded = false; onRename() })
                            DropdownMenuItem(text = { Text("Copy / 複製") }, onClick = { actionsExpanded = false; onDuplicate() })
                            DropdownMenuItem(text = { Text("Delete / 刪除", color = GalleryCoral) }, onClick = { actionsExpanded = false; onDelete() })
                        }
                    }
                }
            }
            ProjectGalleryView.LIST -> Row(Modifier.fillMaxWidth().padding(9.dp), verticalAlignment = Alignment.CenterVertically) {
                ProjectThumbnail(item, thumb, Modifier.size(66.dp, 60.dp))
                Column(Modifier.weight(1f).padding(start = 9.dp)) {
                    ProjectNameAndInfo(item, 15)
                    ProjectActions(enabled, onRename, onDuplicate, onDelete, compact = true)
                }
                Text("›", color = GalleryTeal, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            }
        }
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

private sealed interface GalleryDialog {
    data object Create : GalleryDialog
    data class Rename(val project: AnimationProject) : GalleryDialog
    data class Duplicate(val project: AnimationProject) : GalleryDialog
    data class Delete(val project: AnimationProject) : GalleryDialog
}
