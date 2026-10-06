# Benny UI Automator

Android UI automation framework for AI-driven testing.

## Installation

1. Clone the repo and build the MCP server fat JAR:

```bash
git clone https://github.com/benoitthore/benny-ui-automator.git
cd benny-ui-automator
./gradlew :mcp-server:shadowJar
```

2. Register it globally in `~/.claude.json` so it's available in every project:

```json
{
  "mcpServers": {
    "benny-ui": {
      "type": "stdio",
      "command": "java",
      "args": ["-jar", "<absolute-path-to-this-project>/mcp-server/build/libs/mcp-server-all.jar"]
    }
  }
}
```

Replace the JAR path with where you cloned the repo. That's it — restart Claude Code and the `benny-ui` tools are available everywhere.

## The idea

This project gives AI agents two ways to drive an Android device:

- An **MCP server** that exposes full device control as tools — dump screens, tap, type, scroll, navigate. An agent can explore any app, reason about what's on screen, and interact with it autonomously.
- A **simple automation framework** (`deviceTest()` DSL) that makes it easy to capture what the agent learned as fast, repeatable on-device instrumentation tests — and lets the agent iterate on its own work without the overhead of MCP round-trips.

The core insight: driving a device through MCP works, but it's **slow and expensive** — every action is an ADB round-trip. That's fine for exploration, but you don't want to pay that cost every time you iterate. So: **discover once, test forever**.

1. **Explore** — the agent uses the MCP server to poke around the app. Dump screens, tap buttons, figure out selectors and flows.
2. **Write a test** — capture what it learned as an on-device test using the `deviceTest()` DSL.
3. **Run & iterate fast** — on-device tests execute directly via UiAutomator, no MCP overhead. The agent can tweak, re-run, and validate in seconds instead of minutes.

## MCP Tools

| Tool | Description |
|------|-------------|
| `dump_screen` | Compact UI tree: layout wrappers hidden, only meaningful elements (text, images, inputs, clickable/scrollable) with node ids (`#n`). `mode=text` for readable content only, `mode=full` for every raw node. `max_depth` collapses subtrees, `node_id` expands one. **Prefer this over `take_screenshot`** |
| `find_elements` | Query the screen (text / description / resource-id / class / type / clickable / scrollable, optionally `within` a node) instead of reading the whole tree |
| `inspect_element` | Full details for one node id: attributes, bounds, ancestors, children, and a unique selector to use |
| `take_screenshot` | Captures a PNG screenshot. Only use when `dump_screen` doesn't provide enough info (WebViews, images, visual layout) |
| `device_info` | Returns display size, density, and current foreground package |
| `wait_for` | Waits for an element to appear on screen (by selector) |
| `launch_app` | Launches an app by package name |
| `stop_app` | Force-stops an app by package name |
| `click` | Clicks an element found by selector |
| `long_click` | Long-presses an element found by selector |
| `type_text` | Types text into an input field found by selector |
| `clear_and_type` | Clears an input field and types new text |
| `scroll_until_found` | Scrolls repeatedly until a target element becomes visible |
| `tap` | Taps at raw pixel coordinates, or the center of a node id from the last dump (fallback for WebViews/canvas or elements without a unique selector) |
| `swipe` | Swipes the screen in a direction |
| `press_back` | Presses the Android back button |
| `press_home` | Presses the Android home button |
| `logcat` | Dumps recent Android log lines for debugging |

### Exploring a screen without reading everything

Node ids come from the latest `dump_screen` / `find_elements` call and are only valid until the next capture.

```
dump_screen(max_depth=1)            # overview, deep lists collapsed to "… 40 more elements (…) — expand with node_id=11"
dump_screen(node_id=11)             # expand just that list
find_elements(query="sign in")      # → #10 TextView "Sign in"  → tap #8  in #8 LinearLayout
inspect_element(node_id=8)          # → selector: resource_id="sign_in"
click(selector_type="resource_id", value="sign_in")   # or tap(node_id=8)
```

## Modules

| Module | Description |
|--------|-------------|
| `device-controller-api` | Shared JVM module: `DeviceInteractor` interface, models (`Selector`, `ScreenNode`, `Bounds`…), `AdbDeviceInteractor`, `UiHierarchyParser` |
| `mcp-server` | MCP server that exposes Android device controls as tools (dump screen, click, type, etc.) |
| `android-instrumentation` | Android library module: `InstrumentationDeviceInteractor`, `ScreenExplorer`, `deviceTest()` DSL |

## Build

```bash
# Build everything
./gradlew clean build

# Build just the MCP server fat JAR
./gradlew :mcp-server:shadowJar
```

## Writing tests

Tests use the `deviceTest()` DSL. The lambda is a `suspend InstrumentationDeviceInteractor.() -> Unit`.

Add `android-instrumentation` as a dependency in your test module, then write tests targeting any installed app.

Selectors use String extension properties — `"text".text`, `"res_id".id`, `"description".desc`, `"class".className`:

```kotlin
@RunWith(AndroidJUnit4::class)
class MyTest {
    @Test
    fun example() = deviceTest {
        launchApp("com.example.myapp")
        waitFor("Welcome".text, timeout = 10.seconds)
        click("Sign In".text)
        clearAndType("search_field".desc, "hello")
        delay(1.seconds)
        printScreen()
    }
}
```

## Running instrumentation tests

```bash
# Run all instrumentation tests
./gradlew :your-test-module:connectedDebugAndroidTest

# Run a single test class
./gradlew :your-test-module:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.example.MyTest

# Run a single test method
./gradlew :your-test-module:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.example.MyTest#myTestMethod
```

## Environment variables

| Variable | Required | Description |
|----------|----------|-------------|
| `ADB_DEVICE_SERIAL` | No | Device serial when multiple devices are connected |
