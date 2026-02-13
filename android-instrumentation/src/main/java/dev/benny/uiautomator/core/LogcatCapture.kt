package dev.benny.uiautomator.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class LogcatCapture {

    fun stream(): Flow<String> = callbackFlow {
        val process = Runtime.getRuntime().exec(arrayOf("logcat"))
        val reader = process.inputStream.bufferedReader()

        launch(Dispatchers.IO) {
            while (isActive) {
                val line = reader.readLine() ?: break
                trySend(line)
            }
        }

        awaitClose {
            process.destroy()
        }
    }

    fun capture(lines: Int = 500): String {
        val process = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-t", lines.toString()))
        return try {
            process.inputStream.bufferedReader().readText()
        } finally {
            process.destroyForcibly()
        }
    }

    fun clear() {
        val process = Runtime.getRuntime().exec(arrayOf("logcat", "-c"))
        try {
            process.waitFor()
        } finally {
            process.destroyForcibly()
        }
    }
}
