package dev.benny.uiautomator.dsl

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import dev.benny.uiautomator.core.InstrumentationDeviceInteractor
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

fun deviceTest(
    timeout: Duration = 120.seconds,
    block: suspend InstrumentationDeviceInteractor.() -> Unit
) = runBlocking {
    withTimeout(timeout) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val interactor = InstrumentationDeviceInteractor(instrumentation)
        interactor.block()
    }
}
