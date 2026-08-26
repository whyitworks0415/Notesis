plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.notesis"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.notesis"
        // Front-buffered rendering (the low-latency wet-ink path) is only
        // dependable from Q onward, even though ink itself declares minSdk 23.
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
        // Galaxy Tab is arm64. Shipping one ABI keeps the native ink lib small.
        ndk { abiFilters += "arm64-v8a" }
    }

    buildTypes {
        release {
            // Debug key for now: v0.1.0 only has to sideload onto the author's
            // own Galaxy Tab. Swap in a real keystore before any store upload.
            signingConfig = signingConfigs.getByName("debug")
            // R8 stays off until the spike has actually been run on a device -
            // a minified build that crashes at runtime would be blamed on ink.
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
}

dependencies {
    implementation("androidx.ink:ink-authoring:1.0.0")
    implementation("androidx.ink:ink-brush:1.0.0")
    implementation("androidx.ink:ink-geometry:1.0.0")
    implementation("androidx.ink:ink-rendering:1.0.0")
    implementation("androidx.ink:ink-strokes:1.0.0")
    implementation("androidx.input:input-motionprediction:1.0.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
}
