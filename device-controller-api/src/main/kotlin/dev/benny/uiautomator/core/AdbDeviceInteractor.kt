package dev.benny.uiautomator.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import dev.benny.uiautomator.model.Bounds
import dev.benny.uiautomator.model.InteractionResult
import dev.benny.uiautomator.model.ScreenNode
import dev.benny.uiautomator.model.Selector
import dev.benny.uiautomator.model.SwipeDirection
import java.io.File
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class AdbDeviceInteractor(
    private val adbPath: String = "adb",
    private val deviceSerial: String? = null
) : DeviceInteractor {

    private var displayWidth: Int = 0
    private var displayHeight: Int = 0

    private fun ensureDisplaySize() {
        if (displayWidth > 0 && displayHeight > 0) return
        val output = adb("shell", "wm", "size")
        // Output: "Physical size: 1080x2400"
        val match = Regex("""(\d+)x(\d+)""").find(output)
        if (match != null) {
            displayWidth = match.groupValues[1].toInt()
            displayHeight = match.groupValues[2].toInt()
        } else {
            throw IllegalStateException("Could not determine display size from output: $output")
        }
    }

    fun getDisplaySize(): Pair<Int, Int> {
        ensureDisplaySize()
        return displayWidth to displayHeight
    }

    fun getCurrentPackage(): String? {
        val output = adb("shell", "dumpsys", "activity", "activities")
        val match = Regex("""(?:mResumedActivity|topResumedActivity).*?([a-zA-Z][a-zA-Z0-9_.]*)/""").find(output)
        return match?.groupValues?.get(1)
    }

    fun getDisplayDensity(): Int {
        val output = adb("shell", "wm", "density")
        val match = Regex("""(\d+)""").find(output)
        return match?.groupValues?.get(1)?.toInt() ?: 0
    }

    // App lifecycle

    override suspend fun launchApp(packageName: String, timeout: Duration): InteractionResult {
        adb("shell", "monkey", "-p", packageName, "-c", "android.intent.category.LAUNCHER", "1")

        val deadline = System.currentTimeMillis() + timeout.inWholeMilliseconds
        while (System.currentTimeMillis() < deadline) {
            if (isAppRunning(packageName)) {
                return InteractionResult.Success("App launched: $packageName")
            }
            kotlinx.coroutines.delay(250)
        }
        return InteractionResult.Error(
            IllegalStateException("Timeout waiting for $packageName to launch")
        )
    }

    override fun stopApp(packageName: String): InteractionResult = try {
        adb("shell", "am", "force-stop", packageName)
        InteractionResult.Success("App stopped: $packageName")
    } catch (e: Exception) {
        InteractionResult.Error(e)
    }

    override fun isAppRunning(packageName: String): Boolean {
        val output = adb("shell", "dumpsys", "activity", "activities")
        // Check both old and new Android API field names
        val hasResumed = output.contains("mResumedActivity") || output.contains("topResumedActivity")
        return hasResumed && output.contains(packageName)
    }

    // Screen

    override fun dumpScreen(): ScreenNode {
        val remotePath = "/sdcard/window_dump.xml"
        adb("shell", "uiautomator", "dump", remotePath)
        val xml = adb("shell", "cat", remotePath)
        val cleaned = xml.trim()
        return if (cleaned.isNotEmpty() && (cleaned.startsWith("<?xml") || cleaned.startsWith("<hierarchy"))) {
            UiHierarchyParser.parse(cleaned)
        } else {
            // Return empty root node
            ScreenNode(
                className = "root",
                nodeType = dev.benny.uiautomator.model.NodeType.CONTAINER,
                resourceId = null, text = null, contentDescription = null,
                isClickable = false, isScrollable = false, isCheckable = false,
                isChecked = false, isEnabled = true, isFocusable = false,
                bounds = null, children = emptyList(), depth = 0
            )
        }
    }

    override fun screenContains(selector: Selector): Boolean =
        dumpScreen().contains(selector)

    // Waiting

    override suspend fun waitFor(selector: Selector, timeout: Duration): Boolean {
        val deadline = System.currentTimeMillis() + timeout.inWholeMilliseconds
        while (System.currentTimeMillis() < deadline) {
            if (dumpScreen().contains(selector)) return true
            kotlinx.coroutines.delay(250)
        }
        return false
    }

    override suspend fun waitForIdle(timeout: Duration) {
        // ADB has no direct idle detection; use a short delay as approximation
        kotlinx.coroutines.delay(timeout.inWholeMilliseconds.coerceAtMost(1000))
    }

    // Interactions

    override fun click(selector: Selector): InteractionResult {
        val node = findNode(selector) ?: return InteractionResult.ElementNotFound(selector)
        val bounds = node.bounds ?: return InteractionResult.Error(
            IllegalStateException("Node has no bounds: $selector")
        )
        return try {
            adb("shell", "input", "tap", bounds.centerX().toString(), bounds.centerY().toString())
            InteractionResult.Success("Clicked: $selector")
        } catch (e: Exception) {
            InteractionResult.Error(e)
        }
    }

    override fun clickAt(x: Int, y: Int) {
        adb("shell", "input", "tap", x.toString(), y.toString())
    }

    override fun longClick(selector: Selector): InteractionResult {
        val node = findNode(selector) ?: return InteractionResult.ElementNotFound(selector)
        val bounds = node.bounds ?: return InteractionResult.Error(
            IllegalStateException("Node has no bounds: $selector")
        )
        return try {
            val x = bounds.centerX().toString()
            val y = bounds.centerY().toString()
            adb("shell", "input", "swipe", x, y, x, y, "750")
            InteractionResult.Success("Long clicked: $selector")
        } catch (e: Exception) {
            InteractionResult.Error(e)
        }
    }

    override fun typeText(selector: Selector, text: String): InteractionResult {
        val node = findNode(selector) ?: return InteractionResult.ElementNotFound(selector)
        val bounds = node.bounds ?: return InteractionResult.Error(
            IllegalStateException("Node has no bounds: $selector")
        )
        return try {
            adb("shell", "input", "tap", bounds.centerX().toString(), bounds.centerY().toString())
            Thread.sleep(200)
            inputText(text)
            InteractionResult.Success("Typed text: $text")
        } catch (e: Exception) {
            InteractionResult.Error(e)
        }
    }

    override fun clearAndType(selector: Selector, text: String): InteractionResult {
        val node = findNode(selector) ?: return InteractionResult.ElementNotFound(selector)
        val bounds = node.bounds ?: return InteractionResult.Error(
            IllegalStateException("Node has no bounds: $selector")
        )
        return try {
            val cx = bounds.centerX().toString()
            val cy = bounds.centerY().toString()
            // Triple-tap to select all text in the field
            adb("shell", "input", "tap", cx, cy)
            Thread.sleep(50)
            adb("shell", "input", "tap", cx, cy)
            Thread.sleep(50)
            adb("shell", "input", "tap", cx, cy)
            Thread.sleep(200)
            // Delete the selection (covers selected text; if nothing selected, harmless)
            adb("shell", "input", "keyevent", "KEYCODE_DEL")
            Thread.sleep(100)
            // Fallback: if triple-tap didn't select everything, brute-force delete remaining
            // Move to end then delete back based on known text length (batched in one shell call)
            val existingLen = node.text?.length ?: 0
            if (existingLen > 0) {
                val delKeys = (listOf("KEYCODE_MOVE_END") + List(existingLen) { "KEYCODE_DEL" })
                    .joinToString(" ")
                adb("shell", "input", "keyevent", *delKeys.split(" ").toTypedArray())
                Thread.sleep(100)
            }
            inputText(text)
            InteractionResult.Success("Cleared and typed: $text")
        } catch (e: Exception) {
            InteractionResult.Error(e)
        }
    }

    override fun swipe(direction: SwipeDirection, steps: Int): InteractionResult = try {
        ensureDisplaySize()
        val w = displayWidth
        val h = displayHeight
        val cx = w / 2
        val cy = h / 2
        val margin = 100
        // Duration in ms, proportional to steps (each step ≈ 5ms in UiAutomator)
        val duration = (steps * 5).toString()
        val (x1, y1, x2, y2) = when (direction) {
            SwipeDirection.UP -> listOf(cx, h - margin, cx, margin)
            SwipeDirection.DOWN -> listOf(cx, margin, cx, h - margin)
            SwipeDirection.LEFT -> listOf(w - margin, cy, margin, cy)
            SwipeDirection.RIGHT -> listOf(margin, cy, w - margin, cy)
        }
        adb("shell", "input", "swipe",
            x1.toString(), y1.toString(), x2.toString(), y2.toString(), duration)
        InteractionResult.Success("Swiped: $direction")
    } catch (e: Exception) {
        InteractionResult.Error(e)
    }

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
        return if (screenContains(selector)) {
            InteractionResult.Success("Found after scrolling: $selector")
        } else {
            InteractionResult.ElementNotFound(selector)
        }
    }

    // Buttons

    override fun pressBack() {
        adb("shell", "input", "keyevent", "KEYCODE_BACK")
    }

    override fun pressHome() {
        adb("shell", "input", "keyevent", "KEYCODE_HOME")
    }

    override fun pressRecentApps() {
        adb("shell", "input", "keyevent", "KEYCODE_APP_SWITCH")
    }

    override fun pressKeyEvent(keyCode: Int) {
        adb("shell", "input", "keyevent", keyCode.toString())
    }

    override fun inputRawText(text: String) {
        inputText(text)
    }

    // Drag

    override fun drag(fromX: Int, fromY: Int, toX: Int, toY: Int, durationMs: Int) {
        adb("shell", "input", "swipe",
            fromX.toString(), fromY.toString(), toX.toString(), toY.toString(), durationMs.toString())
    }

    // System controls

    override fun setWifiEnabled(enabled: Boolean) {
        adb("shell", "svc", "wifi", if (enabled) "enable" else "disable")
    }

    override fun setBluetoothEnabled(enabled: Boolean) {
        if (enabled) {
            adb("shell", "cmd", "bluetooth_manager", "enable")
        } else {
            adb("shell", "cmd", "bluetooth_manager", "disable")
        }
    }

    override fun setAirplaneModeEnabled(enabled: Boolean) {
        val value = if (enabled) "1" else "0"
        adb("shell", "settings", "put", "global", "airplane_mode_on", value)
        adb("shell", "am", "broadcast", "-a", "android.intent.action.AIRPLANE_MODE",
            "--ez", "state", enabled.toString())
    }

    override fun setScreenRotation(mode: String) {
        when (mode) {
            "auto" -> {
                adb("shell", "settings", "put", "system", "accelerometer_rotation", "1")
            }
            else -> {
                adb("shell", "settings", "put", "system", "accelerometer_rotation", "0")
                val rotation = when (mode) {
                    "portrait" -> "0"
                    "landscape" -> "1"
                    "reverse_portrait" -> "2"
                    "reverse_landscape" -> "3"
                    else -> "0"
                }
                adb("shell", "settings", "put", "system", "user_rotation", rotation)
            }
        }
    }

    override fun wakeScreen() {
        adb("shell", "input", "keyevent", "KEYCODE_WAKEUP")
    }

    override fun sleepScreen() {
        adb("shell", "input", "keyevent", "KEYCODE_SLEEP")
    }

    override fun lockScreen() {
        adb("shell", "input", "keyevent", "KEYCODE_POWER")
    }

    override fun setVolume(stream: String, level: Int) {
        val streamIndex = when (stream) {
            "music" -> "3"
            "ring" -> "2"
            "notification" -> "5"
            "alarm" -> "4"
            "system" -> "1"
            else -> "3"
        }
        adb("shell", "media", "volume", "--stream", streamIndex, "--set", level.toString())
    }

    override fun setBrightness(level: Int) {
        val clamped = level.coerceIn(0, 255)
        adb("shell", "settings", "put", "system", "screen_brightness_mode", "0") // manual
        adb("shell", "settings", "put", "system", "screen_brightness", clamped.toString())
    }

    override fun reboot(mode: String?) {
        if (mode != null) {
            adb("reboot", mode)
        } else {
            adb("reboot")
        }
    }

    // App management

    override fun installApp(apkPath: String): InteractionResult = try {
        adb("install", "-r", apkPath)
        InteractionResult.Success("Installed: $apkPath")
    } catch (e: Exception) {
        InteractionResult.Error(e)
    }

    override fun uninstallApp(packageName: String): InteractionResult = try {
        adb("uninstall", packageName)
        InteractionResult.Success("Uninstalled: $packageName")
    } catch (e: Exception) {
        InteractionResult.Error(e)
    }

    override fun listPackages(filter: String?): List<String> {
        val output = if (filter != null) {
            adb("shell", "pm", "list", "packages", filter)
        } else {
            adb("shell", "pm", "list", "packages")
        }
        return output.lines()
            .filter { it.startsWith("package:") }
            .map { it.removePrefix("package:").trim() }
            .sorted()
    }

    override fun openUrl(url: String): InteractionResult = try {
        adb("shell", "am", "start", "-a", "android.intent.action.VIEW", "-d", url)
        InteractionResult.Success("Opened URL: $url")
    } catch (e: Exception) {
        InteractionResult.Error(e)
    }

    override fun grantPermission(packageName: String, permission: String) {
        adb("shell", "pm", "grant", packageName, permission)
    }

    override fun revokePermission(packageName: String, permission: String) {
        adb("shell", "pm", "revoke", packageName, permission)
    }

    override fun clearAppData(packageName: String): InteractionResult = try {
        adb("shell", "pm", "clear", packageName)
        InteractionResult.Success("Cleared data: $packageName")
    } catch (e: Exception) {
        InteractionResult.Error(e)
    }

    // Clipboard

    override fun getClipboard(): String {
        return adb("shell", "am", "broadcast", "-a", "clipper.get").trim()
    }

    override fun setClipboard(text: String) {
        adb("shell", "am", "broadcast", "-a", "clipper.set", "-e", "text", text)
    }

    // Notifications

    override fun openNotifications() {
        adb("shell", "cmd", "statusbar", "expand-notifications")
    }

    override fun openQuickSettings() {
        adb("shell", "cmd", "statusbar", "expand-settings")
    }

    // File management

    override fun pushFile(localPath: String, remotePath: String): InteractionResult = try {
        adb("push", localPath, remotePath)
        InteractionResult.Success("Pushed $localPath to $remotePath")
    } catch (e: Exception) {
        InteractionResult.Error(e)
    }

    override fun pullFile(remotePath: String, localPath: String): InteractionResult = try {
        adb("pull", remotePath, localPath)
        InteractionResult.Success("Pulled $remotePath to $localPath")
    } catch (e: Exception) {
        InteractionResult.Error(e)
    }

    // Device info

    override fun getBatteryInfo(): String {
        return adb("shell", "dumpsys", "battery")
    }

    override fun getConnectivityInfo(): String {
        val wifi = adb("shell", "dumpsys", "wifi").lines().take(20).joinToString("\n")
        val net = adb("shell", "dumpsys", "connectivity").lines().take(30).joinToString("\n")
        return "=== WiFi ===\n$wifi\n\n=== Connectivity ===\n$net"
    }

    // Output & debug

    override fun terminalPrint(msg: String) {
        println(msg)
    }

    override fun logcat(): Flow<String> = callbackFlow {
        val process = buildAdbProcess("logcat")
        val reader = process.inputStream.bufferedReader()

        launch(Dispatchers.IO) {
            while (isActive) {
                val line = reader.readLine() ?: break
                trySend(line)
            }
        }

        awaitClose {
            process.destroy()
        }
    }

    override fun logcatDump(lines: Int): String {
        return adb("logcat", "-d", "-t", lines.toString())
    }

    override fun logcatClear() {
        adb("logcat", "-c")
    }

    override fun takeScreenshot(name: String): File? = try {
        val remotePath = "/sdcard/${name}_${System.currentTimeMillis()}.png"
        adb("shell", "screencap", "-p", remotePath)
        val localFile = File(System.getProperty("java.io.tmpdir"), remotePath.substringAfterLast("/"))
        adb("pull", remotePath, localFile.absolutePath)
        adb("shell", "rm", remotePath)
        localFile
    } catch (e: Exception) {
        System.err.println("Could not take screenshot: ${e.message}")
        null
    }

    // Helpers

    private fun findNode(selector: Selector): ScreenNode? {
        val screen = dumpScreen()
        return screen.flatten().firstOrNull { node -> nodeMatches(node, selector) }
    }

    private fun nodeMatches(node: ScreenNode, selector: Selector): Boolean = when (selector) {
        is Selector.ByResourceId -> node.resourceId?.contains(selector.id) == true
        is Selector.ByText -> if (selector.exact) node.text == selector.text
        else node.text?.contains(selector.text, ignoreCase = true) == true
        is Selector.ByClassName -> node.className == selector.className
        is Selector.ByDescription -> node.contentDescription == selector.description
    }

    private fun inputText(text: String) {
        // adb shell input text requires escaping spaces and special chars
        val escaped = text
            .replace("\\", "\\\\")
            .replace(" ", "%s")
            .replace("&", "\\&")
            .replace("<", "\\<")
            .replace(">", "\\>")
            .replace("(", "\\(")
            .replace(")", "\\)")
            .replace("|", "\\|")
            .replace(";", "\\;")
            .replace("'", "\\'")
            .replace("\"", "\\\"")
        adb("shell", "input", "text", escaped)
    }

    private fun adb(vararg args: String): String {
        val process = buildAdbProcess(*args)
        val output = process.inputStream.bufferedReader().readText()
        val error = process.errorStream.bufferedReader().readText()
        process.waitFor()
        if (process.exitValue() != 0 && error.isNotBlank()) {
            throw RuntimeException("adb ${args.joinToString(" ")} failed: $error")
        }
        return output
    }

    private fun buildAdbProcess(vararg args: String): Process {
        val cmd = mutableListOf(adbPath)
        if (deviceSerial != null) {
            cmd.add("-s")
            cmd.add(deviceSerial)
        }
        cmd.addAll(args)
        return ProcessBuilder(cmd).start()
    }
}
