package com.animationworkshop.app

import android.content.Context
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

@Composable
internal fun SettingsScreen(
    themeMode: String,
    onThemeModeChange: (String) -> Unit,
    onAutomaticPreviewChange: (Boolean) -> Unit,
    onBack: () -> Unit,
    onOpenModes: () -> Unit,
    onRestoreDeleteWarnings: () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences("animation_workshop", Context.MODE_PRIVATE) }
    val packageInfo = remember(context) { context.packageManager.getPackageInfo(context.packageName, 0) }
    val versionCode = if (Build.VERSION.SDK_INT >= 28) packageInfo.longVersionCode else @Suppress("DEPRECATION") packageInfo.versionCode.toLong()
    var language by remember { mutableStateOf(prefs.getString("language_mode", "BILINGUAL") ?: "BILINGUAL") }
    var defaultFps by remember { mutableIntStateOf(prefs.getInt("default_fps", 8).coerceIn(1, 24)) }
    var defaultZoom by remember { mutableIntStateOf(prefs.getInt("default_timeline_zoom", 1).coerceIn(0, 2)) }
    var autoPreview by remember { mutableStateOf(prefs.getBoolean("automatic_project_preview", true)) }
    var countdownSound by remember { mutableStateOf(prefs.getBoolean("timelapse_countdown_sound", true)) }
    var showRestoreConfirm by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹ Back / 返回") }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onOpenModes) { Text("MODES") }
        }
        Text("Settings / 設定", fontSize = 25.sp, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.onBackground)
        Text("Make the workshop comfortable for you.", color = MaterialTheme.colorScheme.onBackground.copy(alpha = .75f))

        SettingSection("Theme / 主題") {
            ChoiceRow(listOf("LIGHT" to "Light / 淺色", "DARK" to "Dark / 深色", "SYSTEM" to "Follow device / 跟隨系統"), themeMode) {
                onThemeModeChange(it)
                prefs.edit().putString("theme_mode", it).apply()
            }
        }
        SettingSection("Language / 語言") {
            ChoiceRow(listOf("ENGLISH" to "English", "TRADITIONAL_CHINESE" to "繁體中文", "BILINGUAL" to "Bilingual / 雙語"), language) {
                language = it
                prefs.edit().putString("language_mode", it).apply()
            }
            Text("The current interface keeps paired English and Traditional Chinese labels where available.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        }
        SettingSection("New movies / 新電影預設") {
            Text("Default speed · $defaultFps FPS")
            Slider(value = defaultFps.toFloat(), onValueChange = { defaultFps = it.roundToInt().coerceIn(1, 24) }, onValueChangeFinished = { prefs.edit().putInt("default_fps", defaultFps).apply() }, valueRange = 1f..24f, steps = 22)
            Text("Default timeline thumbnail size · ${defaultZoom + 1} of 3")
            ChoiceRow(listOf("0" to "Small", "1" to "Medium", "2" to "Large"), defaultZoom.toString()) {
                defaultZoom = it.toInt()
                prefs.edit().putInt("default_timeline_zoom", defaultZoom).apply()
            }
        }
        SettingSection("Projects / 專案") {
            SettingSwitch("Automatic animated preview / 自動播放專案預覽", autoPreview) {
                autoPreview = it
                prefs.edit().putBoolean("automatic_project_preview", it).apply()
                onAutomaticPreviewChange(it)
            }
        }
        SettingSection("Timelapse / 縮時攝影") {
            SettingSwitch("Countdown sound / 倒數提示音", countdownSound) {
                countdownSound = it
                prefs.edit().putBoolean("timelapse_countdown_sound", it).apply()
            }
        }
        SettingSection("Editor / 編輯器") {
            Text("Delete warnings are saved per movie.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = { showRestoreConfirm = true }) { Text("Restore all delete warnings / 恢復刪除確認") }
        }
        SettingSection("About / 關於") {
            Text("Little Movie Maker · ${packageInfo.versionName.orEmpty()} (build $versionCode)")
            Text("Made for playful stop-motion stories.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(8.dp))
    }

    if (showRestoreConfirm) AlertDialog(
        onDismissRequest = { showRestoreConfirm = false },
        title = { Text("Restore delete warnings?") },
        text = { Text("Deletion confirmation will be turned back on for every movie.") },
        confirmButton = { TextButton(onClick = { showRestoreConfirm = false; onRestoreDeleteWarnings() }) { Text("Restore") } },
        dismissButton = { TextButton(onClick = { showRestoreConfirm = false }) { Text("Cancel") } }
    )
}

@Composable
private fun SettingSection(title: String, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(top = 2.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        Text(title, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        content()
    }
}

@Composable
private fun ChoiceRow(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        options.forEach { (value, label) ->
            Button(
                onClick = { onSelect(value) },
                modifier = Modifier.weight(1f),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp, vertical = 6.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (value == selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = if (value == selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            ) { Text(label, fontSize = 11.sp, maxLines = 2) }
        }
    }
}

@Composable
private fun SettingSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
