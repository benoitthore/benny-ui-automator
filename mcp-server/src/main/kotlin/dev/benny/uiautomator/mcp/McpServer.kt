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
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import dev.benny.uiautomator.core.AdbDeviceInteractor
import dev.benny.uiautomator.model.InteractionResult
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
                "might not be visible yet, use wait_for first.",
        inputSchema = selectorSchema()
    ) { request ->
        val selector = parseSelector(request.arguments) ?: return@addTool missingSelector()
        resultToToolResult(interactor.click(selector))
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
                "first to understand what's at those coordinates. Use device_info to get the screen dimensions.",
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
            },
            required = listOf("x", "y")
        )
    ) { request ->
        val x = request.arguments?.get("x")?.jsonPrimitive?.intOrNull
            ?: return@addTool CallToolResult(content = listOf(TextContent("Missing 'x' parameter")), isError = true)
        val y = request.arguments?.get("y")?.jsonPrimitive?.intOrNull
            ?: return@addTool CallToolResult(content = listOf(TextContent("Missing 'y' parameter")), isError = true)
        interactor.clickAt(x, y)
        CallToolResult(content = listOf(TextContent("Tapped at ($x, $y)")))
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
