package dev.benny.uiautomator.model

sealed class InteractionResult {
    data class Success(val message: String = "") : InteractionResult()
    data class ElementNotFound(val selector: Selector) : InteractionResult()
    data class Error(val exception: Throwable) : InteractionResult()

    val isSuccess: Boolean get() = this is Success
}
