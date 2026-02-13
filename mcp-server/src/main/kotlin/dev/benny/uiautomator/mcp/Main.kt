package dev.benny.uiautomator.mcp

import dev.benny.uiautomator.core.AdbDeviceInteractor

fun main() {
    val deviceSerial = System.getenv("ADB_DEVICE_SERIAL")
    val interactor = AdbDeviceInteractor(deviceSerial = deviceSerial)
    runMcpServer(interactor)
}
