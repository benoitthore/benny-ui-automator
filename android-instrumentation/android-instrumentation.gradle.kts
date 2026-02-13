plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin)
}

android {
    namespace = "dev.benny.uiautomator"
    compileSdk = libs.versions.compileSdk.get().toInt()

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_19
        targetCompatibility = JavaVersion.VERSION_19
    }

    kotlinOptions {
        jvmTarget = "19"
    }

    defaultConfig {
        minSdk = 28
    }
}

dependencies {
    implementation(project(":device-controller-api"))
    implementation(libs.uiautomator)
    implementation(libs.androidx.junit)
    implementation(libs.androidx.test.runner)
    implementation(libs.junit)
    implementation(libs.coroutines.test)
    implementation(libs.coroutines.core)
    implementation("androidx.lifecycle:lifecycle-common:2.8.3")
}
