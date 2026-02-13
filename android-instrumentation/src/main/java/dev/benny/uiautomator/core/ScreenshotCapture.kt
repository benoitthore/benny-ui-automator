package dev.benny.uiautomator.core

import android.os.Environment
import android.util.Log
import androidx.test.uiautomator.UiDevice
import java.io.File

internal class ScreenshotCapture(private val device: UiDevice) {

    fun take(name: String = "screenshot"): File? = try {
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            "device-automator"
        )
        dir.mkdirs()
        val file = File(dir, "${name}_${System.currentTimeMillis()}.png")
        device.takeScreenshot(file)
        file
    } catch (e: Exception) {
        Log.w(TAG, "Could not take screenshot: ${e.message}")
        null
    }

    companion object {
        private const val TAG = "ScreenshotCapture"
    }
}
