package com.personal.bengaliagent.media

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer

/**
 * Lightweight, non-destructive video processor using Android's built-in MediaExtractor + MediaMuxer.
 * - Uses a 64 KB direct ByteBuffer so even 1 GB videos never exhaust RAM on a 2 GB device.
 * - Preserves the original source URI untouched and writes to a user-selected destination URI.
 * - Verifies that the output file exists and has > 0 bytes before reporting success.
 */
object VideoEditorProcessor {

    private const val STREAM_BUFFER_BYTES = 64 * 1024 // 64 KB bounded buffer for 2 GB RAM devices

    data class VideoMetadata(
        val durationMs: Long,
        val width: Int,
        val height: Int,
        val rotationDegrees: Int,
        val hasAudio: Boolean
    )

    data class EditConfig(
        val startMs: Long,
        val endMs: Long,
        val muteAudio: Boolean = false,
        val audioOnly: Boolean = false,
        val extraRotationDegrees: Int = 0
    )

    data class EditOutcome(
        val success: Boolean,
        val outputBytes: Long,
        val messageBn: String
    )

    suspend fun inspectVideo(context: Context, sourceUri: Uri): VideoMetadata? =
        withContext(Dispatchers.IO) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, sourceUri)
                val duration = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_DURATION
                )?.toLongOrNull() ?: 0L
                val width = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH
                )?.toIntOrNull() ?: 0
                val height = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT
                )?.toIntOrNull() ?: 0
                val rotation = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION
                )?.toIntOrNull() ?: 0
                val hasAudio = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO
                ) != null
                VideoMetadata(duration, width, height, rotation, hasAudio)
            } catch (e: Exception) {
                null
            } finally {
                try {
                    retriever.release()
                } catch (_: Exception) {
                }
            }
        }

    suspend fun extractPreviewFrame(
        context: Context,
        sourceUri: Uri,
        timeMs: Long = 1000L
    ): Bitmap? = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, sourceUri)
            val raw = retriever.getFrameAtTime(
                timeMs * 1000L,
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC
            ) ?: return@withContext null
            // Downscale preview bitmap to save memory
            val maxDim = 480
            if (raw.width > maxDim || raw.height > maxDim) {
                val scale = maxDim.toFloat() / maxOf(raw.width, raw.height).toFloat()
                val w = (raw.width * scale).toInt().coerceAtLeast(120)
                val h = (raw.height * scale).toInt().coerceAtLeast(120)
                Bitmap.createScaledBitmap(raw, w, h, true).also {
                    if (it != raw) raw.recycle()
                }
            } else {
                raw
            }
        } catch (e: Exception) {
            null
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
            }
        }
    }

    @SuppressLint("WrongConstant")
    suspend fun processVideoNonDestructive(
        context: Context,
        sourceUri: Uri,
        destUri: Uri,
        config: EditConfig,
        isCancelled: () -> Boolean,
        onProgressPct: (Int) -> Unit
    ): EditOutcome = withContext(Dispatchers.IO) {
        if (config.endMs <= config.startMs) {
            return@withContext EditOutcome(
                false,
                0L,
                "শেষের সময় শুরুর সময়ের চেয়ে বেশি হতে হবে।"
            )
        }

        val tempOutFile = File(context.cacheDir, "edited_${System.currentTimeMillis()}.mp4")
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null

        try {
            context.contentResolver.openFileDescriptor(sourceUri, "r")?.use { pfd ->
                extractor.setDataSource(pfd.fileDescriptor)
            } ?: return@withContext EditOutcome(false, 0L, "মূল ভিডিও ফাইলটি পড়া যায়নি।")

            muxer = MediaMuxer(
                tempOutFile.absolutePath,
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
            )

            val trackIndexMap = HashMap<Int, Int>()
            var totalRotation = config.extraRotationDegrees

            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                val isVideo = mime.startsWith("video/")
                val isAudio = mime.startsWith("audio/")

                if (isVideo && !config.audioOnly) {
                    extractor.selectTrack(i)
                    val dstIdx = muxer.addTrack(format)
                    trackIndexMap[i] = dstIdx
                    if (format.containsKey(MediaFormat.KEY_ROTATION)) {
                        totalRotation = (totalRotation + format.getInteger(MediaFormat.KEY_ROTATION)) % 360
                    }
                } else if (isAudio && (!config.muteAudio || config.audioOnly)) {
                    extractor.selectTrack(i)
                    val dstIdx = muxer.addTrack(format)
                    trackIndexMap[i] = dstIdx
                }
            }

            if (trackIndexMap.isEmpty()) {
                return@withContext EditOutcome(
                    false,
                    0L,
                    "নির্বাচিত অপশনের জন্য কোনো বৈধ অডিও বা ভিডিও ট্র্যাক পাওয়া যায়নি।"
                )
            }

            if (!config.audioOnly && totalRotation in listOf(0, 90, 180, 270)) {
                muxer.setOrientationHint(totalRotation)
            }

            val startUs = config.startMs * 1000L
            val endUs = config.endMs * 1000L
            val durationSpanUs = (endUs - startUs).coerceAtLeast(1L)

            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            muxer.start()

            val buffer = ByteBuffer.allocateDirect(STREAM_BUFFER_BYTES)
            val bufferInfo = MediaCodec.BufferInfo()

            while (true) {
                if (isCancelled()) {
                    tempOutFile.delete()
                    return@withContext EditOutcome(false, 0L, "ভিডিও এডিটিং বাতিল করা হয়েছে।")
                }

                bufferInfo.offset = 0
                bufferInfo.size = extractor.readSampleData(buffer, 0)
                if (bufferInfo.size < 0) break

                val sampleTimeUs = extractor.sampleTime
                if (sampleTimeUs > endUs) break

                val srcTrack = extractor.sampleTrackIndex
                val dstTrack = trackIndexMap[srcTrack]
                if (dstTrack != null && sampleTimeUs >= startUs) {
                    bufferInfo.presentationTimeUs = (sampleTimeUs - startUs).coerceAtLeast(0L)
                    bufferInfo.flags = extractor.sampleFlags
                    muxer.writeSampleData(dstTrack, buffer, bufferInfo)

                    val pct = (((sampleTimeUs - startUs) * 100L) / durationSpanUs)
                        .toInt().coerceIn(0, 99)
                    onProgressPct(pct)
                }
                extractor.advance()
            }

            muxer.stop()
            muxer.release()
            muxer = null

            if (!tempOutFile.exists() || tempOutFile.length() == 0L) {
                tempOutFile.delete()
                return@withContext EditOutcome(false, 0L, "আউটপুট ভিডিও তৈরি করা সম্ভব হয়নি।")
            }

            // Copy verified output to the user-selected SAF destination URI
            var copiedBytes = 0L
            context.contentResolver.openOutputStream(destUri, "w")?.use { outStream ->
                FileInputStream(tempOutFile).use { inStream ->
                    copiedBytes = inStream.copyTo(outStream, bufferSize = 32 * 1024)
                }
            }

            tempOutFile.delete()

            if (copiedBytes <= 0L) {
                return@withContext EditOutcome(
                    false,
                    0L,
                    "গন্তব্য ফাইলে সংরক্ষণ করা যায়নি।"
                )
            }

            onProgressPct(100)
            EditOutcome(
                success = true,
                outputBytes = copiedBytes,
                messageBn = "সফলভাবে নতুন ফাইলে এক্সপোর্ট হয়েছে (${copiedBytes / 1024} KB)। মূল ভিডিওটি অক্ষত আছে।"
            )
        } catch (e: Exception) {
            tempOutFile.delete()
            EditOutcome(
                success = false,
                outputBytes = 0L,
                messageBn = "ভিডিও প্রসেসিং ত্রুটি: ${e.message}"
            )
        } finally {
            try {
                extractor.release()
            } catch (_: Exception) {
            }
            try {
                muxer?.release()
            } catch (_: Exception) {
            }
        }
    }
}
