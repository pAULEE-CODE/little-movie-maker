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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

private val GalleryInk = androidx.compose.ui.graphics.Color(0xFF23413C)
private val GalleryTeal = androidx.compose.ui.graphics.Color(0xFF31A996)
private val GalleryCoral = androidx.compose.ui.graphics.Color(0xFFFF5B59)
private val GalleryCream = androidx.compose.ui.graphics.Color(0xFFFFFDF5)

internal data class ProjectGalleryItem(val project: AnimationProject, val frameCount: Int, val thumbnailFile: java.io.File?)

@Composable
internal fun ProjectGalleryScreen(
    projects: List<ProjectGalleryItem>,
    activeId: String?,
    busy: Boolean,
    message: String?,
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
        if (busy) CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally).padding(10.dp), color = GalleryTeal)
        message?.let { Text(it, color = GalleryCoral, modifier = Modifier.padding(top = 8.dp)) }
        if (projects.isEmpty() && !busy) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Your movie shelf is ready for a story!\n準備好開始創作了！", color = GalleryInk, fontSize = 19.sp, fontWeight = FontWeight.Bold) }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(projects, key = { it.project.id }) { item ->
                    ProjectCard(
                        item = item,
                        selected = activeId == item.project.id,
                        enabled = !busy,
                        onOpen = { onOpen(item.project) },
                        onRename = { dialog = GalleryDialog.Rename(item.project) },
                        onDuplicate = { dialog = GalleryDialog.Duplicate(item.project) },
                        onDelete = { dialog = GalleryDialog.Delete(item.project) }
                    )
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
private fun ProjectCard(item: ProjectGalleryItem, selected: Boolean, enabled: Boolean, onOpen: () -> Unit, onRename: () -> Unit, onDuplicate: () -> Unit, onDelete: () -> Unit) {
    val context = LocalContext.current
    val thumb by produceState<android.graphics.Bitmap?>(initialValue = null, item.thumbnailFile?.absolutePath) {
        value = item.thumbnailFile?.let { file -> withContext(Dispatchers.IO) { decodeSampledFrame(file, 240, 180) } }
    }
    Card(
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onOpen),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = if (selected) androidx.compose.ui.graphics.Color(0xFFE0F4ED) else androidx.compose.ui.graphics.Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
    ) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(98.dp, 76.dp).clip(RoundedCornerShape(12.dp)).background(androidx.compose.ui.graphics.Color(0xFFE8F2F4)), contentAlignment = Alignment.Center) {
                if (thumb != null) Image(thumb!!.asImageBitmap(), contentDescription = "${item.project.name} thumbnail", modifier = Modifier.fillMaxSize())
                else Text("🎬", fontSize = 35.sp)
            }
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(item.project.name, color = GalleryInk, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, maxLines = 2)
                Text("${item.frameCount} frames  ·  ${DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(item.project.modifiedAt))}", color = GalleryInk, fontSize = 13.sp, modifier = Modifier.padding(top = 3.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    TextButton(onClick = onRename, enabled = enabled) { Text("Rename") }
                    TextButton(onClick = onDuplicate, enabled = enabled) { Text("Copy") }
                    TextButton(onClick = onDelete, enabled = enabled) { Text("Delete", color = GalleryCoral) }
                }
            }
            Text("›", color = GalleryTeal, fontSize = 30.sp, fontWeight = FontWeight.Bold)
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

