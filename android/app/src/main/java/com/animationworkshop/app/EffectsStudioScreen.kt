package com.animationworkshop.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@Composable
internal fun EffectsStudioScreen(
    projects: List<ProjectGalleryItem>,
    initialProjectId: String?,
    onBack: () -> Unit,
    onOpenModes: () -> Unit,
    onApply: (String, MovieEffects) -> Unit
) {
    var selectedProjectId by remember { mutableStateOf(initialProjectId) }
    val selectedProject = projects.firstOrNull { it.project.id == selectedProjectId } ?: projects.firstOrNull()
    var draft by remember(selectedProject?.project?.id) { mutableStateOf(selectedProject?.effects ?: MovieEffects()) }
    var projectMenuOpen by remember { mutableStateOf(false) }
    var frameIndex by remember(selectedProject?.project?.id) { mutableIntStateOf(0) }

    val timeline = remember(selectedProject?.previewFrames, draft) {
        buildMovieTimeline(selectedProject?.previewFrames.orEmpty(), draft)
    }
    LaunchedEffect(timeline, selectedProject?.fps, selectedProject?.project?.id) {
        if (timeline.isEmpty()) return@LaunchedEffect
        frameIndex = 0
        while (isActive) {
            val current = timeline[frameIndex.coerceIn(0, timeline.lastIndex)]
            delay((1000L * current.durationTicks / (selectedProject?.fps ?: 8).coerceIn(1, 24)).coerceAtLeast(1L))
            frameIndex = (frameIndex + 1) % timeline.size
        }
    }

    Column(
        Modifier.fillMaxSize().background(Color(0xFFFFF9ED)).windowInsetsPadding(WindowInsets.safeDrawing).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { LocalizedText("‹ Back / 返回") }
            Spacer(Modifier.weight(1f))
            LocalizedText("Effects Studio / 特效工作室", fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.onBackground)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onOpenModes) { LocalizedText("MODES", fontWeight = FontWeight.ExtraBold) }
        }

        Box {
            Button(onClick = { projectMenuOpen = true }, enabled = projects.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
                LocalizedText(selectedProject?.project?.name?.let { "Movie / 電影 · $it" } ?: "Choose a movie / 選擇電影", maxLines = 1)
            }
            DropdownMenu(expanded = projectMenuOpen, onDismissRequest = { projectMenuOpen = false }) {
                projects.forEach { item ->
                    DropdownMenuItem(
                        text = { LocalizedText("${item.project.name} · ${item.frameCount} frames", maxLines = 1) },
                        onClick = { selectedProjectId = item.project.id; draft = item.effects; frameIndex = 0; projectMenuOpen = false }
                    )
                }
            }
        }

        selectedProject?.let { item ->
            Card(
                Modifier.fillMaxWidth().height(220.dp).padding(top = 8.dp),
                colors = CardDefaults.cardColors(containerColor = Color.Black),
                shape = RoundedCornerShape(18.dp)
            ) {
                val current = timeline.getOrNull(frameIndex.coerceIn(0, (timeline.size - 1).coerceAtLeast(0)))?.frame
                if (current == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        LocalizedText("Take a few photos to preview effects / 拍攝幾張照片即可預覽特效", color = Color.White, textAlign = TextAlign.Center)
                    }
                } else {
                    FramePhoto(
                        file = current.file,
                        contentDescription = uiText("Effects preview frame ${frameIndex + 1}"),
                        modifier = Modifier.fillMaxSize().padding(6.dp),
                        contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                        maximumDimensionPx = 1280,
                        rotationDegrees = item.rotationDegrees,
                        effects = draft
                    )
                }
            }
            LocalizedText(
                if (timeline.isEmpty()) "${item.fps} FPS · 0 frames" else "${item.fps} FPS · Frame ${frameIndex + 1} of ${timeline.size}",
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = .75f), fontSize = 12.sp,
                modifier = Modifier.padding(top = 3.dp)
            )

            LocalizedText("Colour & appearance / 色彩與外觀", fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.fillMaxWidth().padding(top = 10.dp))
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ImageEffectType.entries.forEach { effect ->
                    EffectChoice(
                        label = imageEffectLabel(effect), icon = imageEffectIcon(effect), selected = draft.image == effect,
                        active = item.effects.image == effect && effect != ImageEffectType.NONE,
                        onClick = { draft = draft.copy(image = effect, strength = if (effect == item.effects.image) draft.strength else 1f) }
                    )
                }
            }
            if (draft.image in setOf(ImageEffectType.SEPIA, ImageEffectType.VIVID, ImageEffectType.POSTERISE, ImageEffectType.VIGNETTE)) {
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    LocalizedText("Strength / 強度", fontWeight = FontWeight.Bold)
                    Spacer(Modifier.weight(1f))
                    LocalizedText("${(draft.strength * 100).toInt()}%", fontWeight = FontWeight.Bold)
                }
                Slider(value = draft.strength, onValueChange = { draft = draft.copy(strength = (it * 20).toInt() / 20f) }, valueRange = 0f..1f)
            }

            LocalizedText("Playback / 播放方式", fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                TimelineEffectType.entries.chunked(3).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { effect ->
                            EffectChoice(
                                label = timelineEffectLabel(effect), icon = timelineEffectIcon(effect), selected = draft.timeline == effect,
                                active = item.effects.timeline == effect && effect != TimelineEffectType.NORMAL,
                                onClick = { draft = draft.copy(timeline = effect) }, modifier = Modifier.weight(1f)
                            )
                        }
                        repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
            LocalizedText("Freeze Frame / 凍結影格", fontWeight = FontWeight.ExtraBold, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            if (item.previewFrames.isEmpty()) {
                LocalizedText("Capture photos first / 請先拍攝照片", fontSize = 12.sp)
            } else {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    LocalizedText(if (draft.freezeFrameId == null) "Off / 關閉" else "On / 開啟", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Button(
                        onClick = {
                            draft = draft.copy(freezeFrameId = if (draft.freezeFrameId == null) item.previewFrames.firstOrNull()?.id else null)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = if (draft.freezeFrameId == null) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.primary)
                    ) { LocalizedText(if (draft.freezeFrameId == null) "Add Freeze / 加入凍結" else "Remove Freeze / 移除凍結", fontSize = 11.sp) }
                }
                if (draft.freezeFrameId != null) {
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        item.previewFrames.forEachIndexed { index, frame ->
                            val selected = draft.freezeFrameId == frame.id
                            Button(
                                onClick = { draft = draft.copy(freezeFrameId = frame.id) },
                                colors = ButtonDefaults.buttonColors(containerColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant),
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 9.dp, vertical = 5.dp)
                            ) { LocalizedText("${index + 1}", fontSize = 12.sp) }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        LocalizedText("Hold ×${draft.freezeMultiplier}", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        TextButton(onClick = { draft = draft.copy(freezeMultiplier = (draft.freezeMultiplier - 1).coerceAtLeast(2)) }, enabled = draft.freezeMultiplier > 2) { LocalizedText("−") }
                        TextButton(onClick = { draft = draft.copy(freezeMultiplier = (draft.freezeMultiplier + 1).coerceAtMost(10)) }, enabled = draft.freezeMultiplier < 10) { LocalizedText("+") }
                    }
                }
            }
            val activeLabel = buildList {
                if (item.effects.image != ImageEffectType.NONE) add(imageEffectLabel(item.effects.image))
                if (item.effects.timeline != TimelineEffectType.NORMAL) add(timelineEffectLabel(item.effects.timeline))
                if (item.effects.freezeFrameId != null) add("Freeze ×${item.effects.freezeMultiplier}")
            }.joinToString(" · ").ifBlank { "None / 無" }
            LocalizedText("Active: $activeLabel", color = MaterialTheme.colorScheme.primary, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.fillMaxWidth().padding(top = 7.dp))
            Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 18.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { draft = MovieEffects() }, modifier = Modifier.weight(1f)) { LocalizedText("Reset / 重設") }
                Button(onClick = onBack, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurfaceVariant)) { LocalizedText("Cancel / 取消") }
                Button(
                    onClick = { selectedProjectId = item.project.id; onApply(item.project.id, draft.normalized()) },
                    modifier = Modifier.weight(1f), enabled = projects.isNotEmpty()
                ) { LocalizedText("Apply / 套用") }
            }
        } ?: LocalizedText("Create or choose a movie to use Effects Studio. / 建立或選擇電影以使用特效工作室。", modifier = Modifier.padding(24.dp), textAlign = TextAlign.Center)
    }
}

@Composable
private fun EffectChoice(label: String, icon: String, selected: Boolean, active: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier.width(104.dp)) {
    val color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    Column(
        modifier.height(72.dp).clip(RoundedCornerShape(16.dp)).background(color).clickable(onClick = onClick).padding(horizontal = 5.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        LocalizedText(icon, fontSize = 22.sp)
        LocalizedText(label, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 2, textAlign = TextAlign.Center, color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
        if (active) LocalizedText("●", fontSize = 7.sp, color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary)
    }
}

private fun imageEffectIcon(effect: ImageEffectType) = when (effect) {
    ImageEffectType.NONE -> "🎞️"
    ImageEffectType.BLACK_WHITE -> "◐"
    ImageEffectType.SEPIA -> "🟤"
    ImageEffectType.VIVID -> "🌈"
    ImageEffectType.POSTERISE -> "🎨"
    ImageEffectType.VIGNETTE -> "🌑"
}

private fun timelineEffectIcon(effect: TimelineEffectType) = when (effect) {
    TimelineEffectType.NORMAL -> "▶"
    TimelineEffectType.REVERSE -> "◀"
    TimelineEffectType.BOOMERANG -> "↔"
    TimelineEffectType.SLOW_MOTION -> "🐢"
    TimelineEffectType.FAST_MOTION -> "⚡"
}
