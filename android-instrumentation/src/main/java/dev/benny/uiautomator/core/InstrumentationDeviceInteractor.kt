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
