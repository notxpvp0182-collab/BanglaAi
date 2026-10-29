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
import java.io.ByteArrayOutputStream

/**
 * Manages official MediaProjection screen sharing:
 * - Starts only after explicit user consent via MediaProjectionManager.
 * - Downscales frames to max 720px width and throttles capture (min 3 seconds between frames)
 *   to keep RAM low (<10MB buffer) and avoid continuous frame uploads.
 * - Immediately stops and releases resources when stopped or revoked.
 */
object ScreenCaptureManager {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var lastCaptureTimeMs: Long = 0L
    private var cachedBase64Jpeg: String? = null

    private const val MIN_CAPTURE_INTERVAL_MS = 3000L
    private const val MAX_WIDTH = 720

    var onProjectionStoppedListener: (() -> Unit)? = null

    fun isSharingActive(): Boolean = mediaProjection != null && imageReader != null

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
                2 // Max 2 images in buffer for 2 GB RAM target
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
     * Captures a single downscaled JPEG frame (Base64) on demand, respecting the 3-second throttle.
     * Returns null if projection is inactive or if a secure window blocks capture.
     */
    @Synchronized
    fun captureThrottledFrameBase64(): String? {
        val reader = imageReader ?: return null
        val now = System.currentTimeMillis()
        if (now - lastCaptureTimeMs < MIN_CAPTURE_INTERVAL_MS && cachedBase64Jpeg != null) {
            return cachedBase64Jpeg
        }

        val image = try {
            reader.acquireLatestImage()
        } catch (e: Exception) {
            null
        } ?: return cachedBase64Jpeg

        try {
            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * image.width

            val bitmapWidth = image.width + rowPadding / pixelStride
            val rawBitmap = Bitmap.createBitmap(
                bitmapWidth,
                image.height,
                Bitmap.Config.ARGB_8888
            )
            rawBitmap.copyPixelsFromBuffer(buffer)

            val cropped = if (bitmapWidth != image.width) {
                Bitmap.createBitmap(rawBitmap, 0, 0, image.width, image.height).also {
                    rawBitmap.recycle()
                }
            } else {
                rawBitmap
            }

            val out = ByteArrayOutputStream()
            cropped.compress(Bitmap.CompressFormat.JPEG, 65, out)
            cropped.recycle()

            val encoded = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
            cachedBase64Jpeg = encoded
            lastCaptureTimeMs = now
            return encoded
        } catch (e: Exception) {
            return null
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
