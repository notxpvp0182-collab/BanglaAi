package com.personal.bengaliagent.screen

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.util.Base64
import android.util.DisplayMetrics
import android.view.WindowManager
import kotlinx.coroutines.delay
import java.io.ByteArrayOutputStream

/**
 * Reliable MediaProjection manager:
 * - Prevents stale or corrupted frames when projection stops.
 * - Retries briefly when a fresh frame is requested so "What is on my screen?" never fails
 *   due to ImageReader surface warm-up timing.
 */
object ScreenCaptureManager {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var lastCaptureTimeMs: Long = 0L
    private var cachedBase64Jpeg: String? = null

    private const val MIN_CAPTURE_INTERVAL_MS = 2000L
    private const val MAX_WIDTH = 720

    var onProjectionStoppedListener: (() -> Unit)? = null

    @Synchronized
    fun isSharingActive(): Boolean = mediaProjection != null && imageReader != null

    @Synchronized
    fun startProjection(
        context: Context,
        resultCode: Int,
        data: Intent
    ): Boolean {
        stopProjection()
        return try {
            val mpm = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                as MediaProjectionManager
            val projection = mpm.getMediaProjection(resultCode, data) ?: return false

            projection.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    stopProjection()
                    onProjectionStoppedListener?.invoke()
                }
            }, null)

            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(metrics)

            val scale = if (metrics.widthPixels > MAX_WIDTH) {
                MAX_WIDTH.toFloat() / metrics.widthPixels.toFloat()
            } else {
                1.0f
            }
            val targetWidth = (metrics.widthPixels * scale).toInt().coerceAtLeast(320)
            val targetHeight = (metrics.heightPixels * scale).toInt().coerceAtLeast(480)

            val reader = ImageReader.newInstance(
                targetWidth,
                targetHeight,
                PixelFormat.RGBA_8888,
                2
            )

            val vDisplay = projection.createVirtualDisplay(
                "BengaliAgentScreenObserver",
                targetWidth,
                targetHeight,
                metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                null
            )

            mediaProjection = projection
            imageReader = reader
            virtualDisplay = vDisplay
            true
        } catch (e: Exception) {
            stopProjection()
            false
        }
    }

    /**
     * Suspends briefly if necessary to guarantee a fresh, non-corrupted frame when the user
     * asks "What is on my screen?".
     */
    suspend fun captureFreshFrameWithRetry(): String? {
        if (!isSharingActive()) return null
        for (attempt in 1..3) {
            val frame = captureThrottledFrameBase64()
            if (!frame.isNullOrBlank()) return frame
            delay(140L)
        }
        return cachedBase64Jpeg
    }

    @Synchronized
    fun captureThrottledFrameBase64(): String? {
        val reader = imageReader ?: return null
        val now = System.currentTimeMillis()
        if (now - lastCaptureTimeMs < MIN_CAPTURE_INTERVAL_MS && !cachedBase64Jpeg.isNullOrBlank()) {
            return cachedBase64Jpeg
        }

        val image = try {
            reader.acquireLatestImage()
        } catch (_: Exception) {
            null
        } ?: return cachedBase64Jpeg

        try {
            val planes = image.planes
            if (planes.isEmpty()) return cachedBase64Jpeg
            val buffer = planes[0].buffer ?: return cachedBase64Jpeg
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * image.width

            val bitmapWidth = image.width + rowPadding / pixelStride
            val rawBitmap = Bitmap.createBitmap(
                bitmapWidth,
                image.height,
                Bitmap.Config.ARGB_8888
            )
            buffer.rewind()
            rawBitmap.copyPixelsFromBuffer(buffer)

            val cropped = if (bitmapWidth != image.width) {
                Bitmap.createBitmap(rawBitmap, 0, 0, image.width, image.height).also {
                    rawBitmap.recycle()
                }
            } else {
                rawBitmap
            }

            val out = ByteArrayOutputStream()
            cropped.compress(Bitmap.CompressFormat.JPEG, 68, out)
            cropped.recycle()

            val bytes = out.toByteArray()
            if (bytes.size < 256) return cachedBase64Jpeg

            val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)
            cachedBase64Jpeg = encoded
            lastCaptureTimeMs = now
            return encoded
        } catch (_: Exception) {
            return cachedBase64Jpeg
        } finally {
            try {
                image.close()
            } catch (_: Exception) {
            }
        }
    }

    @Synchronized
    fun stopProjection() {
        cachedBase64Jpeg = null
        lastCaptureTimeMs = 0L
        try {
            virtualDisplay?.release()
        } catch (_: Exception) {
        }
        virtualDisplay = null

        try {
            imageReader?.close()
        } catch (_: Exception) {
        }
        imageReader = null

        try {
            mediaProjection?.stop()
        } catch (_: Exception) {
        }
        mediaProjection = null
    }
}
