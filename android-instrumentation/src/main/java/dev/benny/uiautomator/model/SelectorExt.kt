package dev.benny.uiautomator.model

import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import java.util.regex.Pattern

internal fun Selector.toBySelector(): BySelector = when (this) {
    is Selector.ByResourceId -> By.res(Pattern.compile(".*${Pattern.quote(id)}.*"))
    is Selector.ByText -> if (exact) By.text(text) else By.textContains(text)
    is Selector.ByClassName -> By.clazz(className)
    is Selector.ByDescription -> By.desc(description)
}
