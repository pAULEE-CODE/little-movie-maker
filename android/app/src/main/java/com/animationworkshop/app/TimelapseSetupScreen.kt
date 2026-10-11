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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
    val stopAtMillis: Long
)

private val SetupBackground = Color(0xFF182522)
private val SetupTeal = Color(0xFF31A996)

@Composable
internal fun TimelapseSetupScreen(onOpenModes: () -> Unit, onBack: () -> Unit, onStart: (TimelapseConfig) -> Unit) {
    val context = LocalContext.current
    var interval by remember { mutableIntStateOf(5) }
    var customInterval by remember { mutableStateOf("5") }
    var isCustom by remember { mutableStateOf(false) }
    var condition by remember { mutableStateOf(TimelapseStopCondition.FRAME_COUNT) }
    var framesText by remember { mutableStateOf("30") }
    var hoursText by remember { mutableStateOf("0") }
    var minutesText by remember { mutableStateOf("10") }
    var stopAtMillis by remember { mutableStateOf(System.currentTimeMillis() + 60 * 60 * 1000L) }
    var error by remember { mutableStateOf<String?>(null) }
    val zone = ZoneId.systemDefault()
    val stopLabel = remember(stopAtMillis) { DateTimeFormatter.ofPattern("MMM d, yyyy · h:mm a").format(LocalDateTime.ofInstant(Instant.ofEpochMilli(stopAtMillis), zone)) }
    val intervals = listOf(2, 5, 10, 30, 60)

    Column(Modifier.fillMaxSize().background(SetupBackground).windowInsetsPadding(WindowInsets.statusBars).padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("‹  Back", color = Color.White, modifier = Modifier.clickable(onClick = onBack).padding(8.dp))
            Spacer(Modifier.weight(1f))
            Text("Timelapse", color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Text("MODES", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.clickable(onClick = onOpenModes).padding(horizontal = 8.dp, vertical = 10.dp))
        }
        Spacer(Modifier.height(12.dp))
        Text("Take a photo every…", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            intervals.forEach { seconds ->
                Button(onClick = { interval = seconds; isCustom = false }, modifier = Modifier.weight(1f), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 3.dp, vertical = 8.dp), colors = ButtonDefaults.buttonColors(containerColor = if (!isCustom && interval == seconds) SetupTeal else Color(0xFF34443F))) { Text("${seconds}s") }
            }
        }
        Button(onClick = { isCustom = true }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp), colors = ButtonDefaults.buttonColors(containerColor = if (isCustom) SetupTeal else Color(0xFF34443F))) { Text("Custom interval") }
        if (isCustom) {
            OutlinedTextField(value = customInterval, onValueChange = { customInterval = it.filter(Char::isDigit) }, label = { Text("Seconds between photos") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(18.dp))
        Text("Stop automatically…", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(TimelapseStopCondition.FRAME_COUNT to "Frame count", TimelapseStopCondition.RECORDING_DURATION to "Duration", TimelapseStopCondition.CLOCK_TIME to "Clock time").forEach { (choice, label) ->
                Button(onClick = { condition = choice }, modifier = Modifier.weight(1f), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 2.dp, vertical = 8.dp), colors = ButtonDefaults.buttonColors(containerColor = if (condition == choice) SetupTeal else Color(0xFF34443F))) { Text(label, fontSize = 11.sp, maxLines = 1) }
            }
        }
        when (condition) {
            TimelapseStopCondition.FRAME_COUNT -> OutlinedTextField(value = framesText, onValueChange = { framesText = it.filter(Char::isDigit) }, label = { Text("Number of photographs") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            TimelapseStopCondition.RECORDING_DURATION -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = hoursText, onValueChange = { hoursText = it.filter(Char::isDigit) }, label = { Text("Hours") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.weight(1f))
                OutlinedTextField(value = minutesText, onValueChange = { minutesText = it.filter(Char::isDigit) }, label = { Text("Minutes") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.weight(1f))
            }
            TimelapseStopCondition.CLOCK_TIME -> Button(onClick = {
                val current = LocalDateTime.ofInstant(Instant.ofEpochMilli(stopAtMillis), zone)
                DatePickerDialog(context, { _, year, month, day ->
                    val date = LocalDate.of(year, month + 1, day)
                    TimePickerDialog(context, { _, hour, minute ->
                        stopAtMillis = LocalDateTime.of(date, LocalTime.of(hour, minute)).atZone(zone).toInstant().toEpochMilli()
                    }, current.hour, current.minute, false).show()
                }, current.year, current.monthValue - 1, current.dayOfMonth).show()
            }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF34443F))) { Text("Stop at $stopLabel") }
        }
        Spacer(Modifier.weight(1f))
        error?.let { Text(it, color = Color(0xFFFFD3CC), modifier = Modifier.padding(bottom = 8.dp)) }
        Button(onClick = {
            val chosenInterval = if (isCustom) customInterval.toIntOrNull() else interval
            val count = framesText.toIntOrNull()
            val duration = (hoursText.toLongOrNull() ?: 0L) * 60L + (minutesText.toLongOrNull() ?: 0L)
            val valid = chosenInterval != null && chosenInterval in 1..86400 && when (condition) {
                TimelapseStopCondition.FRAME_COUNT -> count != null && count > 0
                TimelapseStopCondition.RECORDING_DURATION -> duration in 1..100000
                TimelapseStopCondition.CLOCK_TIME -> stopAtMillis > System.currentTimeMillis()
            }
            if (!valid) error = "Choose a valid interval and stopping target."
            else {
                error = null
                onStart(TimelapseConfig(chosenInterval!!, condition, count ?: 0, duration.toInt(), stopAtMillis))
            }
        }, modifier = Modifier.fillMaxWidth().height(58.dp), shape = RoundedCornerShape(18.dp), colors = ButtonDefaults.buttonColors(containerColor = SetupTeal)) { Text("Start Timelapse  ▶", fontSize = 18.sp, fontWeight = FontWeight.Bold) }
    }
}

