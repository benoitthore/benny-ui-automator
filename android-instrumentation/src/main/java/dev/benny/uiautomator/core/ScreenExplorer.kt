package dev.benny.uiautomator.core

import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import kotlinx.coroutines.delay
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import dev.benny.uiautomator.model.Bounds
import dev.benny.uiautomator.model.NodeType
import dev.benny.uiautomator.model.ScreenNode
import dev.benny.uiautomator.model.Selector
import dev.benny.uiautomator.model.toBySelector
import java.io.ByteArrayOutputStream
import java.io.StringReader
import kotlin.time.Duration

internal class ScreenExplorer(private val device: UiDevice) {

    fun dumpScreen(): ScreenNode {
        val outputStream = ByteArrayOutputStream()
        device.dumpWindowHierarchy(outputStream)
        val xml = outputStream.toString("UTF-8")
        return parseXml(xml)
    }

    internal fun findUiObject(selector: Selector): UiObject2? =
        device.findObject(selector.toBySelector())

    suspend fun waitForElement(selector: Selector, timeout: Duration): Boolean {
        val deadline = System.currentTimeMillis() + timeout.inWholeMilliseconds
        while (System.currentTimeMillis() < deadline) {
            if (findUiObject(selector) != null) return true
            delay(250)
        }
        return false
    }

    private fun parseXml(xml: String): ScreenNode {
        val factory = XmlPullParserFactory.newInstance()
        val parser = factory.newPullParser()
        parser.setInput(StringReader(xml))

        val rootChildren = mutableListOf<ScreenNode>()
        val stack = mutableListOf<MutableList<ScreenNode>>()
        stack.add(rootChildren)

        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    if (parser.name == "node") {
                        val depth = stack.size - 1
                        val node = parseNode(parser, depth)
                        val childList = mutableListOf<ScreenNode>()
                        stack.last().add(node.copy(children = childList))
                        stack.add(childList)
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (parser.name == "node" && stack.size > 1) {
                        val children = stack.removeAt(stack.lastIndex)
                        val parent = stack.last()
                        if (parent.isNotEmpty()) {
                            val lastNode = parent.last()
                            parent[parent.lastIndex] = lastNode.copy(children = children)
                        }
                    }
                }
            }
            eventType = parser.next()
        }

        return if (rootChildren.size == 1) {
            rootChildren.first()
        } else {
            ScreenNode(
                className = "root",
                nodeType = NodeType.CONTAINER,
                resourceId = null,
                text = null,
                contentDescription = null,
                isClickable = false,
                isScrollable = false,
                isCheckable = false,
                isChecked = false,
                isEnabled = true,
                isFocusable = false,
                bounds = null,
                children = rootChildren,
                depth = 0
            )
        }
    }

    private fun parseNode(parser: XmlPullParser, depth: Int): ScreenNode {
        val className = parser.getAttributeValue(null, "class") ?: ""
        val resourceId = parser.getAttributeValue(null, "resource-id")?.takeIf { it.isNotBlank() }
        val text = parser.getAttributeValue(null, "text")?.takeIf { it.isNotBlank() }
        val contentDescription = parser.getAttributeValue(null, "content-desc")?.takeIf { it.isNotBlank() }
        val isClickable = parser.getAttributeValue(null, "clickable") == "true"
        val isScrollable = parser.getAttributeValue(null, "scrollable") == "true"
        val isCheckable = parser.getAttributeValue(null, "checkable") == "true"
        val isChecked = parser.getAttributeValue(null, "checked") == "true"
        val isEnabled = parser.getAttributeValue(null, "enabled") != "false"
        val isFocusable = parser.getAttributeValue(null, "focusable") == "true"
        val boundsStr = parser.getAttributeValue(null, "bounds")
        val bounds = parseBounds(boundsStr)

        return ScreenNode(
            className = className,
            nodeType = NodeType.from(className, isClickable, isScrollable),
            resourceId = resourceId,
            text = text,
            contentDescription = contentDescription,
            isClickable = isClickable,
            isScrollable = isScrollable,
            isCheckable = isCheckable,
            isChecked = isChecked,
            isEnabled = isEnabled,
            isFocusable = isFocusable,
            bounds = bounds,
            children = emptyList(),
            depth = depth
        )
    }

    private fun parseBounds(boundsStr: String?): Bounds? {
        if (boundsStr == null) return null
        // Format: [left,top][right,bottom]
        val regex = """\[(\d+),(\d+)\]\[(\d+),(\d+)\]""".toRegex()
        val match = regex.find(boundsStr) ?: return null
        val (left, top, right, bottom) = match.destructured
        return Bounds(left.toInt(), top.toInt(), right.toInt(), bottom.toInt())
    }
}
