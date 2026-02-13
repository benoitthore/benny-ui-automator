package dev.benny.uiautomator.model

data class RecordedStep(
    val action: Action,
    val selector: Selector? = null,
    val params: Map<String, String> = emptyMap(),
    val success: Boolean = true,
    val elapsedMs: Long = 0,
)
