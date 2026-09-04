plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
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
        versionCode = 40
        versionName = "0.24.0"
        // Galaxy Tab is arm64. Shipping one ABI keeps the native ink lib small.
        ndk { abiFilters += "arm64-v8a" }
    }

    signingConfigs {
        create("release") {
            // Not a secret worth hiding - this never goes to a store, only
            // sideloaded onto one tablet, and the whole point of a key of its
            // own is that it stays the same key. The debug keystore CI used to
            // sign with is regenerated fresh on every runner, so every release
            // carried a different, random certificate and every install
            // conflicted with the one already on the tablet - this is the fix.
            storeFile = file("../keystore/release.jks")
            storePassword = "notesis-release"
            keyAlias = "notesis"
            keyPassword = "notesis-release"
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }

    kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
}

dependencies {
    implementation("androidx.ink:ink-authoring:1.0.0")
    implementation("androidx.ink:ink-brush:1.0.0")
    implementation("androidx.ink:ink-geometry:1.0.0")
    implementation("androidx.ink:ink-rendering:1.0.0")
    implementation("androidx.ink:ink-strokes:1.0.0")
    implementation("androidx.input:input-motionprediction:1.0.0")
    // Handwriting search: the models are downloaded on demand and the
    // recognition runs on the device.
    implementation("com.google.mlkit:digital-ink-recognition:18.1.0")
    implementation("androidx.ink:ink-storage:1.0.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation(platform("androidx.compose:compose-bom:2026.06.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    // The renderer behind @Preview. Without it the annotations compile and the
    // preview pane stays empty, which is why the chrome could only be looked at
    // by installing the app - see Previews.kt. Debug only: it is a development
    // tool and has no business in a release APK.
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
}
