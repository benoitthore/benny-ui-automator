plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.shadow)
    application
}

java {
    sourceCompatibility = JavaVersion.VERSION_19
    targetCompatibility = JavaVersion.VERSION_19
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_19)
    }
}

application {
    mainClass.set("dev.benny.uiautomator.mcp.MainKt")
}

dependencies {
    implementation(project(":device-controller-api"))
    implementation(platform(libs.ktor.bom))
    implementation(libs.mcp.kotlin.server)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.client.cio)
    implementation(libs.slf4j.simple)
    runtimeOnly(libs.kotlin.logging)
    runtimeOnly(libs.kotlinx.collections.immutable)
}
