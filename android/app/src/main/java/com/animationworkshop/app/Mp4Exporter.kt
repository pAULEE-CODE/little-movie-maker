package com.animationworkshop.app

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.min

internal data class ExportConfiguration(
    val width: Int,
    val height: Int,
    val projectFps: Int,
    val encoderFps: Int
) {
    val exactFrameTiming: Boolean get() = encoderFps % projectFps == 0
}

internal data class ExportedMovie(val uri: Uri, val fileName: String)

internal object ProjectExportIdentity {
    fun fileNamePrefix(projectId: String): String? = projectId.takeIf { it.matches(Regex("[a-zA-Z0-9-]{1,80}")) }
        ?.let { "AnimationWorkshop_${it}_" }

    fun belongsToProject(fileName: String, projectId: String): Boolean =
        fileNamePrefix(projectId)?.let(fileName::startsWith) == true
}

private data class YuvFrame(val y: ByteArray, val u: ByteArray, val v: ByteArray)

/** Streams sampled frames into Android's AVC encoder without changing project files or metadata. */
internal object Mp4Exporter {
    private const val TAG = "AnimationExport"
    private const val MIME_TYPE = "video/avc"
    private const val BIT_RATE = 5_000_000
    private const val TIMEOUT_US = 10_000L

    fun inspect(frames: List<AnimationFrame>, fps: Int, rotationDegrees: Int = 0): ExportConfiguration {
        val portrait = frames.asSequence().mapNotNull(::orientedDimensions).firstOrNull()?.let { dimensions ->
            val rotated = rotationDegrees.mod(180) == 90
            if (rotated) dimensions.first >= dimensions.second else dimensions.first < dimensions.second
        } ?: false
        val width = if (portrait) 720 else 1280
        val height = if (portrait) 1280 else 720
        val projectFps = fps.coerceIn(1, 24)
        val encoderFps = chooseEncoder(projectFps, width, height).second
        return ExportConfiguration(width, height, projectFps, encoderFps)
    }

    suspend fun export(
        context: Context,
        frames: List<AnimationFrame>,
        configuration: ExportConfiguration,
        projectId: String? = null,
        rotationDegrees: Int = 0,
        onProgress: suspend (Int) -> Unit
    ): ExportedMovie {
        require(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "Gallery export requires Android 10 or newer on this device."
        }
        require(frames.isNotEmpty()) { "Add at least one photograph before exporting." }

        val resolver = context.contentResolver
        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val fileName = uniqueFileName(context, projectId)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/AnimationWorkshop")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val outputUri = resolver.insert(collection, values) ?: error("Android could not create the movie file.")
        Log.i(TAG, "Export started: frames=${frames.size}, projectFps=${configuration.projectFps}, output=${configuration.width}x${configuration.height}, destination=$fileName")
        var published = false
        var codec: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        var codecStarted = false
        var descriptor: android.os.ParcelFileDescriptor? = null

        try {
            val (codecInfo, selectedFps) = chooseEncoder(configuration.projectFps, configuration.width, configuration.height)
            require(selectedFps == configuration.encoderFps) { "The available video encoder changed. Please try exporting again." }
            val colorFormat = codecInfo.getCapabilitiesForType(MIME_TYPE).videoCapabilities
                ?.let { codecInfo.getCapabilitiesForType(MIME_TYPE).colorFormats.firstOrNull { format -> format == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible } }
                ?: error("This device does not provide a compatible H.264 video encoder.")
            Log.i(TAG, "Selected AVC encoder=${codecInfo.name}, colorFormat=0x${colorFormat.toString(16)}, encoderFps=$selectedFps, supportedColorFormats=${codecInfo.getCapabilitiesForType(MIME_TYPE).colorFormats.joinToString { "0x${it.toString(16)}" }}")

            descriptor = resolver.openFileDescriptor(outputUri, "w") ?: error("Could not open the new movie file.")
            val activeMuxer = MediaMuxer(descriptor.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = activeMuxer
            val format = MediaFormat.createVideoFormat(MIME_TYPE, configuration.width, configuration.height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFormat)
                setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
                setInteger(MediaFormat.KEY_FRAME_RATE, selectedFps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
            }
            val activeCodec = MediaCodec.createByCodecName(codecInfo.name)
            codec = activeCodec
            activeCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            activeCodec.start()
            codecStarted = true
            Log.i(TAG, "Encoder started: name=${activeCodec.name}, inputFormat=${activeCodec.inputFormat}, configuredFormat=$format")

            val totalTicks = frames.sumOf { it.duration.coerceIn(1, 10).toLong() }
            val totalOutputFrames = ceil(totalTicks.toDouble() * selectedFps / configuration.projectFps).toLong().coerceAtLeast(1L)
            var trackIndex = -1
            val bufferInfo = MediaCodec.BufferInfo()
            var outputEnded = false
            var outputFormatChanged = false
            var encodedSamples = 0L
            var encodedSyncSamples = 0L
            var configOutputBuffers = 0L
            var previousPresentationTimeUs = -1L
            var submittedFrames = 0L

            fun drainEncoder(endOfStream: Boolean) {
                while (true) {
                    val outputIndex = activeCodec.dequeueOutputBuffer(bufferInfo, if (endOfStream) TIMEOUT_US else 0L)
                    when {
                        outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                        outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            check(!muxerStarted) { "Encoder changed its output format unexpectedly." }
                            val outputFormat = activeCodec.outputFormat
                            val outputMime = outputFormat.getString(MediaFormat.KEY_MIME)
                            val csd0Bytes = outputFormat.getByteBuffer("csd-0")?.remaining() ?: 0
                            val csd1Bytes = outputFormat.getByteBuffer("csd-1")?.remaining() ?: 0
                            Log.i(TAG, "Encoder output format: $outputFormat; mime=$outputMime, csd-0=$csd0Bytes bytes, csd-1=$csd1Bytes bytes")
                            check(outputMime == MIME_TYPE) { "Encoder returned an unexpected output format: $outputMime" }
                            check(csd0Bytes > 0 || csd1Bytes > 0) { "AVC output format did not include SPS/PPS codec configuration data." }
                            check(outputFormat.getInteger(MediaFormat.KEY_WIDTH) == configuration.width && outputFormat.getInteger(MediaFormat.KEY_HEIGHT) == configuration.height) {
                                "Encoder output dimensions do not match the requested movie size."
                            }
                            trackIndex = activeMuxer.addTrack(outputFormat)
                            activeMuxer.start()
                            muxerStarted = true
                            outputFormatChanged = true
                            Log.i(TAG, "MediaMuxer started after INFO_OUTPUT_FORMAT_CHANGED; trackIndex=$trackIndex")
                        }
                        outputIndex >= 0 -> {
                            val encoded = activeCodec.getOutputBuffer(outputIndex)
                            val flags = bufferInfo.flags
                            Log.d(TAG, "Encoder output buffer: index=$outputIndex, offset=${bufferInfo.offset}, size=${bufferInfo.size}, ptsUs=${bufferInfo.presentationTimeUs}, flags=$flags")
                            val isCodecConfig = flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                            if (isCodecConfig) configOutputBuffers++
                            if (bufferInfo.size > 0 && !isCodecConfig) {
                                check(outputFormatChanged && muxerStarted && trackIndex >= 0) {
                                    "Encoder produced a video sample before reporting its output format to MediaMuxer."
                                }
                                check(encoded != null) { "Encoder reported a nonempty sample without an output buffer." }
                                check(bufferInfo.offset >= 0 && bufferInfo.size > 0 && bufferInfo.offset.toLong() + bufferInfo.size <= encoded.capacity()) {
                                    "Encoder returned an invalid output buffer range (offset=${bufferInfo.offset}, size=${bufferInfo.size}, capacity=${encoded.capacity()})."
                                }
                                check(bufferInfo.presentationTimeUs >= 0 && bufferInfo.presentationTimeUs > previousPresentationTimeUs) {
                                    "Encoder returned non-increasing presentation timestamps."
                                }
                                if (encodedSamples == 0L) {
                                    check(flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0) { "The first encoded AVC sample was not a sync frame." }
                                }
                                encoded.position(bufferInfo.offset)
                                encoded.limit(bufferInfo.offset + bufferInfo.size)
                                activeMuxer.writeSampleData(trackIndex, encoded, bufferInfo)
                                encodedSamples++
                                previousPresentationTimeUs = bufferInfo.presentationTimeUs
                                if (flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0) encodedSyncSamples++
                                if (encodedSamples <= 3 || flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0) {
                                    Log.d(TAG, "Muxed AVC sample #$encodedSamples: ptsUs=${bufferInfo.presentationTimeUs}, size=${bufferInfo.size}, flags=$flags")
                                }
                            }
                            if (flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                outputEnded = true
                                Log.i(TAG, "Encoder EOS received: samples=$encodedSamples, syncSamples=$encodedSyncSamples, configBuffers=$configOutputBuffers, ptsUs=${bufferInfo.presentationTimeUs}")
                            }
                            activeCodec.releaseOutputBuffer(outputIndex, false)
                            if (outputEnded) return
                        }
                    }
                }
            }

            val cumulativeEnds = LongArray(frames.size)
            var elapsedTicks = 0L
            frames.forEachIndexed { index, frame ->
                elapsedTicks += frame.duration.coerceIn(1, 10)
                cumulativeEnds[index] = elapsedTicks
            }
            var sourceIndex = 0
            var cachedSourceIndex = -1
            var yuvFrame: YuvFrame? = null
            val pixelScratch = IntArray(configuration.width * configuration.height)
            val contextJob = currentCoroutineContext()

            for (outputIndex in 0 until totalOutputFrames) {
                contextJob.ensureActive()
                val timelinePosition = outputIndex * configuration.projectFps.toLong()
                while (sourceIndex < frames.lastIndex && timelinePosition >= cumulativeEnds[sourceIndex] * selectedFps) {
                    sourceIndex++
                }
                if (cachedSourceIndex != sourceIndex) {
                    val decoded = decodeSampledFrame(frames[sourceIndex].file, configuration.width, configuration.height)
                        ?: error("Frame ${sourceIndex + 1} could not be read. Check that the photograph is still available.")
                    val source = rotateMovieFrame(decoded, rotationDegrees)
                    if (source !== decoded) decoded.recycle()
                    val fitted = fitToOutput(source, configuration.width, configuration.height)
                    if (fitted !== source) source.recycle()
                    yuvFrame = bitmapToYuv420(fitted, pixelScratch)
                    fitted.recycle()
                    cachedSourceIndex = sourceIndex
                }

                val inputIndex = activeCodec.dequeueInputBuffer(TIMEOUT_US)
                if (inputIndex < 0) {
                    drainEncoder(endOfStream = false)
                    // Do not drop a frame when the encoder is temporarily busy.
                    var availableIndex = inputIndex
                    while (availableIndex < 0) {
                        contextJob.ensureActive()
                        availableIndex = activeCodec.dequeueInputBuffer(TIMEOUT_US)
                        if (availableIndex < 0) drainEncoder(endOfStream = false)
                    }
                    queueYuvFrame(activeCodec, availableIndex, yuvFrame!!, outputIndex, selectedFps)
                } else {
                    queueYuvFrame(activeCodec, inputIndex, yuvFrame!!, outputIndex, selectedFps)
                }
                submittedFrames++
                drainEncoder(endOfStream = false)
                if (outputIndex % maxOf(1L, totalOutputFrames / 100L) == 0L || outputIndex == totalOutputFrames - 1) {
                    onProgress(((outputIndex + 1L) * 100L / totalOutputFrames).toInt().coerceIn(0, 100))
                }
            }
            check(submittedFrames == totalOutputFrames) { "Not all animation frames were submitted to the encoder." }
            Log.i(TAG, "All raw frames submitted: submitted=$submittedFrames, expected=$totalOutputFrames")
            var eosInput = activeCodec.dequeueInputBuffer(TIMEOUT_US)
            while (eosInput < 0) {
                contextJob.ensureActive()
                drainEncoder(endOfStream = false)
                eosInput = activeCodec.dequeueInputBuffer(TIMEOUT_US)
            }
            val endPts = totalOutputFrames * 1_000_000L / selectedFps
            activeCodec.queueInputBuffer(eosInput, 0, 0, endPts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            while (!outputEnded) {
                contextJob.ensureActive()
                drainEncoder(endOfStream = true)
            }
            check(outputFormatChanged) { "Encoder ended without reporting an output format; no MP4 video track was created." }
            check(encodedSamples > 0L && encodedSyncSamples > 0L) { "Encoder produced no playable AVC samples or sync frame." }
            Log.i(TAG, "Encoder drained: outputEnded=$outputEnded, samples=$encodedSamples, syncSamples=$encodedSyncSamples, finalPtsUs=$previousPresentationTimeUs")
            check(muxerStarted) { "The video encoder did not produce an MP4 video track." }
            activeMuxer.stop()
            muxerStarted = false
            activeMuxer.release()
            muxer = null
            activeCodec.stop()
            codecStarted = false
            activeCodec.release()
            codec = null
            descriptor.close()
            descriptor = null

            val trackDiagnostics = inspectMp4(context, outputUri, configuration, encodedSamples)
            Log.i(TAG, "MP4 validation passed: $trackDiagnostics")

            val publish = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            check(resolver.update(outputUri, publish, null, null) == 1) { "The movie was encoded but could not be published to Movies." }
            onProgress(100)
            published = true
            return ExportedMovie(outputUri, fileName)
        } catch (failure: Throwable) {
            Log.e(TAG, "Export failed for $fileName (uri=$outputUri), encoderMetrics=${runCatching { codec?.metrics }.getOrNull()}", failure)
            throw failure
        } finally {
            runCatching { if (codecStarted) codec?.stop() }
            runCatching { codec?.release() }
            runCatching { if (muxerStarted) muxer?.stop() }
            runCatching { muxer?.release() }
            runCatching { descriptor?.close() }
            if (!published) runCatching { resolver.delete(outputUri, null, null) }
        }
    }

    private fun chooseEncoder(projectFps: Int, width: Int, height: Int): Pair<MediaCodecInfo, Int> {
        val encoders = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { info ->
            info.isEncoder && info.supportedTypes.any { it.equals(MIME_TYPE, ignoreCase = true) }
        }
        val candidates = buildList {
            add(projectFps.coerceIn(1, 24))
            for (multiple in 2..120 / projectFps.coerceIn(1, 24)) add(projectFps.coerceIn(1, 24) * multiple)
            for (rate in (projectFps.coerceIn(1, 24) + 1)..120) add(rate)
        }.distinct()
        encoders.forEach { info ->
            val caps = info.getCapabilitiesForType(MIME_TYPE)
            if (caps.colorFormats.none { it == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible }) return@forEach
            val videoCaps = caps.videoCapabilities ?: return@forEach
            candidates.forEach { rate ->
                if (runCatching { videoCaps.areSizeAndRateSupported(width, height, rate.toDouble()) }.getOrDefault(false)) {
                    Log.i(TAG, "Encoder capability match: ${info.name} supports ${width}x$height at $rate fps; colorFormats=${caps.colorFormats.joinToString { "0x${it.toString(16)}" }}")
                    return info to rate
                }
            }
        }
        error("No compatible H.264 encoder supports ${width}×$height video at a timing-compatible frame rate.")
    }

    private fun queueYuvFrame(codec: MediaCodec, inputIndex: Int, frame: YuvFrame, frameIndex: Long, fps: Int) {
        val inputImage = codec.getInputImage(inputIndex) ?: error("The H.264 encoder did not provide a writable video frame.")
        try {
            if (frameIndex == 0L) Log.i(TAG, "Writable encoder input image: format=${inputImage.format}, ${inputImage.width}x${inputImage.height}, planes=${inputImage.planes.joinToString { "row=${it.rowStride}/pixel=${it.pixelStride}" }}")
            writeYuv420(frame, inputImage)
            val presentationTimeUs = frameIndex * 1_000_000L / fps
            // queueInputBuffer's size describes the submitted raw YUV payload. Zero silently
            // queues an empty input and can produce only codec configuration/EOS output.
            val payloadSize = inputImage.width * inputImage.height * 3 / 2
            codec.queueInputBuffer(inputIndex, 0, payloadSize, presentationTimeUs, 0)
            Log.v(TAG, "Submitted YUV420 frame: inputIndex=$inputIndex, size=$payloadSize, ptsUs=$presentationTimeUs")
        } finally {
            inputImage.close()
        }
    }

    private fun inspectMp4(context: Context, uri: Uri, configuration: ExportConfiguration, expectedSamples: Long): String {
        val descriptor = context.contentResolver.openFileDescriptor(uri, "r") ?: error("Could not reopen the encoded MP4 for validation.")
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(descriptor.fileDescriptor)
            var videoTrack = -1
            var videoFormat: MediaFormat? = null
            for (index in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(index)
                if (format.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true) {
                    videoTrack = index
                    videoFormat = format
                    break
                }
            }
            check(videoTrack >= 0 && videoFormat != null) { "The completed MP4 does not contain a video track." }
            val format = videoFormat!!
            check(format.getString(MediaFormat.KEY_MIME) == MIME_TYPE) { "The MP4 video track is not H.264/AVC." }
            check(format.getInteger(MediaFormat.KEY_WIDTH) == configuration.width && format.getInteger(MediaFormat.KEY_HEIGHT) == configuration.height) {
                "The MP4 video track has unexpected dimensions."
            }
            val csdBytes = (format.getByteBuffer("csd-0")?.remaining() ?: 0) + (format.getByteBuffer("csd-1")?.remaining() ?: 0)
            check(csdBytes > 0) { "The MP4 AVC track is missing SPS/PPS codec configuration data." }
            val declaredDurationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L

            extractor.selectTrack(videoTrack)
            val sampleBuffer = java.nio.ByteBuffer.allocateDirect(8 * 1024 * 1024)
            var sampleCount = 0L
            var syncSamples = 0L
            var firstPts = -1L
            var lastPts = -1L
            while (extractor.sampleTrackIndex >= 0) {
                val pts = extractor.sampleTime
                check(pts >= 0L && pts > lastPts) { "The MP4 contains invalid or non-increasing sample timestamps." }
                sampleBuffer.clear()
                val sampleSize = extractor.readSampleData(sampleBuffer, 0)
                check(sampleSize > 0) { "The MP4 contains an empty or unreadable video sample." }
                val flags = extractor.sampleFlags
                if (sampleCount == 0L) check(flags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) { "The MP4 does not begin with a sync sample." }
                if (flags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) syncSamples++
                if (firstPts < 0L) firstPts = pts
                lastPts = pts
                sampleCount++
                if (!extractor.advance()) break
            }
            check(sampleCount > 0L && syncSamples > 0L) { "The MP4 contains no decodable video samples." }
            check(sampleCount == expectedSamples) { "MP4 sample count ($sampleCount) differs from the encoder output ($expectedSamples)." }
            check(declaredDurationUs > 0L) { "The MP4 container reports a zero or missing video duration." }
            val durationUs = declaredDurationUs
            val decodeResult = decodeFirstSample(context, uri, format)
            return "mime=${format.getString(MediaFormat.KEY_MIME)}, dimensions=${format.getInteger(MediaFormat.KEY_WIDTH)}x${format.getInteger(MediaFormat.KEY_HEIGHT)}, durationUs=$durationUs, samples=$sampleCount, syncSamples=$syncSamples, codecConfigBytes=$csdBytes, firstFrameDecode=$decodeResult, bytes=${descriptor.statSize}"
        } finally {
            runCatching { extractor.release() }
            descriptor.close()
        }
    }

    private fun decodeFirstSample(context: Context, uri: Uri, format: MediaFormat): String {
        val descriptor = context.contentResolver.openFileDescriptor(uri, "r") ?: error("Could not open the MP4 for decoder validation.")
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        var decoderStarted = false
        try {
            extractor.setDataSource(descriptor.fileDescriptor)
            val track = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME) == MIME_TYPE
            } ?: error("The MP4 AVC track could not be reopened for decoder validation.")
            extractor.selectTrack(track)
            extractor.seekTo(0L, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            val samplePts = extractor.sampleTime
            check(samplePts >= 0L) { "The MP4 does not contain a readable initial AVC sample." }

            val activeDecoder = MediaCodec.createDecoderByType(MIME_TYPE)
            decoder = activeDecoder
            activeDecoder.configure(format, null, null, 0)
            activeDecoder.start()
            decoderStarted = true
            val bufferInfo = MediaCodec.BufferInfo()
            var sampleQueued = false
            var eosQueued = false
            var firstFrameDecoded = false
            var decoderOutputFormat = "not reported"
            val deadline = android.os.SystemClock.elapsedRealtime() + 5_000L

            while (!firstFrameDecoded && android.os.SystemClock.elapsedRealtime() < deadline) {
                val inputIndex = activeDecoder.dequeueInputBuffer(TIMEOUT_US)
                if (inputIndex >= 0 && !eosQueued) {
                    val input = activeDecoder.getInputBuffer(inputIndex) ?: error("The H.264 decoder did not provide an input buffer.")
                    if (!sampleQueued) {
                        input.clear()
                        val sampleSize = extractor.readSampleData(input, 0)
                        check(sampleSize > 0) { "The first AVC sample could not be read for decoding." }
                        activeDecoder.queueInputBuffer(inputIndex, 0, sampleSize, samplePts, 0)
                        sampleQueued = true
                        Log.i(TAG, "Decoder validation queued first sample: ptsUs=$samplePts, size=$sampleSize")
                    } else {
                        activeDecoder.queueInputBuffer(inputIndex, 0, 0, samplePts + 1_000_000L / 24, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        eosQueued = true
                    }
                }

                val outputIndex = activeDecoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                when {
                    outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> decoderOutputFormat = activeDecoder.outputFormat.toString()
                    outputIndex >= 0 -> {
                        if (bufferInfo.size > 0) firstFrameDecoded = true
                        activeDecoder.releaseOutputBuffer(outputIndex, false)
                    }
                }
            }
            check(firstFrameDecoded) { "The MP4 AVC sync sample did not decode within five seconds." }
            Log.i(TAG, "AVC decoder validation passed: codec=${activeDecoder.name}, outputFormat=$decoderOutputFormat")
            return "decoded by ${activeDecoder.name}"
        } finally {
            runCatching { if (decoderStarted) decoder?.stop() }
            runCatching { decoder?.release() }
            runCatching { extractor.release() }
            descriptor.close()
        }
    }

    private fun bitmapToYuv420(bitmap: Bitmap, pixels: IntArray): YuvFrame {
        val width = bitmap.width
        val height = bitmap.height
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val yPlane = ByteArray(width * height)
        val uPlane = ByteArray(width * height / 4)
        val vPlane = ByteArray(width * height / 4)
        for (row in 0 until height) {
            for (column in 0 until width) {
                val color = pixels[row * width + column]
                val r = color shr 16 and 0xff
                val g = color shr 8 and 0xff
                val b = color and 0xff
                val y = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                yPlane[row * width + column] = y.coerceIn(0, 255).toByte()
                if (row % 2 == 0 && column % 2 == 0) {
                    val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                    val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                    val chromaIndex = row / 2 * (width / 2) + column / 2
                    uPlane[chromaIndex] = u.coerceIn(0, 255).toByte()
                    vPlane[chromaIndex] = v.coerceIn(0, 255).toByte()
                }
            }
        }
        return YuvFrame(yPlane, uPlane, vPlane)
    }

    private fun writeYuv420(frame: YuvFrame, image: android.media.Image) {
        val width = image.width
        val height = image.height
        val planes = image.planes
        val yPlane = planes[0].buffer.duplicate().apply { clear() }
        val uPlane = planes[1].buffer.duplicate().apply { clear() }
        val vPlane = planes[2].buffer.duplicate().apply { clear() }
        for (row in 0 until height) {
            for (column in 0 until width) {
                val yIndex = row * width + column
                yPlane.put(row * planes[0].rowStride + column * planes[0].pixelStride, frame.y[yIndex])
                if (row % 2 == 0 && column % 2 == 0) {
                    val chromaRow = row / 2
                    val chromaColumn = column / 2
                    val chromaIndex = chromaRow * (width / 2) + chromaColumn
                    uPlane.put(chromaRow * planes[1].rowStride + chromaColumn * planes[1].pixelStride, frame.u[chromaIndex])
                    vPlane.put(chromaRow * planes[2].rowStride + chromaColumn * planes[2].pixelStride, frame.v[chromaIndex])
                }
            }
        }
    }

    private fun fitToOutput(source: Bitmap, width: Int, height: Int): Bitmap {
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        canvas.drawColor(android.graphics.Color.BLACK)
        val scale = min(width.toFloat() / source.width, height.toFloat() / source.height)
        val scaledWidth = source.width * scale
        val scaledHeight = source.height * scale
        val destination = RectF(
            (width - scaledWidth) / 2f,
            (height - scaledHeight) / 2f,
            (width + scaledWidth) / 2f,
            (height + scaledHeight) / 2f
        )
        canvas.drawBitmap(source, null, destination, Paint(Paint.FILTER_BITMAP_FLAG))
        return output
    }

    private fun rotateMovieFrame(source: Bitmap, rotationDegrees: Int): Bitmap {
        val normalized = ((rotationDegrees % 360) + 360) % 360
        if (normalized == 0) return source
        require(normalized % 90 == 0) { "Movie rotation must use 90-degree steps" }
        val matrix = android.graphics.Matrix().apply { postRotate(normalized.toFloat()) }
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }

    private fun orientedDimensions(frame: AnimationFrame): Pair<Int, Int>? {
        if (!frame.file.isFile) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(frame.file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val orientation = runCatching {
            ExifInterface(frame.file.absolutePath).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val rotated = orientation == ExifInterface.ORIENTATION_ROTATE_90 || orientation == ExifInterface.ORIENTATION_ROTATE_270 ||
            orientation == ExifInterface.ORIENTATION_TRANSPOSE || orientation == ExifInterface.ORIENTATION_TRANSVERSE
        return if (rotated) bounds.outHeight to bounds.outWidth else bounds.outWidth to bounds.outHeight
    }

    private fun uniqueFileName(context: Context, projectId: String?): String {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
        val moviePrefix = projectId?.let(ProjectExportIdentity::fileNamePrefix) ?: "AnimationWorkshop_"
        val resolver = context.contentResolver
        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        var suffix = 0
        while (true) {
            val candidate = if (suffix == 0) "${moviePrefix}${timestamp}.mp4" else "${moviePrefix}${timestamp}_${suffix.toString().padStart(2, '0')}.mp4"
            val exists = resolver.query(
                collection,
                arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?",
                arrayOf(candidate, "${Environment.DIRECTORY_MOVIES}/AnimationWorkshop/"),
                null
            )?.use { it.moveToFirst() } ?: false
            if (!exists) return candidate
            suffix++
        }
    }
}
