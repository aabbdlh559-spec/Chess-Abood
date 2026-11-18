package com.abood.chessassistant.capture

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager

class ScreenCaptureManager(
    private val context: Context,
    private val onProjectionStopped: () -> Unit
) {
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    var screenWidth = 1080
        private set
    var screenHeight = 2400
        private set
    private var screenDensity = 420

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            release()
            onProjectionStopped()
        }
    }

    fun start(resultCode: Int, resultData: Intent): Boolean {
        val mgr = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = mgr.getMediaProjection(resultCode, resultData) ?: return false

        val dm = context.resources.displayMetrics
        screenDensity = dm.densityDpi
        screenWidth = dm.widthPixels
        screenHeight = dm.heightPixels
        try {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bounds = wm.maximumWindowMetrics.bounds
                screenWidth = bounds.width()
                screenHeight = bounds.height()
            } else {
                val metrics = DisplayMetrics()
                @Suppress("DEPRECATION")
                wm.defaultDisplay.getRealMetrics(metrics)
                screenWidth = metrics.widthPixels
                screenHeight = metrics.heightPixels
            }
        } catch (_: Exception) {
        }

        projection.registerCallback(projectionCallback, Handler(Looper.getMainLooper()))

        val reader = ImageReader.newInstance(
            screenWidth,
            screenHeight,
            PixelFormat.RGBA_8888,
            2
        )

        val display = projection.createVirtualDisplay(
            "ChessAboodCapture",
            screenWidth,
            screenHeight,
            screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            Handler(Looper.getMainLooper())
        )

        mediaProjection = projection
        imageReader = reader
        virtualDisplay = display
        return true
    }

    fun captureFrame(): Bitmap? {
        val reader = imageReader ?: return null
        val image = reader.acquireLatestImage() ?: return null
        return try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * screenWidth
            val paddedWidth = screenWidth + rowPadding / pixelStride

            val rawBmp = Bitmap.createBitmap(paddedWidth, screenHeight, Bitmap.Config.ARGB_8888)
            rawBmp.copyPixelsFromBuffer(buffer)
            if (paddedWidth != screenWidth) {
                val cropped = Bitmap.createBitmap(rawBmp, 0, 0, screenWidth, screenHeight)
                rawBmp.recycle()
                cropped
            } else {
                rawBmp
            }
        } catch (_: Exception) {
            null
        } finally {
            image.close()
        }
    }

    fun release() {
        try {
            virtualDisplay?.release()
            imageReader?.close()
            mediaProjection?.unregisterCallback(projectionCallback)
            mediaProjection?.stop()
        } catch (_: Exception) {
        }
        virtualDisplay = null
        imageReader = null
        mediaProjection = null
    }
}
