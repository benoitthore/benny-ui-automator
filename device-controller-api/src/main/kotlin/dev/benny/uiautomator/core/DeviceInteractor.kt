package dev.benny.uiautomator.core

import kotlinx.coroutines.flow.Flow
import dev.benny.uiautomator.model.InteractionResult
import dev.benny.uiautomator.model.ScreenNode
import dev.benny.uiautomator.model.Selector
import dev.benny.uiautomator.model.SwipeDirection
import java.io.File
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

interface DeviceInteractor {

    // App lifecycle
    suspend fun launchApp(packageName: String, timeout: Duration = 15.seconds): InteractionResult
    fun stopApp(packageName: String): InteractionResult
    fun isAppRunning(packageName: String): Boolean

    // Screen
    fun dumpScreen(): ScreenNode
    fun printScreen() { println(dumpScreen().prettyPrint()) }
    fun screenContains(selector: Selector): Boolean

    // Waiting
    suspend fun waitFor(selector: Selector, timeout: Duration = 5.seconds): Boolean
    suspend fun waitForIdle(timeout: Duration = 5.seconds)
    suspend fun delay(duration: Duration) { kotlinx.coroutines.delay(duration) }

    // Interactions
    fun click(selector: Selector): InteractionResult
    fun clickAt(x: Int, y: Int)
    fun longClick(selector: Selector): InteractionResult
    fun typeText(selector: Selector, text: String): InteractionResult
    fun clearAndType(selector: Selector, text: String): InteractionResult
    fun swipe(direction: SwipeDirection, steps: Int = 20): InteractionResult
    suspend fun scrollUntilFound(
        selector: Selector,
        direction: SwipeDirection = SwipeDirection.DOWN,
        maxScrolls: Int = 5,
        delayBetween: Duration = 500.milliseconds
    ): InteractionResult

    // Buttons
    fun pressBack()
    fun pressHome()
    fun pressRecentApps()
    fun pressKeyEvent(keyCode: Int)
    fun inputRawText(text: String)

    // Drag
    fun drag(fromX: Int, fromY: Int, toX: Int, toY: Int, durationMs: Int = 500)

    // System controls
    fun setWifiEnabled(enabled: Boolean)
    fun setBluetoothEnabled(enabled: Boolean)
    fun setAirplaneModeEnabled(enabled: Boolean)
    fun setScreenRotation(mode: String) // "auto", "portrait", "landscape", "reverse_portrait", "reverse_landscape"
    fun wakeScreen()
    fun sleepScreen()
    fun lockScreen()
    fun setVolume(stream: String, level: Int) // stream: "music", "ring", "notification", "alarm", "system"
    fun setBrightness(level: Int) // 0-255
    fun reboot(mode: String? = null) // null = normal, "recovery", "bootloader"

    // App management
    fun installApp(apkPath: String): InteractionResult
    fun uninstallApp(packageName: String): InteractionResult
    fun listPackages(filter: String? = null): List<String>
    fun openUrl(url: String): InteractionResult
    fun grantPermission(packageName: String, permission: String)
    fun revokePermission(packageName: String, permission: String)
    fun clearAppData(packageName: String): InteractionResult

    // Clipboard
    fun getClipboard(): String
    fun setClipboard(text: String)

    // Notifications
    fun openNotifications()
    fun openQuickSettings()

    // File management
    fun pushFile(localPath: String, remotePath: String): InteractionResult
    fun pullFile(remotePath: String, localPath: String): InteractionResult

    // Device info
    fun getBatteryInfo(): String
    fun getConnectivityInfo(): String

    // Output & debug
    fun terminalPrint(msg: String)
    fun logcat(): Flow<String>
    fun logcatDump(lines: Int = 500): String
    fun logcatClear()
    fun takeScreenshot(name: String = "screenshot"): File?
}
