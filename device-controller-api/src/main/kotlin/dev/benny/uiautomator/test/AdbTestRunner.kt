package dev.benny.uiautomator.test

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import dev.benny.uiautomator.core.AdbDeviceInteractor
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

fun adbTest(
    timeout: Duration = 120.seconds,
    block: suspend AdbDeviceInteractor.() -> Unit
) = runBlocking {
    val interactor = AdbDeviceInteractor()
    try {
        withTimeout(timeout) {
            interactor.block()
        }
    } catch (e: Throwable) {
        println("\n!!! TEST FAILED: ${e.message}")
        println("\n--- Screen dump at failure ---")
        try {
            println(interactor.dumpScreen().prettyPrint())
        } catch (dumpErr: Exception) {
            println("(Could not dump screen: ${dumpErr.message})")
        }
        throw e
    }
}

fun main() {
    adbTest {
        println("Screen:")
        println(dumpScreen().prettyPrint())
    }
}
