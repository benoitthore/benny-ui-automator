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

    // Output & debug
    fun terminalPrint(msg: String)
    fun logcat(): Flow<String>
    fun logcatDump(lines: Int = 500): String
    fun logcatClear()
    fun takeScreenshot(name: String = "screenshot"): File?
}
