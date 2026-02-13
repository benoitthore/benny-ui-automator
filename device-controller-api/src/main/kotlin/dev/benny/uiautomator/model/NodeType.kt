package dev.benny.uiautomator.model

enum class NodeType {
    BUTTON,
    TEXT,
    TEXT_FIELD,
    IMAGE,
    CHECKBOX,
    SWITCH,
    SCROLLABLE,
    CLICKABLE,
    CONTAINER,
    UNKNOWN;

    companion object {
        fun from(className: String, isClickable: Boolean, isScrollable: Boolean): NodeType {
            val lower = className.lowercase()
            return when {
                lower.contains("button") -> BUTTON
                lower.endsWith("edittext") || lower.endsWith("textfield") -> TEXT_FIELD
                lower.contains("checkbox") -> CHECKBOX
                lower.contains("switch") || lower.contains("toggle") -> SWITCH
                lower.contains("imageview") || lower.contains("image") -> IMAGE
                lower.endsWith("textview") -> TEXT
                isScrollable -> SCROLLABLE
                isClickable -> CLICKABLE
                lower.contains("layout") || lower.contains("view") ||
                    lower.contains("frame") || lower.contains("group") -> CONTAINER
                else -> UNKNOWN
            }
        }
    }
}
