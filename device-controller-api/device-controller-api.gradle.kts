plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

application {
    mainClass.set("dev.benny.uiautomator.test.AdbTestRunnerKt")
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

dependencies {
    implementation(libs.coroutines.core)
}
