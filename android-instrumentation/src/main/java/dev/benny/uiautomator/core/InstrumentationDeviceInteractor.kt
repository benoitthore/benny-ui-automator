package dev.benny.uiautomator.core

import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import androidx.test.uiautomator.UiDevice
import kotlinx.coroutines.flow.Flow
import dev.benny.uiautomator.model.InteractionResult
import dev.benny.uiautomator.model.ScreenNode
import dev.benny.uiautomator.model.Selector
import dev.benny.uiautomator.model.SwipeDirection
import java.io.File
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class InstrumentationDeviceInteractor(
    val instrumentation: Instrumentation
) : DeviceInteractor {
    val device: UiDevice = UiDevice.getInstance(instrumentation)
    private val explorer = ScreenExplorer(device)
    private val logcatCapture = LogcatCapture()
    private val screenshotCapture = ScreenshotCapture(device)

    // App lifecycle

    override suspend fun launchApp(
        packageName: String,
        timeout: Duration
    ): InteractionResult {
        val context = instrumentation.context
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } else {
            Runtime.getRuntime().exec(
                arrayOf(
                    "monkey", "-p", packageName, "-c",
                    "android.intent.category.LAUNCHER", "1"
                )
            ).waitFor()
        }

        val deadline = System.currentTimeMillis() + timeout.inWholeMilliseconds
        while (System.currentTimeMillis() < deadline) {
            if (device.currentPackageName == packageName) {
                return InteractionResult.Success("App launched: $packageName")
            }
            kotlinx.coroutines.delay(250)
        }
        return InteractionResult.Error(
            IllegalStateException("Timeout waiting for $packageName to launch")
        )
    }

    override fun stopApp(packageName: String): InteractionResult = try {
        Runtime.getRuntime().exec(arrayOf("am", "force-stop", packageName)).waitFor()
        InteractionResult.Success("App stopped: $packageName")
    } catch (e: Exception) {
        InteractionResult.Error(e)
    }

    override fun isAppRunning(packageName: String): Boolean =
        device.currentPackageName == packageName

    // Screen exploration

    override fun dumpScreen(): ScreenNode = explorer.dumpScreen()

    override fun printScreen() {
        val screen = dumpScreen()
        println(screen.prettyPrint())
    }

    // Waiting (explicit, with Duration)

    override suspend fun waitFor(selector: Selector, timeout: Duration): Boolean =
        explorer.waitForElement(selector, timeout)

    override suspend fun waitForIdle(timeout: Duration) {
        device.waitForIdle(timeout.inWholeMilliseconds)
    }

    override suspend fun delay(duration: Duration) {
        kotlinx.coroutines.delay(duration)
    }

    // Contains checks (live, using Selector)

    override fun screenContains(selector: Selector): Boolean =
        explorer.findUiObject(selector) != null

    // Interactions (immediate, no timeout)
    // All selector-based interactions resolve the element's visible bounds and use
    // coordinate-based touch injection (device.click / device.swipe) so that
    // View.OnTouchListener, View.OnClickListener, and other real touch handlers fire.

    override fun click(selector: Selector): InteractionResult {
        val obj = explorer.findUiObject(selector)
            ?: return InteractionResult.ElementNotFound(selector)
        return try {
            val bounds = obj.visibleBounds
            device.click(bounds.centerX(), bounds.centerY())
            InteractionResult.Success("Clicked: $selector")
        } catch (e: Exception) {
            InteractionResult.Error(e)
        }
    }

    override fun clickAt(x: Int, y: Int) {
        device.click(x, y)
    }

    override fun longClick(selector: Selector): InteractionResult {
        val obj = explorer.findUiObject(selector)
            ?: return InteractionResult.ElementNotFound(selector)
        return try {
            val bounds = obj.visibleBounds
            val x = bounds.centerX()
            val y = bounds.centerY()
            // Swipe from same point to same point over 150 steps (~750ms) to trigger long press
            device.swipe(x, y, x, y, 150)
            InteractionResult.Success("Long clicked: $selector")
        } catch (e: Exception) {
            InteractionResult.Error(e)
        }
    }

    override fun typeText(selector: Selector, text: String): InteractionResult {
        val obj = explorer.findUiObject(selector)
            ?: return InteractionResult.ElementNotFound(selector)
        return try {
            val bounds = obj.visibleBounds
            device.click(bounds.centerX(), bounds.centerY())
            obj.text = text
            InteractionResult.Success("Typed text: $text")
        } catch (e: Exception) {
            InteractionResult.Error(e)
        }
    }

    override fun clearAndType(selector: Selector, text: String): InteractionResult {
        val obj = explorer.findUiObject(selector)
            ?: return InteractionResult.ElementNotFound(selector)
        return try {
            val bounds = obj.visibleBounds
            device.click(bounds.centerX(), bounds.centerY())
            obj.clear()
            obj.text = text
            InteractionResult.Success("Cleared and typed: $text")
        } catch (e: Exception) {
            InteractionResult.Error(e)
        }
    }

    override fun swipe(direction: SwipeDirection, steps: Int): InteractionResult = try {
        val w = device.displayWidth
        val h = device.displayHeight
        val cx = w / 2
        val cy = h / 2
        val margin = 100
        when (direction) {
            SwipeDirection.UP -> device.swipe(cx, h - margin, cx, margin, steps)
            SwipeDirection.DOWN -> device.swipe(cx, margin, cx, h - margin, steps)
            SwipeDirection.LEFT -> device.swipe(w - margin, cy, margin, cy, steps)
            SwipeDirection.RIGHT -> device.swipe(margin, cy, w - margin, cy, steps)
        }
        InteractionResult.Success("Swiped: $direction")
    } catch (e: Exception) {
        InteractionResult.Error(e)
    }

    // Scroll + find

    override suspend fun scrollUntilFound(
        selector: Selector,
        direction: SwipeDirection,
        maxScrolls: Int,
        delayBetween: Duration
    ): InteractionResult {
        repeat(maxScrolls) {
            if (screenContains(selector)) {
                return InteractionResult.Success("Found after scrolling: $selector")
            }
            swipe(direction)
            kotlinx.coroutines.delay(delayBetween)
        }
        // Check one more time after the last scroll
        return if (screenContains(selector)) {
            InteractionResult.Success("Found after scrolling: $selector")
        } else {
            InteractionResult.ElementNotFound(selector)
        }
    }

    // Device buttons

    override fun pressBack() {
        device.pressBack()
    }

    override fun pressHome() {
        device.pressHome()
    }

    override fun pressRecentApps() {
        device.pressRecentApps()
    }

    override fun pressKeyEvent(keyCode: Int) {
        Runtime.getRuntime().exec(arrayOf("input", "keyevent", "$keyCode")).waitFor()
    }

    override fun inputRawText(text: String) {
        Runtime.getRuntime().exec(arrayOf("input", "text", text)).waitFor()
    }

    // Drag

    override fun drag(fromX: Int, fromY: Int, toX: Int, toY: Int, durationMs: Int) {
        val steps = (durationMs / 5).coerceAtLeast(1)
        device.swipe(fromX, fromY, toX, toY, steps)
    }

    // System controls (shell commands from on-device instrumentation)

    private fun shell(vararg args: String): String {
        val process = Runtime.getRuntime().exec(args)
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor()
        return output
    }

    override fun setWifiEnabled(enabled: Boolean) {
        shell("svc", "wifi", if (enabled) "enable" else "disable")
    }

    override fun setBluetoothEnabled(enabled: Boolean) {
        shell("cmd", "bluetooth_manager", if (enabled) "enable" else "disable")
    }

    override fun setAirplaneModeEnabled(enabled: Boolean) {
        val value = if (enabled) "1" else "0"
        shell("settings", "put", "global", "airplane_mode_on", value)
        shell("am", "broadcast", "-a", "android.intent.action.AIRPLANE_MODE", "--ez", "state", enabled.toString())
    }

    override fun setScreenRotation(mode: String) {
        when (mode) {
            "auto" -> shell("settings", "put", "system", "accelerometer_rotation", "1")
            else -> {
                shell("settings", "put", "system", "accelerometer_rotation", "0")
                val rotation = when (mode) {
                    "portrait" -> "0"; "landscape" -> "1"
                    "reverse_portrait" -> "2"; "reverse_landscape" -> "3"
                    else -> "0"
                }
                shell("settings", "put", "system", "user_rotation", rotation)
            }
        }
    }

    override fun wakeScreen() { device.wakeUp() }
    override fun sleepScreen() { device.sleep() }
    override fun lockScreen() { shell("input", "keyevent", "KEYCODE_POWER") }

    override fun setVolume(stream: String, level: Int) {
        val streamIndex = when (stream) {
            "music" -> "3"; "ring" -> "2"; "notification" -> "5"; "alarm" -> "4"; "system" -> "1"; else -> "3"
        }
        shell("media", "volume", "--stream", streamIndex, "--set", level.toString())
    }

    override fun setBrightness(level: Int) {
        val clamped = level.coerceIn(0, 255)
        shell("settings", "put", "system", "screen_brightness_mode", "0")
        shell("settings", "put", "system", "screen_brightness", clamped.toString())
    }

    override fun reboot(mode: String?) {
        if (mode != null) shell("reboot", mode) else shell("reboot")
    }

    // App management

    override fun installApp(apkPath: String): InteractionResult = try {
        shell("pm", "install", "-r", apkPath)
        InteractionResult.Success("Installed: $apkPath")
    } catch (e: Exception) { InteractionResult.Error(e) }

    override fun uninstallApp(packageName: String): InteractionResult = try {
        shell("pm", "uninstall", packageName)
        InteractionResult.Success("Uninstalled: $packageName")
    } catch (e: Exception) { InteractionResult.Error(e) }

    override fun listPackages(filter: String?): List<String> {
        val output = if (filter != null) shell("pm", "list", "packages", filter)
        else shell("pm", "list", "packages")
        return output.lines().filter { it.startsWith("package:") }.map { it.removePrefix("package:").trim() }.sorted()
    }

    override fun openUrl(url: String): InteractionResult = try {
        shell("am", "start", "-a", "android.intent.action.VIEW", "-d", url)
        InteractionResult.Success("Opened URL: $url")
    } catch (e: Exception) { InteractionResult.Error(e) }

    override fun grantPermission(packageName: String, permission: String) {
        shell("pm", "grant", packageName, permission)
    }

    override fun revokePermission(packageName: String, permission: String) {
        shell("pm", "revoke", packageName, permission)
    }

    override fun clearAppData(packageName: String): InteractionResult = try {
        shell("pm", "clear", packageName)
        InteractionResult.Success("Cleared data: $packageName")
    } catch (e: Exception) { InteractionResult.Error(e) }

    // Clipboard

    override fun getClipboard(): String = shell("am", "broadcast", "-a", "clipper.get").trim()
    override fun setClipboard(text: String) { shell("am", "broadcast", "-a", "clipper.set", "-e", "text", text) }

    // Notifications

    override fun openNotifications() { shell("cmd", "statusbar", "expand-notifications") }
    override fun openQuickSettings() { shell("cmd", "statusbar", "expand-settings") }

    // File management

    override fun pushFile(localPath: String, remotePath: String): InteractionResult =
        InteractionResult.Error(UnsupportedOperationException("pushFile not available from instrumentation"))

    override fun pullFile(remotePath: String, localPath: String): InteractionResult =
        InteractionResult.Error(UnsupportedOperationException("pullFile not available from instrumentation"))

    // Device info

    override fun getBatteryInfo(): String = shell("dumpsys", "battery")
    override fun getConnectivityInfo(): String {
        val wifi = shell("dumpsys", "wifi").lines().take(20).joinToString("\n")
        val net = shell("dumpsys", "connectivity").lines().take(30).joinToString("\n")
        return "=== WiFi ===\n$wifi\n\n=== Connectivity ===\n$net"
    }

    // Terminal output

    override fun terminalPrint(msg: String) {
        val bundle = Bundle()
        bundle.putString(Instrumentation.REPORT_KEY_STREAMRESULT, "\n$msg")
        instrumentation.sendStatus(0, bundle)
    }

    // Debug

    override fun logcat(): Flow<String> = logcatCapture.stream()

    override fun logcatDump(lines: Int): String = logcatCapture.capture(lines)

    override fun logcatClear() = logcatCapture.clear()

    override fun takeScreenshot(name: String): File? = screenshotCapture.take(name)
}
