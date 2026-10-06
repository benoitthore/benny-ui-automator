package dev.benny.uiautomator.core

import dev.benny.uiautomator.model.NodeType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScreenSnapshotTest {

    private val snapshot = ScreenSnapshot(
        UiHierarchyParser.parse(javaClass.getResource("/login_screen.xml")!!.readText())
    )

    private fun idOfText(text: String) = snapshot.query(ScreenQuery(text = text)).single()

    @Test
    fun `compact view hides layout wrappers and hoists their children`() {
        val compact = snapshot.renderCompact()
        println(compact)
        assertFalse(compact.contains("FrameLayout"))
        assertTrue(compact.lines().first().startsWith("#3 ImageView id=logo"))
        // Sign-in text sits under the clickable row, one level deep, despite the FrameLayout in between
        assertTrue(compact.lines().any { it.startsWith("  #${idOfText("Sign in")} TextView \"Sign in\"") })
        assertTrue(compact.contains("CheckBox desc=\"Remember me\" id=remember [clickable,checked] @300,950"))
    }

    @Test
    fun `max depth collapses subtrees and node id expands them`() {
        val collapsed = snapshot.renderCompact(maxDepth = 0)
        val list = snapshot.query(ScreenQuery(resourceId = "recents")).single()
        assertTrue(collapsed.contains("… 4 more elements (2 with text, 2 clickable) — expand with node_id=$list"))
        assertFalse(collapsed.contains("alice"))

        val expanded = snapshot.renderCompact(list)
        assertTrue(expanded.contains("alice@example.com"))
        assertFalse(expanded.contains("Welcome back"))
    }

    @Test
    fun `text view lists readable content with tap targets`() {
        val text = snapshot.renderText()
        println(text)
        val signIn = snapshot.query(ScreenQuery(resourceId = "sign_in")).single()
        assertTrue(text.contains("#${idOfText("Sign in")} \"Sign in\" (tap #$signIn)"))
        assertTrue(text.contains("[input] \"Email\" (tappable)"))
        assertEquals(6, text.lines().size)
    }

    @Test
    fun `query combines filters`() {
        assertEquals(2, snapshot.query(ScreenQuery(any = "example.com")).size)
        assertEquals(1, snapshot.query(ScreenQuery(type = NodeType.TEXT_FIELD)).size)
        val list = snapshot.query(ScreenQuery(scrollable = true)).single()
        assertEquals(2, snapshot.query(ScreenQuery(clickable = true, within = list)).size)
        assertTrue(snapshot.renderQuery(ScreenQuery(text = "bob")).contains("→ tap #"))
    }

    @Test
    fun `describe suggests a unique selector or falls back to node id`() {
        val email = snapshot.query(ScreenQuery(resourceId = "email")).single()
        assertTrue(snapshot.describe(email).contains("selector: resource_id=\"email\""))
        // "row" appears twice (and is a substring of row_text), so no unique selector
        val row = snapshot.query(ScreenQuery(resourceId = "id/row", clickable = true)).first()
        assertEquals(null, snapshot.suggestSelector(row))
        println(snapshot.describe(row))
    }
}
