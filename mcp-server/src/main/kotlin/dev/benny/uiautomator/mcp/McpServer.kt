package dev.benny.uiautomator.mcp

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ImageContent
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import dev.benny.uiautomator.core.AdbDeviceInteractor
import dev.benny.uiautomator.model.InteractionResult
import dev.benny.uiautomator.model.ScreenNode
import dev.benny.uiautomator.model.Selector
import dev.benny.uiautomator.model.SwipeDirection
import java.util.Base64
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

fun runMcpServer(interactor: AdbDeviceInteractor) {
    val server = Server(
        Implementation(name = "benny-ui", version = "1.0.0"),
        ServerOptions(
            capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = true))
        )
    )

    // --- Observation tools ---

    server.addTool(
        name = "dump_screen",
        description = "Inspect the current screen. Returns the full UI element tree showing every visible element " +
                "with its text, resource-id, content-description, class, bounds, and state (clickable, scrollable, " +
                "checked, etc). IMPORTANT: Always call this BEFORE interacting with any element. Use the output to " +
                "find the right selector (text, resource-id, or description) for click/type/wait actions. Never " +
                "guess element positions — always observe first. PREFER this over take_screenshot — it is faster, " +
                "cheaper, and provides structured data. Only use take_screenshot when this tool doesn't provide " +
                "enough information (e.g. WebView content, images, visual layout)."
    ) {
        val screen = interactor.dumpScreen()
        CallToolResult(content = listOf(TextContent(screen.prettyPrint())))
    }

    server.addTool(
        name = "take_screenshot",
        description = "Capture a screenshot of the current device screen. Returns a PNG image. " +
                "IMPORTANT: Prefer dump_screen over this tool in most cases — it is faster and returns structured data. " +
                "Only use take_screenshot when you need to visually verify the screen state or when dump_screen " +
                "doesn't provide enough information (e.g. WebView content, images, visual layout).",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("name") {
                    put("type", "string")
                    put("description", "Optional name for the screenshot file")
                }
            },
            required = emptyList()
        )
    ) { request ->
        val name = request.arguments?.get("name")?.jsonPrimitive?.content ?: "screenshot"
        val file = interactor.takeScreenshot(name)
        if (file != null && file.exists()) {
            val bytes = file.readBytes()
            val base64 = Base64.getEncoder().encodeToString(bytes)
            file.delete()
            CallToolResult(content = listOf(ImageContent(data = base64, mimeType = "image/png")))
        } else {
            CallToolResult(content = listOf(TextContent("Failed to capture screenshot")), isError = true)
        }
    }

    server.addTool(
        name = "device_info",
        description = "Get device information: display size (width x height), density, current foreground package. " +
                "Useful for understanding coordinate space when tap is needed."
    ) {
        val (width, height) = interactor.getDisplaySize()
        val density = interactor.getDisplayDensity()
        val currentPkg = interactor.getCurrentPackage() ?: "unknown"
        val info = buildString {
            appendLine("Display: ${width}x${height}")
            appendLine("Density: ${density}dpi")
            appendLine("Current package: $currentPkg")
        }
        CallToolResult(content = listOf(TextContent(info)))
    }

    // --- Waiting tools ---

    server.addTool(
        name = "wait_for",
        description = "Wait for an element to appear on screen. CRITICAL: Always call this after any navigation " +
                "action (launch_app, click, press_back) to ensure the target screen has fully loaded before " +
                "interacting with it. Returns true if found within the timeout. Failing to wait is the #1 cause " +
                "of interaction failures.",
        inputSchema = selectorSchema(
            extraProperties = buildJsonObject {
                putJsonObject("timeout_ms") {
                    put("type", "integer")
                    put("description", "Max wait time in milliseconds (default: 5000)")
                }
            }
        )
    ) { request ->
        val selector = parseSelector(request.arguments) ?: return@addTool missingSelector()
        val timeoutMs = request.arguments?.get("timeout_ms")?.jsonPrimitive?.intOrNull ?: 5000
        val found = runBlocking { interactor.waitFor(selector, timeoutMs.milliseconds) }
        CallToolResult(content = listOf(TextContent(if (found) "Found: $selector" else "Timeout waiting for: $selector")))
    }

    // --- App lifecycle ---

    server.addTool(
        name = "launch_app",
        description = "Launch an app by package name. After calling this, " +
                "always use wait_for to confirm the app's main screen has loaded before interacting.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("package_name") {
                    put("type", "string")
                    put("description", "Android package name")
                }
            },
            required = listOf("package_name")
        )
    ) { request ->
        val pkg = request.arguments?.get("package_name")?.jsonPrimitive?.content
            ?: return@addTool CallToolResult(content = listOf(TextContent("Missing 'package_name' parameter")), isError = true)
        val result = runBlocking { interactor.launchApp(pkg) }
        resultToToolResult(result)
    }

    server.addTool(
        name = "stop_app",
        description = "Force-stop an app by package name.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("package_name") {
                    put("type", "string")
                    put("description", "Android package name")
                }
            },
            required = listOf("package_name")
        )
    ) { request ->
        val pkg = request.arguments?.get("package_name")?.jsonPrimitive?.content
            ?: return@addTool CallToolResult(content = listOf(TextContent("Missing 'package_name' parameter")), isError = true)
        resultToToolResult(interactor.stopApp(pkg))
    }

    // --- Selector-based interactions ---

    server.addTool(
        name = "click",
        description = "Click an element found by selector. This is the preferred way to interact with elements. " +
                "It finds the element in the current screen hierarchy and taps its center coordinates. Before " +
                "calling this, use dump_screen to verify the element exists and find its selector. If the element " +
                "might not be visible yet, use wait_for first. " +
                "Supports multi-tap (double-tap, triple-tap, etc.) via the count parameter.",
        inputSchema = selectorSchema(
            extraProperties = buildJsonObject {
                putJsonObject("count") {
                    put("type", "integer")
                    put("description", "Number of taps to perform (default: 1). Use 2 for double-tap, 3 for triple-tap, etc.")
                }
                putJsonObject("delay_between_ms") {
                    put("type", "integer")
                    put("description", "Delay in milliseconds between consecutive taps (default: 50)")
                }
            }
        )
    ) { request ->
        val selector = parseSelector(request.arguments) ?: return@addTool missingSelector()
        val count = request.arguments?.get("count")?.jsonPrimitive?.intOrNull ?: 1
        val delayBetweenMs = request.arguments?.get("delay_between_ms")?.jsonPrimitive?.intOrNull ?: 50
        if (count <= 1) {
            resultToToolResult(interactor.click(selector))
        } else {
            // Resolve element bounds once, then tap coordinates repeatedly
            val node = interactor.dumpScreen().flatten().firstOrNull { nodeMatches(it, selector) }
                ?: return@addTool resultToToolResult(InteractionResult.ElementNotFound(selector))
            val bounds = node.bounds
                ?: return@addTool resultToToolResult(InteractionResult.Error(IllegalStateException("Node has no bounds: $selector")))
            val cx = bounds.centerX()
            val cy = bounds.centerY()
            repeat(count) { i ->
                if (i > 0) Thread.sleep(delayBetweenMs.toLong())
                interactor.clickAt(cx, cy)
            }
            CallToolResult(content = listOf(TextContent("Clicked $count times: $selector with ${delayBetweenMs}ms delay")))
        }
    }

    server.addTool(
        name = "long_click",
        description = "Long-press an element found by selector (~750ms hold). Use dump_screen first to find the " +
                "right selector.",
        inputSchema = selectorSchema()
    ) { request ->
        val selector = parseSelector(request.arguments) ?: return@addTool missingSelector()
        resultToToolResult(interactor.longClick(selector))
    }

    server.addTool(
        name = "type_text",
        description = "Type text into an input field found by selector. Taps the field first, then types. " +
                "Use dump_screen to find the field's selector.",
        inputSchema = selectorSchema(
            extraProperties = buildJsonObject {
                putJsonObject("text") {
                    put("type", "string")
                    put("description", "The text to type")
                }
            },
            extraRequired = listOf("text")
        )
    ) { request ->
        val selector = parseSelector(request.arguments) ?: return@addTool missingSelector()
        val text = request.arguments?.get("text")?.jsonPrimitive?.content
            ?: return@addTool CallToolResult(content = listOf(TextContent("Missing 'text' parameter")), isError = true)
        resultToToolResult(interactor.typeText(selector, text))
    }

    server.addTool(
        name = "clear_and_type",
        description = "Clear an input field and type new text. Useful for replacing existing text.",
        inputSchema = selectorSchema(
            extraProperties = buildJsonObject {
                putJsonObject("text") {
                    put("type", "string")
                    put("description", "The text to type after clearing")
                }
            },
            extraRequired = listOf("text")
        )
    ) { request ->
        val selector = parseSelector(request.arguments) ?: return@addTool missingSelector()
        val text = request.arguments?.get("text")?.jsonPrimitive?.content
            ?: return@addTool CallToolResult(content = listOf(TextContent("Missing 'text' parameter")), isError = true)
        resultToToolResult(interactor.clearAndType(selector, text))
    }

    server.addTool(
        name = "scroll_until_found",
        description = "Scroll the screen repeatedly until the target element becomes visible. Returns success " +
                "if found within max_scrolls attempts.",
        inputSchema = selectorSchema(
            extraProperties = buildJsonObject {
                putJsonObject("direction") {
                    put("type", "string")
                    put("description", "Scroll direction: up, down, left, right (default: down)")
                    putJsonArray("enum") { add(JsonPrimitive("up")); add(JsonPrimitive("down")); add(JsonPrimitive("left")); add(JsonPrimitive("right")) }
                }
                putJsonObject("max_scrolls") {
                    put("type", "integer")
                    put("description", "Maximum number of scroll attempts (default: 5)")
                }
            }
        )
    ) { request ->
        val selector = parseSelector(request.arguments) ?: return@addTool missingSelector()
        val direction = request.arguments?.get("direction")?.jsonPrimitive?.content
            ?.uppercase()?.let { SwipeDirection.valueOf(it) } ?: SwipeDirection.DOWN
        val maxScrolls = request.arguments?.get("max_scrolls")?.jsonPrimitive?.intOrNull ?: 5
        val result = runBlocking { interactor.scrollUntilFound(selector, direction, maxScrolls) }
        resultToToolResult(result)
    }

    // --- Coordinate-based interaction ---

    server.addTool(
        name = "tap",
        description = "Tap at raw pixel coordinates. Only use this as a last resort when selector-based click " +
                "doesn't work (e.g. WebView content, canvas elements). Always call dump_screen or take_screenshot " +
                "first to understand what's at those coordinates. Use device_info to get the screen dimensions. " +
                "Supports multi-tap (double-tap, triple-tap, etc.) via the count parameter.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("x") {
                    put("type", "integer")
                    put("description", "X coordinate in pixels")
                }
                putJsonObject("y") {
                    put("type", "integer")
                    put("description", "Y coordinate in pixels")
                }
                putJsonObject("count") {
                    put("type", "integer")
                    put("description", "Number of taps to perform (default: 1). Use 2 for double-tap, 3 for triple-tap, etc.")
                }
                putJsonObject("delay_between_ms") {
                    put("type", "integer")
                    put("description", "Delay in milliseconds between consecutive taps (default: 50)")
                }
            },
            required = listOf("x", "y")
        )
    ) { request ->
        val x = request.arguments?.get("x")?.jsonPrimitive?.intOrNull
            ?: return@addTool CallToolResult(content = listOf(TextContent("Missing 'x' parameter")), isError = true)
        val y = request.arguments?.get("y")?.jsonPrimitive?.intOrNull
            ?: return@addTool CallToolResult(content = listOf(TextContent("Missing 'y' parameter")), isError = true)
        val count = request.arguments?.get("count")?.jsonPrimitive?.intOrNull ?: 1
        val delayBetweenMs = request.arguments?.get("delay_between_ms")?.jsonPrimitive?.intOrNull ?: 50
        repeat(count) { i ->
            interactor.clickAt(x, y)
            if (i < count - 1) Thread.sleep(delayBetweenMs.toLong())
        }
        val tapDesc = if (count == 1) "Tapped at ($x, $y)" else "Tapped $count times at ($x, $y) with ${delayBetweenMs}ms delay"
        CallToolResult(content = listOf(TextContent(tapDesc)))
    }

    server.addTool(
        name = "swipe",
        description = "Swipe the screen in a direction. Steps controls speed (lower = faster). Use for scrolling " +
                "lists or dismissing panels.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("direction") {
                    put("type", "string")
                    put("description", "Swipe direction")
                    putJsonArray("enum") { add(JsonPrimitive("up")); add(JsonPrimitive("down")); add(JsonPrimitive("left")); add(JsonPrimitive("right")) }
                }
                putJsonObject("steps") {
                    put("type", "integer")
                    put("description", "Number of steps (controls speed, default: 20)")
                }
            },
            required = listOf("direction")
        )
    ) { request ->
        val dirStr = request.arguments?.get("direction")?.jsonPrimitive?.content
            ?: return@addTool CallToolResult(content = listOf(TextContent("Missing 'direction' parameter")), isError = true)
        val direction = SwipeDirection.valueOf(dirStr.uppercase())
        val steps = request.arguments?.get("steps")?.jsonPrimitive?.intOrNull ?: 20
        resultToToolResult(interactor.swipe(direction, steps))
    }

    // --- Navigation ---

    server.addTool(
        name = "press_back",
        description = "Press the Android back button. After pressing, use wait_for or dump_screen to verify the " +
                "resulting screen."
    ) {
        interactor.pressBack()
        CallToolResult(content = listOf(TextContent("Pressed back")))
    }

    server.addTool(
        name = "press_home",
        description = "Press the Android home button to go to the launcher."
    ) {
        interactor.pressHome()
        CallToolResult(content = listOf(TextContent("Pressed home")))
    }

    server.addTool(
        name = "press_recent_apps",
        description = "Press the Android recent apps / app switcher button."
    ) {
        interactor.pressRecentApps()
        CallToolResult(content = listOf(TextContent("Pressed recent apps")))
    }

    server.addTool(
        name = "press_key",
        description = "Send an arbitrary Android key event by key code number. " +
                "Common codes: ENTER=66, TAB=61, ESCAPE=111, VOLUME_UP=24, VOLUME_DOWN=25, " +
                "POWER=26, CAMERA=27, DEL=67, MENU=82, SEARCH=84, MEDIA_PLAY_PAUSE=85, " +
                "DPAD_UP=19, DPAD_DOWN=20, DPAD_LEFT=21, DPAD_RIGHT=22, DPAD_CENTER=23. " +
                "Full list: developer.android.com/reference/android/view/KeyEvent",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("key_code") {
                    put("type", "integer")
                    put("description", "Android KeyEvent code number")
                }
            },
            required = listOf("key_code")
        )
    ) { request ->
        val keyCode = request.arguments?.get("key_code")?.jsonPrimitive?.intOrNull
            ?: return@addTool CallToolResult(content = listOf(TextContent("Missing 'key_code' parameter")), isError = true)
        interactor.pressKeyEvent(keyCode)
        CallToolResult(content = listOf(TextContent("Pressed key code: $keyCode")))
    }

    server.addTool(
        name = "input_text",
        description = "Type text without needing a selector. The text goes to whatever field currently has focus. " +
                "Useful when you've already tapped a field and just need to type.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("text") {
                    put("type", "string")
                    put("description", "The text to type")
                }
            },
            required = listOf("text")
        )
    ) { request ->
        val text = request.arguments?.get("text")?.jsonPrimitive?.content
            ?: return@addTool CallToolResult(content = listOf(TextContent("Missing 'text' parameter")), isError = true)
        interactor.inputRawText(text)
        CallToolResult(content = listOf(TextContent("Typed: $text")))
    }

    // --- Drag ---

    server.addTool(
        name = "drag",
        description = "Drag from one point to another. Useful for drag-and-drop, sliders, or drawing gestures.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("from_x") { put("type", "integer"); put("description", "Start X coordinate") }
                putJsonObject("from_y") { put("type", "integer"); put("description", "Start Y coordinate") }
                putJsonObject("to_x") { put("type", "integer"); put("description", "End X coordinate") }
                putJsonObject("to_y") { put("type", "integer"); put("description", "End Y coordinate") }
                putJsonObject("duration_ms") { put("type", "integer"); put("description", "Duration of the drag in ms (default: 500)") }
            },
            required = listOf("from_x", "from_y", "to_x", "to_y")
        )
    ) { request ->
        val fromX = request.arguments?.get("from_x")?.jsonPrimitive?.intOrNull
            ?: return@addTool CallToolResult(content = listOf(TextContent("Missing 'from_x'")), isError = true)
        val fromY = request.arguments?.get("from_y")?.jsonPrimitive?.intOrNull
            ?: return@addTool CallToolResult(content = listOf(TextContent("Missing 'from_y'")), isError = true)
        val toX = request.arguments?.get("to_x")?.jsonPrimitive?.intOrNull
            ?: return@addTool CallToolResult(content = listOf(TextContent("Missing 'to_x'")), isError = true)
        val toY = request.arguments?.get("to_y")?.jsonPrimitive?.intOrNull
            ?: return@addTool CallToolResult(content = listOf(TextContent("Missing 'to_y'")), isError = true)
        val durationMs = request.arguments?.get("duration_ms")?.jsonPrimitive?.intOrNull ?: 500
        interactor.drag(fromX, fromY, toX, toY, durationMs)
        CallToolResult(content = listOf(TextContent("Dragged ($fromX,$fromY) -> ($toX,$toY) in ${durationMs}ms")))
    }

    // --- System controls ---

    server.addTool(
        name = "set_wifi",
        description = "Enable or disable WiFi.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("enabled") { put("type", "boolean"); put("description", "true to enable, false to disable") }
            },
            required = listOf("enabled")
        )
    ) { request ->
        val enabled = request.arguments?.get("enabled")?.jsonPrimitive?.booleanOrNull
            ?: return@addTool CallToolResult(content = listOf(TextContent("Missing 'enabled'")), isError = true)
        interactor.setWifiEnabled(enabled)
        CallToolResult(content = listOf(TextContent("WiFi ${if (enabled) "enabled" else "disabled"}")))
    }

    server.addTool(
        name = "set_bluetooth",
        description = "Enable or disable Bluetooth.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("enabled") { put("type", "boolean"); put("description", "true to enable, false to disable") }
            },
            required = listOf("enabled")
        )
    ) { request ->
        val enabled = request.arguments?.get("enabled")?.jsonPrimitive?.booleanOrNull
            ?: return@addTool CallToolResult(content = listOf(TextContent("Missing 'enabled'")), isError = true)
        interactor.setBluetoothEnabled(enabled)
        CallToolResult(content = listOf(TextContent("Bluetooth ${if (enabled) "enabled" else "disabled"}")))
    }

    server.addTool(
        name = "set_airplane_mode",
        description = "Enable or disable airplane mode.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("enabled") { put("type", "boolean"); put("description", "true to enable, false to disable") }
            },
            required = listOf("enabled")
        )
    ) { request ->
        val enabled = request.arguments?.get("enabled")?.jsonPrimitive?.booleanOrNull
            ?: return@addTool CallToolResult(content = listOf(TextContent("Missing 'enabled'")), isError = true)
        interactor.setAirplaneModeEnabled(enabled)
        CallToolResult(content = listOf(TextContent("Airplane mode ${if (enabled) "enabled" else "disabled"}")))
    }

    server.addTool(
        name = "set_rotation",
        description = "Set screen rotation mode.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("mode") {
                    put("type", "string")
                    put("description", "Rotation mode")
                    putJsonArray("enum") {
                        add(JsonPrimitive("auto")); add(JsonPrimitive("portrait"))
                        add(JsonPrimitive("landscape")); add(JsonPrimitive("reverse_portrait"))
                        add(JsonPrimitive("reverse_landscape"))
                    }
                }
            },
            required = listOf("mode")
        )
    ) { request ->
        val mode = request.arguments?.get("mode")?.jsonPrimitive?.content
            ?: return@addTool CallToolResult(content = listOf(TextContent("Missing 'mode'")), isError = true)
        interactor.setScreenRotation(mode)
        CallToolResult(content = listOf(TextContent("Rotation set to: $mode")))
    }

    server.addTool(
        name = "wake_screen",
        description = "Wake up the device screen (turn on display)."
    ) {
        interactor.wakeScreen()
        CallToolResult(content = listOf(TextContent("Screen woken")))
    }

    server.addTool(
        name = "sleep_screen",
        description = "Put the device screen to sleep (turn off display)."
    ) {
        interactor.sleepScreen()
        CallToolResult(content = listOf(TextContent("Screen sleeping")))
    }

    server.addTool(
        name = "lock_screen",
        description = "Lock the device (press power button)."
    ) {
        interactor.lockScreen()
        CallToolResult(content = listOf(TextContent("Screen locked")))
    }

    server.addTool(
        name = "set_volume",
        description = "Set volume level for a specific audio stream.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("stream") {
                    put("type", "string")
                    put("description", "Audio stream")
                    putJsonArray("enum") {
                        add(JsonPrimitive("music")); add(JsonPrimitive("ring"))
                        add(JsonPrimitive("notification")); add(JsonPrimitive("alarm"))
                        add(JsonPrimitive("system"))
                    }
                }
                putJsonObject("level") { put("type", "integer"); put("description", "Volume level (0-15 typical)") }
            },
            required = listOf("stream", "level")
        )
    ) { request ->
        val stream = request.arguments?.get("stream")?.jsonPrimitive?.content
            ?: return@addTool CallToolResult(content = listOf(TextContent("Missing 'stream'")), isError = true)
        val level = request.arguments?.get("level")?.jsonPrimitive?.intOrNull
            ?: return@addTool CallToolResult(content = listOf(TextContent("Missing 'level'")), isError = true)
        interactor.setVolume(stream, level)
        CallToolResult(content = listOf(TextContent("Volume $stream set to $level")))
    }

    server.addTool(
        name = "set_brightness",
        description = "Set screen brightness (disables auto-brightness).",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("level") { put("type", "integer"); put("description", "Brightness level 0-255") }
            },
            required = listOf("level")
        )
    ) { request ->
        val level = request.arguments?.get("level")?.jsonPrimitive?.intOrNull
            ?: return@addTool CallToolResult(content = listOf(TextContent("Missing 'level'")), isError = true)
        interactor.setBrightness(level)
        CallToolResult(content = listOf(TextContent("Brightness set to $level")))
    }

    server.addTool(
        name = "reboot",
        description = "Reboot the device. Optionally specify a mode: 'recovery' or 'bootloader'.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("mode") {
                    put("type", "string")
                    put("description", "Reboot mode (optional). Leave empty for normal reboot.")
                    putJsonArray("enum") { add(JsonPrimitive("normal")); add(JsonPrimitive("recovery")); add(JsonPrimitive("bootloader")) }
                }
            },
            required = emptyList()
        )
    ) { request ->
        val mode = request.arguments?.get("mode")?.jsonPrimitive?.content?.let {
            if (it == "normal") null else it
        }
        interactor.reboot(mode)
        CallToolResult(content = listOf(TextContent("Rebooting device${if (mode != null) " into $mode" else ""}")))
    }

    // --- App management ---

    server.addTool(
        name = "install_app",
        description = "Install an APK from the host machine onto the device.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("apk_path") { put("type", "string"); put("description", "Path to the APK file on the host") }
            },
            required = listOf("apk_path")
        )
    ) { request ->
        val apkPath = request.arguments?.get("apk_path")?.jsonPrimitive?.content
            ?: return@addTool CallToolResult(content = listOf(TextContent("Missing 'apk_path'")), isError = true)
        resultToToolResult(interactor.installApp(apkPath))
    }

    server.addTool(
        name = "uninstall_app",
        description = "Uninstall an app by package name.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("package_name") { put("type", "string"); put("description", "Android package name") }
            },
            required = listOf("package_name")
        )
    ) { request ->
        val pkg = request.arguments?.get("package_name")?.jsonPrimitive?.content
            ?: return@addTool CallToolResult(content = listOf(TextContent("Missing 'package_name'")), isError = true)
        resultToToolResult(interactor.uninstallApp(pkg))
    }

    server.addTool(
        name = "list_packages",
        description = "List installed packages on the device. Optionally filter by keyword.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("filter") { put("type", "string"); put("description", "Optional keyword to filter packages") }
            },
            required = emptyList()
        )
    ) { request ->
        val filter = request.arguments?.get("filter")?.jsonPrimitive?.content
        val packages = interactor.listPackages(filter)
        CallToolResult(content = listOf(TextContent("${packages.size} packages:\n${packages.joinToString("\n")}")))
    }

    server.addTool(
        name = "open_url",
        description = "Open a URL or deep link in the default handler. Works with http/https URLs and app deep links (e.g. myapp://path).",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("url") { put("type", "string"); put("description", "URL or deep link to open") }
            },
            required = listOf("url")
        )
    ) { request ->
        val url = request.arguments?.get("url")?.jsonPrimitive?.content
            ?: return@addTool CallToolResult(content = listOf(TextContent("Missing 'url'")), isError = true)
        resultToToolResult(interactor.openUrl(url))
    }

    server.addTool(
        name = "grant_permission",
        description = "Grant a runtime permission to an app (e.g. android.permission.CAMERA).",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("package_name") { put("type", "string"); put("description", "Android package name") }
                putJsonObject("permission") { put("type", "string"); put("description", "Full permission string (e.g. android.permission.CAMERA)") }
            },
            required = listOf("package_name", "permission")
        )
    ) { request ->
        val pkg = request.arguments?.get("package_name")?.jsonPrimitive?.content
            ?: return@addTool CallToolResult(content = listOf(TextContent("Missing 'package_name'")), isError = true)
        val permission = request.arguments?.get("permission")?.jsonPrimitive?.content
            ?: return@addTool CallToolResult(content = listOf(TextContent("Missing 'permission'")), isError = true)
        interactor.grantPermission(pkg, permission)
        CallToolResult(content = listOf(TextContent("Granted $permission to $pkg")))
    }

    server.addTool(
        name = "revoke_permission",
        description = "Revoke a runtime permission from an app.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("package_name") { put("type", "string"); put("description", "Android package name") }
                putJsonObject("permission") { put("type", "string"); put("description", "Full permission string") }
            },
            required = listOf("package_name", "permission")
        )
    ) { request ->
        val pkg = request.arguments?.get("package_name")?.jsonPrimitive?.content
            ?: return@addTool CallToolResult(content = listOf(TextContent("Missing 'package_name'")), isError = true)
        val permission = request.arguments?.get("permission")?.jsonPrimitive?.content
            ?: return@addTool CallToolResult(content = listOf(TextContent("Missing 'permission'")), isError = true)
        interactor.revokePermission(pkg, permission)
        CallToolResult(content = listOf(TextContent("Revoked $permission from $pkg")))
    }

    server.addTool(
        name = "clear_app_data",
        description = "Clear all data for an app (like a fresh install). This force-stops the app.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("package_name") { put("type", "string"); put("description", "Android package name") }
            },
            required = listOf("package_name")
        )
    ) { request ->
        val pkg = request.arguments?.get("package_name")?.jsonPrimitive?.content
            ?: return@addTool CallToolResult(content = listOf(TextContent("Missing 'package_name'")), isError = true)
        resultToToolResult(interactor.clearAppData(pkg))
    }

    server.addTool(
        name = "is_app_running",
        description = "Check if an app is currently in the foreground.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("package_name") { put("type", "string"); put("description", "Android package name") }
            },
            required = listOf("package_name")
        )
    ) { request ->
        val pkg = request.arguments?.get("package_name")?.jsonPrimitive?.content
            ?: return@addTool CallToolResult(content = listOf(TextContent("Missing 'package_name'")), isError = true)
        val running = interactor.isAppRunning(pkg)
        CallToolResult(content = listOf(TextContent(if (running) "$pkg is running in foreground" else "$pkg is NOT in foreground")))
    }

    // --- Clipboard ---

    server.addTool(
        name = "get_clipboard",
        description = "Get the current clipboard text content. Note: requires clipper service on device."
    ) {
        val text = interactor.getClipboard()
        CallToolResult(content = listOf(TextContent("Clipboard: $text")))
    }

    server.addTool(
        name = "set_clipboard",
        description = "Set the clipboard text content. Note: requires clipper service on device.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("text") { put("type", "string"); put("description", "Text to put on clipboard") }
            },
            required = listOf("text")
        )
    ) { request ->
        val text = request.arguments?.get("text")?.jsonPrimitive?.content
            ?: return@addTool CallToolResult(content = listOf(TextContent("Missing 'text'")), isError = true)
        interactor.setClipboard(text)
        CallToolResult(content = listOf(TextContent("Clipboard set")))
    }

    // --- Notifications ---

    server.addTool(
        name = "open_notifications",
        description = "Pull down the notification shade."
    ) {
        interactor.openNotifications()
        CallToolResult(content = listOf(TextContent("Notification shade opened")))
    }

    server.addTool(
        name = "open_quick_settings",
        description = "Open the quick settings panel (WiFi, Bluetooth, etc toggles)."
    ) {
        interactor.openQuickSettings()
        CallToolResult(content = listOf(TextContent("Quick settings opened")))
    }

    // --- File management ---

    server.addTool(
        name = "push_file",
        description = "Push a file from the host machine to the device.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("local_path") { put("type", "string"); put("description", "Path on host machine") }
                putJsonObject("remote_path") { put("type", "string"); put("description", "Path on the device (e.g. /sdcard/file.txt)") }
            },
            required = listOf("local_path", "remote_path")
        )
    ) { request ->
        val local = request.arguments?.get("local_path")?.jsonPrimitive?.content
            ?: return@addTool CallToolResult(content = listOf(TextContent("Missing 'local_path'")), isError = true)
        val remote = request.arguments?.get("remote_path")?.jsonPrimitive?.content
            ?: return@addTool CallToolResult(content = listOf(TextContent("Missing 'remote_path'")), isError = true)
        resultToToolResult(interactor.pushFile(local, remote))
    }

    server.addTool(
        name = "pull_file",
        description = "Pull a file from the device to the host machine.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("remote_path") { put("type", "string"); put("description", "Path on the device") }
                putJsonObject("local_path") { put("type", "string"); put("description", "Path on host machine") }
            },
            required = listOf("remote_path", "local_path")
        )
    ) { request ->
        val remote = request.arguments?.get("remote_path")?.jsonPrimitive?.content
            ?: return@addTool CallToolResult(content = listOf(TextContent("Missing 'remote_path'")), isError = true)
        val local = request.arguments?.get("local_path")?.jsonPrimitive?.content
            ?: return@addTool CallToolResult(content = listOf(TextContent("Missing 'local_path'")), isError = true)
        resultToToolResult(interactor.pullFile(remote, local))
    }

    // --- Device info ---

    server.addTool(
        name = "battery_info",
        description = "Get battery status: level, charging state, temperature, etc."
    ) {
        val info = interactor.getBatteryInfo()
        CallToolResult(content = listOf(TextContent(info)))
    }

    server.addTool(
        name = "connectivity_info",
        description = "Get network connectivity info: WiFi state, active connections, etc."
    ) {
        val info = interactor.getConnectivityInfo()
        CallToolResult(content = listOf(TextContent(info)))
    }

    // --- Logcat ---

    server.addTool(
        name = "logcat_clear",
        description = "Clear the logcat buffer. Useful before running a test so you only see relevant logs."
    ) {
        interactor.logcatClear()
        CallToolResult(content = listOf(TextContent("Logcat cleared")))
    }

    // --- Debug ---

    server.addTool(
        name = "logcat",
        description = "Dump recent Android log lines. Useful for debugging app crashes or verifying backend calls.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("lines") {
                    put("type", "integer")
                    put("description", "Number of recent log lines to return (default: 500)")
                }
            },
            required = emptyList()
        )
    ) { request ->
        val lines = request.arguments?.get("lines")?.jsonPrimitive?.intOrNull ?: 500
        val output = interactor.logcatDump(lines)
        CallToolResult(content = listOf(TextContent(output)))
    }

    // --- Batch execution ---

    server.addTool(
        name = "run_sequence",
        description = "Execute a sequence of UI actions in a single call. Each step is an object with an 'action' " +
                "field matching an existing tool name, plus that action's parameters. Steps run sequentially; " +
                "if any step fails, execution stops and all results so far are returned. " +
                "Useful for scripted flows like: launch app → wait for element → interact → assert state. " +
                "Special actions: 'assert_visible' checks an element exists (fails if not), " +
                "'sleep' pauses for a given number of ms.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("steps") {
                    put("type", "array")
                    put("description", "Array of step objects. Each must have an 'action' field plus action-specific params.")
                    putJsonObject("items") {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("action") {
                                put("type", "string")
                                put("description", "Name of the action to execute")
                            }
                        }
                        putJsonArray("required") { add(JsonPrimitive("action")) }
                    }
                }
            },
            required = listOf("steps")
        )
    ) { request ->
        val steps = try {
            request.arguments?.get("steps")?.jsonArray
        } catch (_: Exception) { null }
            ?: return@addTool CallToolResult(
                content = listOf(TextContent("Missing or invalid 'steps' array")),
                isError = true
            )

        val results = mutableListOf<String>()
        for ((index, stepElement) in steps.withIndex()) {
            val step = try { stepElement.jsonObject } catch (_: Exception) {
                results.add("Step ${index + 1}: Invalid step object")
                results.add("⛔ Sequence stopped at step ${index + 1}")
                return@addTool CallToolResult(
                    content = listOf(TextContent(results.joinToString("\n"))),
                    isError = true
                )
            }
            val action = step["action"]?.jsonPrimitive?.content
            if (action == null) {
                results.add("Step ${index + 1}: Missing 'action' field")
                results.add("⛔ Sequence stopped at step ${index + 1}")
                return@addTool CallToolResult(
                    content = listOf(TextContent(results.joinToString("\n"))),
                    isError = true
                )
            }
            val stepResult = executeStep(action, step, interactor)
            results.add("Step ${index + 1} ($action): ${stepResult.text}")
            if (stepResult.isError) {
                results.add("⛔ Sequence stopped at step ${index + 1}")
                return@addTool CallToolResult(
                    content = listOf(TextContent(results.joinToString("\n"))),
                    isError = true
                )
            }
        }
        CallToolResult(content = listOf(TextContent(results.joinToString("\n"))))
    }

    // --- Start server ---

    val transport = StdioServerTransport(
        inputStream = System.`in`.asSource().buffered(),
        outputStream = System.out.asSink().buffered()
    )

    runBlocking {
        server.createSession(transport)
        val done = Job()
        server.onClose {
            done.complete()
        }
        done.join()
    }
}

// --- Helpers ---

private fun selectorSchema(
    extraProperties: kotlinx.serialization.json.JsonObject? = null,
    extraRequired: List<String> = emptyList()
): ToolSchema {
    val props = buildJsonObject {
        putJsonObject("selector_type") {
            put("type", "string")
            put("description", "How to find the element")
            putJsonArray("enum") { add(JsonPrimitive("resource_id")); add(JsonPrimitive("text")); add(JsonPrimitive("description")); add(JsonPrimitive("class_name")) }
        }
        putJsonObject("value") {
            put("type", "string")
            put("description", "The value to match for the chosen selector_type")
        }
        putJsonObject("exact") {
            put("type", "boolean")
            put("description", "Whether to match exactly (default: true). Only applies to text selectors.")
        }
        extraProperties?.forEach { key, value ->
            put(key, value)
        }
    }
    return ToolSchema(
        properties = props,
        required = listOf("selector_type", "value") + extraRequired
    )
}

private fun parseSelector(arguments: kotlinx.serialization.json.JsonObject?): Selector? {
    val type = arguments?.get("selector_type")?.jsonPrimitive?.content ?: return null
    val value = arguments["value"]?.jsonPrimitive?.content ?: return null
    val exact = arguments["exact"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: true
    return when (type) {
        "resource_id" -> Selector.ByResourceId(value)
        "text" -> Selector.ByText(value, exact)
        "description" -> Selector.ByDescription(value)
        "class_name" -> Selector.ByClassName(value)
        else -> null
    }
}

private fun nodeMatches(node: ScreenNode, selector: Selector): Boolean = when (selector) {
    is Selector.ByResourceId -> node.resourceId?.contains(selector.id) == true
    is Selector.ByText -> if (selector.exact) node.text == selector.text
        else node.text?.contains(selector.text, ignoreCase = true) == true
    is Selector.ByClassName -> node.className == selector.className
    is Selector.ByDescription -> node.contentDescription == selector.description
}

private fun missingSelector(): CallToolResult =
    CallToolResult(
        content = listOf(TextContent("Missing or invalid selector_type/value parameters")),
        isError = true
    )

private fun resultToToolResult(result: InteractionResult): CallToolResult = when (result) {
    is InteractionResult.Success -> CallToolResult(content = listOf(TextContent(result.message)))
    is InteractionResult.ElementNotFound -> CallToolResult(
        content = listOf(TextContent("Element not found: ${result.selector}")),
        isError = true
    )
    is InteractionResult.Error -> CallToolResult(
        content = listOf(TextContent("Error: ${result.exception.message}")),
        isError = true
    )
}

// --- Batch execution helpers ---

private data class StepResult(val text: String, val isError: Boolean = false)

private fun resultToStepResult(result: InteractionResult): StepResult = when (result) {
    is InteractionResult.Success -> StepResult(result.message)
    is InteractionResult.ElementNotFound -> StepResult("Element not found: ${result.selector}", isError = true)
    is InteractionResult.Error -> StepResult("Error: ${result.exception.message}", isError = true)
}

private fun parseSelectorFromStep(step: kotlinx.serialization.json.JsonObject): Selector? {
    val type = step["selector_type"]?.jsonPrimitive?.content ?: return null
    val value = step["value"]?.jsonPrimitive?.content ?: return null
    val exact = step["exact"]?.jsonPrimitive?.booleanOrNull ?: true
    return when (type) {
        "resource_id" -> Selector.ByResourceId(value)
        "text" -> Selector.ByText(value, exact)
        "description" -> Selector.ByDescription(value)
        "class_name" -> Selector.ByClassName(value)
        else -> null
    }
}

private fun executeStep(
    action: String,
    step: kotlinx.serialization.json.JsonObject,
    interactor: AdbDeviceInteractor
): StepResult {
    return try {
        when (action) {
            "launch_app" -> {
                val pkg = step["package_name"]?.jsonPrimitive?.content
                    ?: return StepResult("Missing 'package_name'", isError = true)
                resultToStepResult(runBlocking { interactor.launchApp(pkg) })
            }
            "stop_app" -> {
                val pkg = step["package_name"]?.jsonPrimitive?.content
                    ?: return StepResult("Missing 'package_name'", isError = true)
                resultToStepResult(interactor.stopApp(pkg))
            }
            "click" -> {
                val selector = parseSelectorFromStep(step)
                    ?: return StepResult("Missing or invalid selector", isError = true)
                val count = step["count"]?.jsonPrimitive?.intOrNull ?: 1
                val delayBetweenMs = step["delay_between_ms"]?.jsonPrimitive?.intOrNull ?: 50
                if (count <= 1) {
                    resultToStepResult(interactor.click(selector))
                } else {
                    val node = interactor.dumpScreen().flatten().firstOrNull { nodeMatches(it, selector) }
                        ?: return StepResult("Element not found: $selector", isError = true)
                    val bounds = node.bounds
                        ?: return StepResult("Node has no bounds: $selector", isError = true)
                    repeat(count) { i ->
                        if (i > 0) Thread.sleep(delayBetweenMs.toLong())
                        interactor.clickAt(bounds.centerX(), bounds.centerY())
                    }
                    StepResult("Clicked $count times: $selector")
                }
            }
            "long_click" -> {
                val selector = parseSelectorFromStep(step)
                    ?: return StepResult("Missing or invalid selector", isError = true)
                resultToStepResult(interactor.longClick(selector))
            }
            "tap" -> {
                val x = step["x"]?.jsonPrimitive?.intOrNull
                    ?: return StepResult("Missing 'x'", isError = true)
                val y = step["y"]?.jsonPrimitive?.intOrNull
                    ?: return StepResult("Missing 'y'", isError = true)
                val count = step["count"]?.jsonPrimitive?.intOrNull ?: 1
                val delayBetweenMs = step["delay_between_ms"]?.jsonPrimitive?.intOrNull ?: 50
                repeat(count) { i ->
                    if (i > 0) Thread.sleep(delayBetweenMs.toLong())
                    interactor.clickAt(x, y)
                }
                if (count == 1) StepResult("Tapped at ($x, $y)")
                else StepResult("Tapped $count times at ($x, $y)")
            }
            "type_text" -> {
                val selector = parseSelectorFromStep(step)
                    ?: return StepResult("Missing or invalid selector", isError = true)
                val text = step["text"]?.jsonPrimitive?.content
                    ?: return StepResult("Missing 'text'", isError = true)
                resultToStepResult(interactor.typeText(selector, text))
            }
            "clear_and_type" -> {
                val selector = parseSelectorFromStep(step)
                    ?: return StepResult("Missing or invalid selector", isError = true)
                val text = step["text"]?.jsonPrimitive?.content
                    ?: return StepResult("Missing 'text'", isError = true)
                resultToStepResult(interactor.clearAndType(selector, text))
            }
            "swipe" -> {
                val dirStr = step["direction"]?.jsonPrimitive?.content
                    ?: return StepResult("Missing 'direction'", isError = true)
                val direction = SwipeDirection.valueOf(dirStr.uppercase())
                val steps = step["steps"]?.jsonPrimitive?.intOrNull ?: 20
                resultToStepResult(interactor.swipe(direction, steps))
            }
            "scroll_until_found" -> {
                val selector = parseSelectorFromStep(step)
                    ?: return StepResult("Missing or invalid selector", isError = true)
                val direction = step["direction"]?.jsonPrimitive?.content
                    ?.uppercase()?.let { SwipeDirection.valueOf(it) } ?: SwipeDirection.DOWN
                val maxScrolls = step["max_scrolls"]?.jsonPrimitive?.intOrNull ?: 5
                resultToStepResult(runBlocking { interactor.scrollUntilFound(selector, direction, maxScrolls) })
            }
            "wait_for" -> {
                val selector = parseSelectorFromStep(step)
                    ?: return StepResult("Missing or invalid selector", isError = true)
                val timeoutMs = step["timeout_ms"]?.jsonPrimitive?.intOrNull ?: 5000
                val found = runBlocking { interactor.waitFor(selector, timeoutMs.milliseconds) }
                if (found) StepResult("Found: $selector")
                else StepResult("Timeout waiting for: $selector", isError = true)
            }
            "press_back" -> {
                interactor.pressBack()
                StepResult("Pressed back")
            }
            "press_home" -> {
                interactor.pressHome()
                StepResult("Pressed home")
            }
            "dump_screen" -> {
                val screen = interactor.dumpScreen()
                StepResult("Screen dump:\n${screen.prettyPrint()}")
            }
            "take_screenshot" -> {
                val name = step["name"]?.jsonPrimitive?.content ?: "screenshot"
                val file = interactor.takeScreenshot(name)
                if (file != null && file.exists()) {
                    file.delete()
                    StepResult("Screenshot captured successfully (image not included in batch output)")
                } else {
                    StepResult("Failed to capture screenshot", isError = true)
                }
            }
            "assert_visible" -> {
                val selector = parseSelectorFromStep(step)
                    ?: return StepResult("Missing or invalid selector", isError = true)
                val found = interactor.dumpScreen().flatten().any { nodeMatches(it, selector) }
                if (found) StepResult("Visible: $selector")
                else StepResult("Not visible: $selector", isError = true)
            }
            "sleep" -> {
                val ms = step["ms"]?.jsonPrimitive?.longOrNull
                    ?: return StepResult("Missing 'ms'", isError = true)
                Thread.sleep(ms)
                StepResult("Slept ${ms}ms")
            }
            "press_recent_apps" -> {
                interactor.pressRecentApps()
                StepResult("Pressed recent apps")
            }
            "press_key" -> {
                val keyCode = step["key_code"]?.jsonPrimitive?.intOrNull
                    ?: return StepResult("Missing 'key_code'", isError = true)
                interactor.pressKeyEvent(keyCode)
                StepResult("Pressed key code: $keyCode")
            }
            "input_text" -> {
                val text = step["text"]?.jsonPrimitive?.content
                    ?: return StepResult("Missing 'text'", isError = true)
                interactor.inputRawText(text)
                StepResult("Typed: $text")
            }
            "drag" -> {
                val fromX = step["from_x"]?.jsonPrimitive?.intOrNull ?: return StepResult("Missing 'from_x'", isError = true)
                val fromY = step["from_y"]?.jsonPrimitive?.intOrNull ?: return StepResult("Missing 'from_y'", isError = true)
                val toX = step["to_x"]?.jsonPrimitive?.intOrNull ?: return StepResult("Missing 'to_x'", isError = true)
                val toY = step["to_y"]?.jsonPrimitive?.intOrNull ?: return StepResult("Missing 'to_y'", isError = true)
                val durationMs = step["duration_ms"]?.jsonPrimitive?.intOrNull ?: 500
                interactor.drag(fromX, fromY, toX, toY, durationMs)
                StepResult("Dragged ($fromX,$fromY) -> ($toX,$toY)")
            }
            "set_wifi" -> {
                val enabled = step["enabled"]?.jsonPrimitive?.booleanOrNull ?: return StepResult("Missing 'enabled'", isError = true)
                interactor.setWifiEnabled(enabled)
                StepResult("WiFi ${if (enabled) "enabled" else "disabled"}")
            }
            "set_bluetooth" -> {
                val enabled = step["enabled"]?.jsonPrimitive?.booleanOrNull ?: return StepResult("Missing 'enabled'", isError = true)
                interactor.setBluetoothEnabled(enabled)
                StepResult("Bluetooth ${if (enabled) "enabled" else "disabled"}")
            }
            "set_airplane_mode" -> {
                val enabled = step["enabled"]?.jsonPrimitive?.booleanOrNull ?: return StepResult("Missing 'enabled'", isError = true)
                interactor.setAirplaneModeEnabled(enabled)
                StepResult("Airplane mode ${if (enabled) "enabled" else "disabled"}")
            }
            "open_url" -> {
                val url = step["url"]?.jsonPrimitive?.content ?: return StepResult("Missing 'url'", isError = true)
                resultToStepResult(interactor.openUrl(url))
            }
            "open_notifications" -> {
                interactor.openNotifications()
                StepResult("Notification shade opened")
            }
            "open_quick_settings" -> {
                interactor.openQuickSettings()
                StepResult("Quick settings opened")
            }
            "wake_screen" -> {
                interactor.wakeScreen()
                StepResult("Screen woken")
            }
            "sleep_screen" -> {
                interactor.sleepScreen()
                StepResult("Screen sleeping")
            }
            "clear_app_data" -> {
                val pkg = step["package_name"]?.jsonPrimitive?.content ?: return StepResult("Missing 'package_name'", isError = true)
                resultToStepResult(interactor.clearAppData(pkg))
            }
            "logcat_clear" -> {
                interactor.logcatClear()
                StepResult("Logcat cleared")
            }
            else -> StepResult("Unknown action: $action", isError = true)
        }
    } catch (e: Exception) {
        StepResult("Exception: ${e.message}", isError = true)
    }
}
