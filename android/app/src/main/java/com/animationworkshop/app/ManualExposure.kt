package com.animationworkshop.app

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Range
import kotlin.math.roundToInt

internal data class ManualExposureCapabilities(
    val shutterRangeNanos: Range<Long>?,
    val isoRange: Range<Int>?
) {
    val supported: Boolean get() = shutterRangeNanos != null && isoRange != null
    val shutterChoices: List<Long> get() = supportedShutterChoices(shutterRangeNanos)

    companion object {
        fun detect(context: Context): ManualExposureCapabilities {
            val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            for (cameraId in manager.cameraIdList) {
                val characteristics = runCatching { manager.getCameraCharacteristics(cameraId) }.getOrNull() ?: continue
                if (characteristics.get(CameraCharacteristics.LENS_FACING) != CameraCharacteristics.LENS_FACING_BACK) continue
                val capabilities = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
                if (CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR !in capabilities) continue
                val shutter = characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
                val iso = characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
                if (shutter != null && iso != null) return ManualExposureCapabilities(shutter, iso)
            }
            return ManualExposureCapabilities(null, null)
        }
    }
}

internal fun supportedShutterChoices(range: Range<Long>?): List<Long> {
    if (range == null) return emptyList()
    return supportedShutterChoices(range.lower, range.upper)
}

internal fun supportedShutterChoices(minNanos: Long, maxNanos: Long): List<Long> {
    if (minNanos <= 0L || maxNanos < minNanos) return emptyList()
    val common = listOf(1L to 60L, 1L to 30L, 1L to 15L, 1L to 8L, 1L to 4L, 1L to 2L, 1L to 1L, 2L to 1L, 5L to 1L)
        .map { (seconds, divisor) -> seconds * 1_000_000_000L / divisor }
    return common.filter { it in minNanos..maxNanos }.ifEmpty {
        listOf(minNanos, maxNanos).distinct()
    }
}

internal fun shutterLabel(nanos: Long): String {
    val seconds = nanos / 1_000_000_000.0
    return if (seconds < 1.0) "1/${(1.0 / seconds).roundToInt()} s" else "${"%.2f".format(java.util.Locale.US, seconds)} s"
}
