package dev.benny.uiautomator.model

data class ScreenNode(
    val className: String,
    val nodeType: NodeType,
    val resourceId: String?,
    val text: String?,
    val contentDescription: String?,
    val isClickable: Boolean,
    val isScrollable: Boolean,
    val isCheckable: Boolean,
    val isChecked: Boolean,
    val isEnabled: Boolean,
    val isFocusable: Boolean,
    val bounds: Bounds?,
    val children: List<ScreenNode>,
    val depth: Int
) {
    val displayName: String
        get() = text?.takeIf { it.isNotBlank() }
            ?: contentDescription?.takeIf { it.isNotBlank() }
            ?: resourceId?.takeIf { it.isNotBlank() }
            ?: className

    // Tree

    fun flatten(): List<ScreenNode> =
        listOf(this) + children.flatMap { it.flatten() }

    fun prettyPrint(indent: Int = 0): String = buildString {
        val prefix = "  ".repeat(indent)
        val info = buildList {
            add(className)
            text?.takeIf { it.isNotBlank() }?.let { add("text=\"$it\"") }
            contentDescription?.takeIf { it.isNotBlank() }?.let { add("desc=\"$it\"") }
            resourceId?.takeIf { it.isNotBlank() }?.let { add("id=\"$it\"") }
            if (isClickable) add("clickable")
            if (isScrollable) add("scrollable")
            if (isCheckable) add("checkable")
            if (isChecked) add("checked")
            bounds?.let { add("bounds=[${it.left},${it.top}][${it.right},${it.bottom}]") }
        }.joinToString(" | ")
        appendLine("$prefix$info")
        children.forEach { append(it.prettyPrint(indent + 1)) }
    }

    // Finding

    fun findByResourceId(id: String): List<ScreenNode> =
        flatten().filter { it.resourceId?.contains(id) == true }

    fun findByText(text: String, contains: Boolean = false): List<ScreenNode> =
        flatten().filter { node ->
            if (contains) node.text?.contains(text, ignoreCase = true) == true
            else node.text == text
        }

    fun findByDescription(desc: String, contains: Boolean = false): List<ScreenNode> =
        flatten().filter { node ->
            if (contains) node.contentDescription?.contains(desc, ignoreCase = true) == true
            else node.contentDescription == desc
        }

    fun findByType(type: NodeType): List<ScreenNode> =
        flatten().filter { it.nodeType == type }

    fun findClickable(): List<ScreenNode> =
        flatten().filter { it.isClickable }

    fun findScrollable(): List<ScreenNode> =
        flatten().filter { it.isScrollable }

    fun find(predicate: (ScreenNode) -> Boolean): List<ScreenNode> =
        flatten().filter(predicate)

    // Contains — using Selector

    fun contains(selector: Selector): Boolean = flatten().any { node -> node.matches(selector) }

    fun contains(predicate: (ScreenNode) -> Boolean): Boolean = flatten().any(predicate)

    // Convert to Selector

    fun toSelector(): Selector = when {
        !resourceId.isNullOrBlank() -> Selector.ByResourceId(resourceId)
        !text.isNullOrBlank() -> Selector.ByText(text)
        !contentDescription.isNullOrBlank() -> Selector.ByDescription(contentDescription)
        else -> Selector.ByClassName(className)
    }

    private fun matches(selector: Selector): Boolean = when (selector) {
        is Selector.ByResourceId -> resourceId?.contains(selector.id) == true
        is Selector.ByText -> if (selector.exact) text == selector.text
        else text?.contains(selector.text, ignoreCase = true) == true
        is Selector.ByClassName -> className == selector.className
        is Selector.ByDescription -> contentDescription == selector.description
    }
}
