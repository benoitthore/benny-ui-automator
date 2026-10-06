package dev.benny.uiautomator.core

import dev.benny.uiautomator.model.NodeType
import dev.benny.uiautomator.model.ScreenNode
import java.util.IdentityHashMap

/**
 * An indexed view of a [ScreenNode] tree, built for agents that need to explore a screen
 * without reading the whole hierarchy.
 *
 * Every node gets a short numeric id (pre-order index, `#0` is the root). Ids are only valid
 * for the snapshot they came from.
 *
 * Most raw nodes are layout wrappers carrying nothing an agent cares about. The "compact" views
 * hide those and hoist their children up, so only *meaningful* nodes are shown: nodes with text
 * or a content description, interactive nodes, inputs, and images that can be referenced.
 */
class ScreenSnapshot(val root: ScreenNode, val version: Int = 0) {

    private val nodes = mutableListOf<ScreenNode>()
    private val parents = mutableListOf<Int>()
    private val ids = IdentityHashMap<ScreenNode, Int>()

    init {
        index(root, parent = -1)
    }

    private fun index(node: ScreenNode, parent: Int) {
        val id = nodes.size
        nodes += node
        parents += parent
        ids[node] = id
        node.children.forEach { index(it, id) }
    }

    val size: Int get() = nodes.size

    fun node(id: Int): ScreenNode? = nodes.getOrNull(id)

    fun idOf(node: ScreenNode): Int = ids[node] ?: error("Node is not part of this snapshot")

    fun parentOf(id: Int): Int? = parents.getOrNull(id)?.takeIf { it >= 0 }

    /** Ancestor ids, nearest first. */
    fun ancestors(id: Int): List<Int> = generateSequence(parentOf(id)) { parentOf(it) }.toList()

    fun isMeaningful(node: ScreenNode): Boolean =
        !node.text.isNullOrBlank() ||
            !node.contentDescription.isNullOrBlank() ||
            node.isClickable || node.isScrollable || node.isCheckable ||
            node.nodeType == NodeType.TEXT_FIELD ||
            (node.nodeType == NodeType.IMAGE && !node.resourceId.isNullOrBlank())

    /** Nearest meaningful descendants of [id], looking through wrapper nodes. */
    fun meaningfulChildren(id: Int): List<Int> =
        nodes[id].children.flatMap { child ->
            if (isMeaningful(child)) listOf(idOf(child)) else meaningfulChildren(idOf(child))
        }

    /** Nearest clickable ancestor (or the node itself) — i.e. what actually receives a tap. */
    fun tapTarget(id: Int): Int? =
        (listOf(id) + ancestors(id)).firstOrNull { nodes[it].isClickable }

    // --- Rendering ---

    fun header(): String {
        val shown = (0 until size).count { isMeaningful(nodes[it]) }
        return "Snapshot v$version · $size raw nodes, $shown meaningful · ids are #n (valid for this snapshot only)"
    }

    /**
     * Meaningful nodes only, indented by meaningful depth. Subtrees deeper than [maxDepth] are
     * collapsed to a one-line summary that can be expanded by calling again with that node id.
     */
    fun renderCompact(fromId: Int = 0, maxDepth: Int = Int.MAX_VALUE, maxLines: Int = 200): String {
        val lines = mutableListOf<String>()
        var truncated = false

        fun visit(id: Int, depth: Int) {
            if (lines.size >= maxLines) { truncated = true; return }
            val prefix = "  ".repeat(depth)
            lines += prefix + formatLine(id)
            val children = meaningfulChildren(id)
            if (children.isEmpty()) return
            if (depth + 1 > maxDepth) {
                lines += "$prefix  … ${collapsedSummary(id)} — expand with node_id=$id"
                return
            }
            children.forEach { visit(it, depth + 1) }
        }

        val start = nodes[fromId]
        if (fromId == 0 && !isMeaningful(start)) {
            meaningfulChildren(0).forEach { visit(it, 0) }
        } else {
            visit(fromId, 0)
        }
        if (truncated) lines += "… output truncated at $maxLines lines — narrow down with node_id, max_depth or find_elements"
        if (lines.isEmpty()) lines += "(no meaningful elements on screen)"
        return lines.joinToString("\n")
    }

    /**
     * Only the readable content (text, descriptions, labelled images), in screen order. Each line
     * notes which node to tap when the text itself is inside a clickable container.
     */
    fun renderText(fromId: Int = 0, maxLines: Int = 200): String {
        val subtree = subtreeIds(fromId)
        val lines = subtree.mapNotNull { id ->
            val n = nodes[id]
            val label = listOfNotNull(
                n.text?.takeIf { it.isNotBlank() }?.let { "\"${it.oneLine()}\"" },
                n.contentDescription?.takeIf { it.isNotBlank() }?.let { "desc=\"${it.oneLine()}\"" },
            )
            if (label.isEmpty()) return@mapNotNull null
            val kind = when (n.nodeType) {
                NodeType.IMAGE -> "[image] "
                NodeType.TEXT_FIELD -> "[input] "
                NodeType.BUTTON -> "[button] "
                else -> ""
            }
            val tap = tapTarget(id)?.let { if (it == id) " (tappable)" else " (tap #$it)" } ?: ""
            "#$id $kind${label.joinToString(" ")}$tap"
        }
        if (lines.isEmpty()) return "(no text on screen)"
        return if (lines.size > maxLines) {
            (lines.take(maxLines) + "… ${lines.size - maxLines} more lines truncated").joinToString("\n")
        } else lines.joinToString("\n")
    }

    /** Every raw node (like the original XML), with ids. Verbose — prefer [renderCompact]. */
    fun renderFull(fromId: Int = 0, maxDepth: Int = Int.MAX_VALUE, maxLines: Int = 500): String {
        val lines = mutableListOf<String>()
        fun visit(id: Int, depth: Int) {
            if (lines.size >= maxLines) return
            lines += "  ".repeat(depth) + formatLine(id, includeBounds = true, includeClass = true)
            if (depth < maxDepth) nodes[id].children.forEach { visit(idOf(it), depth + 1) }
        }
        visit(fromId, 0)
        if (lines.size >= maxLines) lines += "… output truncated at $maxLines lines"
        return lines.joinToString("\n")
    }

    /** One-line description of a node: `#12 Button "Sign in" id=login [clickable] @540,1200`. */
    fun formatLine(id: Int, includeBounds: Boolean = false, includeClass: Boolean = false): String {
        val n = nodes[id]
        return buildList {
            add("#$id")
            add(if (includeClass) n.className.ifBlank { "?" } else n.shortClass())
            n.text?.takeIf { it.isNotBlank() }?.let { add("\"${it.oneLine().truncate(80)}\"") }
            n.contentDescription?.takeIf { it.isNotBlank() }?.let { add("desc=\"${it.oneLine().truncate(80)}\"") }
            n.resourceId?.takeIf { it.isNotBlank() }?.let { add("id=${it.shortResourceId()}") }
            val flags = buildList {
                if (n.isClickable) add("clickable")
                if (n.isScrollable) add("scrollable")
                if (n.isCheckable) add(if (n.isChecked) "checked" else "unchecked")
                if (!n.isEnabled) add("disabled")
            }
            if (flags.isNotEmpty()) add("[${flags.joinToString(",")}]")
            n.bounds?.let { b ->
                if (includeBounds) add("[${b.left},${b.top}][${b.right},${b.bottom}]")
                else if (n.isClickable || n.isScrollable || n.nodeType == NodeType.TEXT_FIELD) {
                    add("@${b.centerX()},${b.centerY()}")
                }
            }
        }.joinToString(" ")
    }

    /** Everything known about one node: attributes, where it sits, what's inside, how to target it. */
    fun describe(id: Int): String {
        val n = nodes[id]
        return buildString {
            appendLine(formatLine(id))
            appendLine("class: ${n.className}")
            appendLine("type: ${n.nodeType}")
            n.text?.let { appendLine("text: \"$it\"") }
            n.contentDescription?.let { appendLine("content-desc: \"$it\"") }
            n.resourceId?.let { appendLine("resource-id: $it") }
            n.bounds?.let {
                appendLine("bounds: [${it.left},${it.top}][${it.right},${it.bottom}] " +
                    "(${it.width()}x${it.height()}, center ${it.centerX()},${it.centerY()})")
            }
            appendLine(
                "state: clickable=${n.isClickable} scrollable=${n.isScrollable} checkable=${n.isCheckable} " +
                    "checked=${n.isChecked} enabled=${n.isEnabled} focusable=${n.isFocusable}"
            )
            tapTarget(id)?.takeIf { it != id }?.let { appendLine("tap target: #$it (nearest clickable ancestor)") }
            appendLine("selector: ${suggestSelector(id) ?: "no unique selector — use tap with node_id=$id"}")

            val path = ancestors(id).filter { isMeaningful(nodes[it]) }.reversed()
            if (path.isNotEmpty()) {
                appendLine("inside:")
                path.forEach { appendLine("  ${formatLine(it)}") }
            }
            val children = meaningfulChildren(id)
            if (children.isNotEmpty()) {
                appendLine("contains:")
                children.take(50).forEach { child ->
                    renderCompact(child, maxDepth = 0).lines().forEach { appendLine("  $it") }
                }
                if (children.size > 50) appendLine("  … ${children.size - 50} more")
            }
        }.trimEnd()
    }

    /**
     * The simplest selector (same matching rules as the interactors) that resolves to this node,
     * e.g. `resource_id=login`. Null when no attribute uniquely identifies it.
     */
    fun suggestSelector(id: Int): String? {
        val n = nodes[id]
        val all = nodes
        n.resourceId?.takeIf { it.isNotBlank() }?.shortResourceId()?.let { rid ->
            if (all.count { it.resourceId?.contains(rid) == true } == 1) return "resource_id=\"$rid\""
        }
        n.text?.takeIf { it.isNotBlank() }?.let { t ->
            if (all.count { it.text == t } == 1) return "text=\"$t\""
        }
        n.contentDescription?.takeIf { it.isNotBlank() }?.let { d ->
            if (all.count { it.contentDescription == d } == 1) return "description=\"$d\""
        }
        return null
    }

    // --- Querying ---

    fun query(q: ScreenQuery): List<Int> =
        subtreeIds(q.within ?: 0).filter { id -> q.matches(nodes[id]) }

    fun renderQuery(q: ScreenQuery, limit: Int = 50): String {
        val hits = query(q)
        if (hits.isEmpty()) return "No elements match $q"
        return buildString {
            appendLine("${hits.size} match${if (hits.size == 1) "" else "es"}" +
                if (hits.size > limit) " (showing first $limit)" else "")
            hits.take(limit).forEach { id ->
                append(formatLine(id))
                val parent = ancestors(id).firstOrNull { isMeaningful(nodes[it]) }
                tapTarget(id)?.takeIf { it != id }?.let { append("  → tap #$it") }
                parent?.let { append("  in #$it ${nodes[it].shortClass()}") }
                appendLine()
            }
        }.trimEnd()
    }

    // --- Internals ---

    private fun subtreeIds(id: Int): List<Int> =
        listOf(id) + nodes[id].children.flatMap { subtreeIds(idOf(it)) }

    private fun collapsedSummary(id: Int): String {
        val descendants = subtreeIds(id).drop(1).map { nodes[it] }.filter(::isMeaningful)
        val withText = descendants.count { !it.text.isNullOrBlank() || !it.contentDescription.isNullOrBlank() }
        val clickable = descendants.count { it.isClickable }
        return "${descendants.size} more elements ($withText with text, $clickable clickable)"
    }
}

/** Filter for [ScreenSnapshot.query]. All set fields must match; string matches are case-insensitive "contains". */
data class ScreenQuery(
    val any: String? = null,
    val text: String? = null,
    val description: String? = null,
    val resourceId: String? = null,
    val className: String? = null,
    val type: NodeType? = null,
    val clickable: Boolean? = null,
    val scrollable: Boolean? = null,
    val within: Int? = null,
) {
    fun matches(n: ScreenNode): Boolean =
        (any == null || n.text.has(any) || n.contentDescription.has(any) || n.resourceId.has(any)) &&
            (text == null || n.text.has(text)) &&
            (description == null || n.contentDescription.has(description)) &&
            (resourceId == null || n.resourceId.has(resourceId)) &&
            (className == null || n.className.has(className)) &&
            (type == null || n.nodeType == type) &&
            (clickable == null || n.isClickable == clickable) &&
            (scrollable == null || n.isScrollable == scrollable)

    private fun String?.has(needle: String) = this?.contains(needle, ignoreCase = true) == true

    override fun toString(): String = buildList {
        any?.let { add("any~\"$it\"") }
        text?.let { add("text~\"$it\"") }
        description?.let { add("desc~\"$it\"") }
        resourceId?.let { add("id~\"$it\"") }
        className?.let { add("class~\"$it\"") }
        type?.let { add("type=$it") }
        clickable?.let { add("clickable=$it") }
        scrollable?.let { add("scrollable=$it") }
        within?.let { add("within=#$it") }
    }.joinToString(" ", prefix = "{", postfix = "}")
}

private fun ScreenNode.shortClass(): String = className.substringAfterLast('.').ifBlank { "?" }

private fun String.shortResourceId(): String = substringAfter(":id/")

private fun String.oneLine(): String = replace(Regex("\\s*\\n\\s*"), " ⏎ ")

private fun String.truncate(max: Int): String = if (length <= max) this else take(max - 1) + "…"
