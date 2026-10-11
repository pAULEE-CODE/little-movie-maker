package com.animationworkshop.app

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

internal enum class ModeDestination(val label: String, val icon: ModeIcon, val color: Color) {
    CAMERA("Camera", ModeIcon.CAMERA, Color(0xFF31A996)),
    TIMELAPSE("Timelapse", ModeIcon.TIMER, Color(0xFFFF8177)),
    PREVIEW("Preview", ModeIcon.PLAY, Color(0xFF8E83D8)),
    EDIT("Edit", ModeIcon.SCISSORS, Color(0xFFFFB64D)),
    EXPORT("Export", ModeIcon.FILM, Color(0xFF50A6D8)),
    PROJECTS("Projects", ModeIcon.FOLDER, Color(0xFF71BD7A))
}

internal enum class ModeIcon { CAMERA, TIMER, PLAY, SCISSORS, FILM, FOLDER }

@Composable
internal fun ModesWheel(
    visible: Boolean,
    active: ModeDestination,
    onDismiss: () -> Unit,
    onSelect: (ModeDestination) -> Unit
) {
    BackHandler(enabled = visible, onBack = onDismiss)
    BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        val wheelSize = min(maxWidth.value, maxHeight.value * 0.86f).dp.coerceAtMost(480.dp)
        val buttonSize = (wheelSize * 0.235f).coerceIn(52.dp, 94.dp)
        val radius = wheelSize * 0.345f
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            AnimatedVisibility(
                visible = visible,
                enter = fadeIn() + scaleIn(initialScale = 0.72f),
                exit = fadeOut() + scaleOut(targetScale = 0.78f)
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.52f)).clickable(onClick = onDismiss))
                    Box(Modifier.size(wheelSize), contentAlignment = Alignment.Center) {
                        ModeDestination.entries.forEachIndexed { index, destination ->
                            val angle = Math.toRadians((-90.0 + index * 60.0))
                            val dx = radius * cos(angle).toFloat()
                            val dy = radius * sin(angle).toFloat()
                            ModeWheelButton(
                                destination = destination,
                                selected = active == destination,
                                size = buttonSize,
                                modifier = Modifier.offset(x = dx, y = dy),
                                onClick = { onSelect(destination) }
                            )
                        }
                        Box(
                            Modifier.size(wheelSize * 0.31f).clip(CircleShape).background(Color(0xFFFFFDF5)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("Animation\nWorkshop", color = Color(0xFF23413C), fontSize = 14.sp, lineHeight = 17.sp, fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Center)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ModeWheelButton(
    destination: ModeDestination,
    selected: Boolean,
    size: Dp,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Column(
        modifier = modifier.size(size).scale(if (selected) 1.06f else 1f).clip(CircleShape)
            .background(destination.color)
            .then(if (selected) Modifier.border(3.dp, Color.White, CircleShape) else Modifier)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center
    ) {
        Canvas(Modifier.size(size * 0.37f)) { drawModeIcon(destination.icon, Color.White) }
        Text(destination.label, color = Color.White, fontSize = if (size < 82.dp) 11.sp else 12.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 1)
        if (selected) Text("HERE", color = Color.White.copy(alpha = .9f), fontSize = 8.sp, fontWeight = FontWeight.ExtraBold, lineHeight = 9.sp)
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawModeIcon(icon: ModeIcon, color: Color) {
    val sw = size.minDimension * 0.09f
    val left = size.width * .16f
    val top = size.height * .2f
    val right = size.width * .84f
    val bottom = size.height * .8f
    when (icon) {
        ModeIcon.CAMERA -> {
            drawRoundRect(color, androidx.compose.ui.geometry.Offset(left, top + sw), androidx.compose.ui.geometry.Size(right - left, bottom - top - sw), cornerRadius = androidx.compose.ui.geometry.CornerRadius(sw), style = Stroke(sw))
            drawCircle(color, size.minDimension * .17f, androidx.compose.ui.geometry.Offset(size.width * .5f, size.height * .55f), style = Stroke(sw))
            drawLine(color, androidx.compose.ui.geometry.Offset(left + sw, top), androidx.compose.ui.geometry.Offset(size.width * .43f, top), sw, StrokeCap.Round)
        }
        ModeIcon.TIMER -> {
            drawCircle(color, size.minDimension * .34f, androidx.compose.ui.geometry.Offset(size.width * .5f, size.height * .55f), style = Stroke(sw))
            drawLine(color, androidx.compose.ui.geometry.Offset(size.width * .5f, size.height * .55f), androidx.compose.ui.geometry.Offset(size.width * .5f, size.height * .33f), sw, StrokeCap.Round)
            drawLine(color, androidx.compose.ui.geometry.Offset(size.width * .5f, size.height * .55f), androidx.compose.ui.geometry.Offset(size.width * .67f, size.height * .62f), sw, StrokeCap.Round)
            drawLine(color, androidx.compose.ui.geometry.Offset(size.width * .39f, top), androidx.compose.ui.geometry.Offset(size.width * .61f, top), sw, StrokeCap.Round)
        }
        ModeIcon.PLAY -> {
            val path = Path().apply { moveTo(size.width * .34f, size.height * .2f); lineTo(size.width * .76f, size.height * .5f); lineTo(size.width * .34f, size.height * .8f); close() }
            drawPath(path, color)
        }
        ModeIcon.SCISSORS -> {
            drawCircle(color, sw * 1.65f, androidx.compose.ui.geometry.Offset(size.width * .28f, size.height * .72f), style = Stroke(sw))
            drawCircle(color, sw * 1.65f, androidx.compose.ui.geometry.Offset(size.width * .28f, size.height * .28f), style = Stroke(sw))
            drawLine(color, androidx.compose.ui.geometry.Offset(size.width * .39f, size.height * .36f), androidx.compose.ui.geometry.Offset(size.width * .78f, size.height * .72f), sw, StrokeCap.Round)
            drawLine(color, androidx.compose.ui.geometry.Offset(size.width * .39f, size.height * .64f), androidx.compose.ui.geometry.Offset(size.width * .78f, size.height * .28f), sw, StrokeCap.Round)
        }
        ModeIcon.FILM -> {
            drawRoundRect(color, androidx.compose.ui.geometry.Offset(left, top), androidx.compose.ui.geometry.Size(right - left, bottom - top), cornerRadius = androidx.compose.ui.geometry.CornerRadius(sw), style = Stroke(sw))
            for (y in listOf(.34f, .66f)) {
                drawLine(color, androidx.compose.ui.geometry.Offset(left, size.height * y), androidx.compose.ui.geometry.Offset(right, size.height * y), sw * .7f)
            }
            for (x in listOf(.3f, .7f)) drawLine(color, androidx.compose.ui.geometry.Offset(size.width * x, top), androidx.compose.ui.geometry.Offset(size.width * x, size.height * .34f), sw * .7f)
        }
        ModeIcon.FOLDER -> {
            val path = Path().apply {
                moveTo(left, size.height * .35f); lineTo(size.width * .44f, size.height * .35f); lineTo(size.width * .53f, size.height * .46f); lineTo(right, size.height * .46f)
                lineTo(size.width * .77f, bottom); lineTo(left, bottom); close()
            }
            drawPath(path, color, style = Stroke(sw, cap = StrokeCap.Round))
        }
    }
}

