package dev.benny.uiautomator.core

import org.w3c.dom.Element
import org.w3c.dom.Node
import dev.benny.uiautomator.model.Bounds
import dev.benny.uiautomator.model.NodeType
import dev.benny.uiautomator.model.ScreenNode
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory

object UiHierarchyParser {

    fun parse(xml: String): ScreenNode {
        val factory = DocumentBuilderFactory.newInstance()
        val builder = factory.newDocumentBuilder()
        val document = builder.parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
        val root = document.documentElement

        val children = parseChildren(root, depth = 0)
        return if (children.size == 1) {
            children.first()
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
                children = children,
                depth = 0
            )
        }
    }

    private fun parseChildren(parent: Node, depth: Int): List<ScreenNode> {
        val result = mutableListOf<ScreenNode>()
        val childNodes = parent.childNodes
        for (i in 0 until childNodes.length) {
            val child = childNodes.item(i)
            if (child is Element && child.tagName == "node") {
                result.add(parseElement(child, depth))
            }
        }
        return result
    }

    private fun parseElement(element: Element, depth: Int): ScreenNode {
        val className = element.getAttribute("class") ?: ""
        val resourceId = element.getAttribute("resource-id")?.takeIf { it.isNotBlank() }
        val text = element.getAttribute("text")?.takeIf { it.isNotBlank() }
        val contentDescription = element.getAttribute("content-desc")?.takeIf { it.isNotBlank() }
        val isClickable = element.getAttribute("clickable") == "true"
        val isScrollable = element.getAttribute("scrollable") == "true"
        val isCheckable = element.getAttribute("checkable") == "true"
        val isChecked = element.getAttribute("checked") == "true"
        val isEnabled = element.getAttribute("enabled") != "false"
        val isFocusable = element.getAttribute("focusable") == "true"
        val bounds = parseBounds(element.getAttribute("bounds"))

        val children = parseChildren(element, depth + 1)

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
            children = children,
            depth = depth
        )
    }

    private fun parseBounds(boundsStr: String?): Bounds? {
        if (boundsStr.isNullOrBlank()) return null
        val regex = """\[(\d+),(\d+)\]\[(\d+),(\d+)\]""".toRegex()
        val match = regex.find(boundsStr) ?: return null
        val (left, top, right, bottom) = match.destructured
        return Bounds(left.toInt(), top.toInt(), right.toInt(), bottom.toInt())
    }
}
