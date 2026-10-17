package com.animationworkshop.app

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal enum class TimelapseStopCondition { FRAME_COUNT, RECORDING_DURATION, CLOCK_TIME }

internal data class TimelapseConfig(
    val intervalSeconds: Int,
    val stopCondition: TimelapseStopCondition,
    val frameLimit: Int,
    val durationMinutes: Int,
    val stopAtMillis: Long,
    val countdownSoundEnabled: Boolean,
    val manualExposureEnabled: Boolean = false,
    val exposureTimeNanos: Long = 0L,
    val iso: Int = 100,
    val stackSourceCount: Int = 1
)


@Composable
internal fun TimelapseSetupScreen(onOpenModes: () -> Unit, onBack: () -> Unit, onStart: (TimelapseConfig) -> Unit) {
    val context = LocalContext.current
    val preferences = remember(context) { context.getSharedPreferences("animation_workshop", android.content.Context.MODE_PRIVATE) }
    val countdownSoundEnabled = remember { preferences.getBoolean("timelapse_countdown_sound", true) }
    var interval by remember { mutableIntStateOf(5) }
    var customInterval by remember { mutableStateOf("5") }
    var isCustom by remember { mutableStateOf(false) }
    var condition by remember { mutableStateOf(TimelapseStopCondition.FRAME_COUNT) }
    var framesText by remember { mutableStateOf("30") }
    var hoursText by remember { mutableStateOf("0") }
    var minutesText by remember { mutableStateOf("10") }
    var stopAtMillis by remember { mutableStateOf(System.currentTimeMillis() + 60 * 60 * 1000L) }
    var error by remember { mutableStateOf<String?>(null) }
    val exposureCapabilities = remember(context) { runCatching { ManualExposureCapabilities.detect(context) }.getOrDefault(ManualExposureCapabilities(null, null)) }
    val shutterChoices = exposureCapabilities.shutterChoices
    var showExposure by remember { mutableStateOf(false) }
    var manualExposureEnabled by remember { mutableStateOf(false) }
    var shutterIndex by remember(shutterChoices) { mutableIntStateOf(shutterChoices.indexOfFirst { it >= 30_000_000L }.coerceAtLeast(0)) }
    var iso by remember(exposureCapabilities.isoRange) { mutableIntStateOf(exposureCapabilities.isoRange?.lower?.coerceIn(50, 3200) ?: 100) }
    var stackSourceCount by remember { mutableIntStateOf(1) }
    val zone = ZoneId.systemDefault()
    val stopLabel = remember(stopAtMillis) { DateTimeFormatter.ofPattern("MMM d, yyyy · h:mm a").format(LocalDateTime.ofInstant(Instant.ofEpochMilli(stopAtMillis), zone)) }
    val intervals = listOf(2, 5, 10, 30, 60)

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).windowInsetsPadding(WindowInsets.statusBars).padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            LocalizedText("‹  Back", color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.clickable(onClick = onBack).padding(8.dp))
            Spacer(Modifier.weight(1f))
            LocalizedText("Timelapse", color = MaterialTheme.colorScheme.onBackground, fontSize = 21.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            LocalizedText("MODES", color = MaterialTheme.colorScheme.onBackground, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.clickable(onClick = onOpenModes).padding(horizontal = 8.dp, vertical = 10.dp))
        }
        Spacer(Modifier.height(12.dp))
        LocalizedText("Take a photo every…", color = MaterialTheme.colorScheme.onBackground, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            intervals.forEach { seconds ->
                Button(onClick = { interval = seconds; isCustom = false }, modifier = Modifier.weight(1f), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 3.dp, vertical = 8.dp), colors = ButtonDefaults.buttonColors(containerColor = if (!isCustom && interval == seconds) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant, contentColor = if (!isCustom && interval == seconds) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)) { LocalizedText("${seconds}s") }
            }
        }
        Button(onClick = { isCustom = true }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp), colors = ButtonDefaults.buttonColors(containerColor = if (isCustom) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant, contentColor = if (isCustom) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)) { LocalizedText("Custom interval / 自訂間隔", maxLines = 1) }
        if (isCustom) {
            OutlinedTextField(value = customInterval, onValueChange = { customInterval = it.filter(Char::isDigit) }, label = { LocalizedText("Seconds between photos") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(12.dp))
        TextButton(onClick = { showExposure = !showExposure }, modifier = Modifier.fillMaxWidth()) {
            LocalizedText(if (showExposure) "− Exposure / 曝光" else "+ Exposure / 曝光（進階）", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
        }
        if (showExposure) {
            if (!exposureCapabilities.supported || shutterChoices.isEmpty()) {
                LocalizedText("Manual shutter control is not supported by this camera. Automatic exposure will be used.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            } else {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        LocalizedText("Manual exposure / 手動曝光", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                        LocalizedText("Uses the rear camera's reported shutter and ISO ranges.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = manualExposureEnabled, onCheckedChange = { manualExposureEnabled = it })
                }
                if (manualExposureEnabled) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        LocalizedText("Shutter", fontWeight = FontWeight.Bold)
                        TextButton(onClick = { shutterIndex = (shutterIndex - 1).coerceAtLeast(0) }, enabled = shutterIndex > 0) { LocalizedText("−") }
                        LocalizedText(shutterLabel(shutterChoices[shutterIndex.coerceIn(shutterChoices.indices)]), fontWeight = FontWeight.Bold)
                        TextButton(onClick = { shutterIndex = (shutterIndex + 1).coerceAtMost(shutterChoices.lastIndex) }, enabled = shutterIndex < shutterChoices.lastIndex) { LocalizedText("+") }
                    }
                    val isoRange = exposureCapabilities.isoRange!!
                    val minIso = isoRange.lower.coerceAtLeast(1)
                    val maxIso = isoRange.upper.coerceAtLeast(minIso)
                    LocalizedText("ISO · $iso", fontWeight = FontWeight.Bold)
                    androidx.compose.material3.Slider(value = iso.coerceIn(minIso, maxIso).toFloat(), onValueChange = { iso = it.toInt().coerceIn(minIso, maxIso) }, valueRange = minIso.toFloat()..maxIso.toFloat())
                    LocalizedText("Choose a capture interval longer than the shutter plus 0.3 seconds.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                }
            }
        }
        LocalizedText("Light trails / 光軌疊加", color = MaterialTheme.colorScheme.onBackground, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(1, 5, 10, 20).forEach { count ->
                Button(onClick = { stackSourceCount = count }, modifier = Modifier.weight(1f), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 2.dp, vertical = 5.dp), colors = ButtonDefaults.buttonColors(containerColor = if (stackSourceCount == count) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant, contentColor = if (stackSourceCount == count) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)) {
                    LocalizedText(if (count == 1) "Off" else "×$count", maxLines = 1)
                }
            }
        }
        if (stackSourceCount > 1) LocalizedText("Combines $stackSourceCount source photos into each movie frame. A stable camera position gives the best results.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, modifier = Modifier.padding(top = 3.dp))
        if (stackSourceCount > 1) LocalizedText("Choose an interval long enough for the full group; camera processing may extend it.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
        LocalizedText("Stop automatically…", color = MaterialTheme.colorScheme.onBackground, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(TimelapseStopCondition.FRAME_COUNT to "Frame count", TimelapseStopCondition.RECORDING_DURATION to "Duration", TimelapseStopCondition.CLOCK_TIME to "Clock time").forEach { (choice, label) ->
                Button(onClick = { condition = choice }, modifier = Modifier.weight(1f), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 2.dp, vertical = 8.dp), colors = ButtonDefaults.buttonColors(containerColor = if (condition == choice) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant, contentColor = if (condition == choice) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)) { LocalizedText(label, fontSize = 11.sp, maxLines = 1) }
            }
        }
        when (condition) {
            TimelapseStopCondition.FRAME_COUNT -> OutlinedTextField(value = framesText, onValueChange = { framesText = it.filter(Char::isDigit) }, label = { LocalizedText("Number of photographs") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            TimelapseStopCondition.RECORDING_DURATION -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = hoursText, onValueChange = { hoursText = it.filter(Char::isDigit) }, label = { LocalizedText("Hours") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.weight(1f))
                OutlinedTextField(value = minutesText, onValueChange = { minutesText = it.filter(Char::isDigit) }, label = { LocalizedText("Minutes") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.weight(1f))
            }
            TimelapseStopCondition.CLOCK_TIME -> Button(onClick = {
                val current = LocalDateTime.ofInstant(Instant.ofEpochMilli(stopAtMillis), zone)
                DatePickerDialog(context, { _, year, month, day ->
                    val date = LocalDate.of(year, month + 1, day)
                    TimePickerDialog(context, { _, hour, minute ->
                        stopAtMillis = LocalDateTime.of(date, LocalTime.of(hour, minute)).atZone(zone).toInstant().toEpochMilli()
                    }, current.hour, current.minute, false).show()
                }, current.year, current.monthValue - 1, current.dayOfMonth).show()
            }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurfaceVariant)) { LocalizedText("Stop at $stopLabel", maxLines = 1) }
        }
        Spacer(Modifier.weight(1f))
        error?.let { LocalizedText(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(bottom = 8.dp)) }
        Button(onClick = {
            val chosenInterval = if (isCustom) customInterval.toIntOrNull() else interval
            val count = framesText.toIntOrNull()
            val duration = (hoursText.toLongOrNull() ?: 0L) * 60L + (minutesText.toLongOrNull() ?: 0L)
            val selectedExposure = shutterChoices.getOrNull(shutterIndex)
            val exposureFitsInterval = !manualExposureEnabled || selectedExposure != null && chosenInterval != null && selectedExposure + 300_000_000L < chosenInterval * 1_000_000_000L
            val estimatedGroupNanos = if (stackSourceCount <= 1) 0L else stackSourceCount * if (manualExposureEnabled && selectedExposure != null) selectedExposure + 300_000_000L else 430_000_000L
            val stackFitsInterval = chosenInterval != null && estimatedGroupNanos < chosenInterval * 1_000_000_000L
            val valid = chosenInterval != null && chosenInterval in 1..86400 && exposureFitsInterval && stackFitsInterval && when (condition) {
                TimelapseStopCondition.FRAME_COUNT -> count != null && count > 0
                TimelapseStopCondition.RECORDING_DURATION -> duration in 1..100000
                TimelapseStopCondition.CLOCK_TIME -> stopAtMillis > System.currentTimeMillis()
            }
            if (!valid) error = when {
                !exposureFitsInterval -> "Choose an interval longer than the shutter time plus processing time."
                !stackFitsInterval -> "Choose a longer interval for the selected light-trail photo group."
                else -> "Choose a valid interval and stopping target."
            }
            else {
                error = null
                onStart(TimelapseConfig(chosenInterval!!, condition, count ?: 0, duration.toInt(), stopAtMillis, countdownSoundEnabled, manualExposureEnabled, selectedExposure ?: 0L, iso, stackSourceCount))
            }
        }, modifier = Modifier.fillMaxWidth().height(58.dp), shape = RoundedCornerShape(18.dp), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)) { LocalizedText("Start Timelapse  ▶", fontSize = 18.sp, fontWeight = FontWeight.Bold) }
    }
}
