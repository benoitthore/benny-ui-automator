package dev.benny.uiautomator.model

sealed class Selector {
    data class ByResourceId(val id: String) : Selector()
    data class ByText(val text: String, val exact: Boolean = true) : Selector()
    data class ByClassName(val className: String) : Selector()
    data class ByDescription(val description: String) : Selector()

    override fun toString(): String = when (this) {
        is ByResourceId -> "ByResourceId($id)"
        is ByText -> "ByText($text, exact=$exact)"
        is ByClassName -> "ByClassName($className)"
        is ByDescription -> "ByDescription($description)"
    }
}

val String.text get() = Selector.ByText(this)
val String.id get() = Selector.ByResourceId(this)
val String.desc get() = Selector.ByDescription(this)
val String.className get() = Selector.ByClassName(this)
fun String.text(exact: Boolean) = Selector.ByText(this, exact)