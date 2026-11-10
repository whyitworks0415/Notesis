plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.notesis"
    compileSdk = 36
    ndkVersion = "28.2.13676358"
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }

    defaultConfig {
        applicationId = "com.notesis"
        // Front-buffered rendering (the low-latency wet-ink path) is only
        // dependable from Q onward, even though ink itself declares minSdk 23.
        minSdk = 29
        targetSdk = 36
        versionCode = 222
        versionName = "0.90.22"
        testInstrumentationRunner = "com.notesis.CustomizationInstrumentation"
        // Ship arm64 for the tablet; opt into x86_64 for emulator GPU checks.
        ndk { abiFilters += providers.gradleProperty("notesis.testAbi").getOrElse("arm64-v8a") }
    }

    buildTypes {
        release {
            // Built unsigned. The key is not in this repository: the release
            // workflow signs the APK with it afterwards, from a secret, with
            // apksigner (see docs/signing.md). An earlier version kept the
            // keystore and its password here, in the open.
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

    testOptions {
        unitTests {
            // The screenshot tests (src/testDebug) draw real Compose screens
            // under Robolectric, which needs the app's resources and themes.
            isIncludeAndroidResources = true
            all {
                it.systemProperty("roborazzi.test.record", "true")
                it.systemProperty("robolectric.pixelCopyRenderMode", "hardware")
                providers.gradleProperty("notesis.hostNativeDir").orNull?.let { dir ->
                    it.systemProperty("java.library.path", dir)
                }
            }
        }
    }
}

dependencies {
    implementation("androidx.ink:ink-authoring:1.0.0")
    implementation("androidx.ink:ink-brush:1.0.0")
    implementation("androidx.ink:ink-geometry:1.0.0")
    implementation("androidx.ink:ink-rendering:1.0.0")
    implementation("androidx.ink:ink-strokes:1.0.0")
    implementation("androidx.input:input-motionprediction:1.0.0")
    // Export keeps imported PDF pages and handwriting as vector content instead
    // of flattening both into a bitmap before another note imports the result.
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    // Handwriting search: the models are downloaded on demand and the
    // recognition runs on the device.
    implementation("com.google.mlkit:digital-ink-recognition:18.1.0")
    implementation("androidx.ink:ink-storage:1.0.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    // Installs the baseline profiles - the app's own (src/main/baseline-prof.txt)
    // and those the Compose libraries ship - on a sideloaded install too, so
    // the code they name is compiled ahead of time instead of interpreted for
    // the first launches. The Play Store would do this; GitHub releases do not.
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    // Automatic backup wakes now and then on its own, off unless switched on.
    implementation("androidx.work:work-runtime:2.10.0")
    implementation(platform("androidx.compose:compose-bom:2026.06.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    // Backdrop 2.x supplies the API 33+ AGSL refraction path used by the
    // reusable Liquid Glass controls. Older Android releases use our opaque
    // fallback and never enter the runtime-shader path.
    // 1.0.6 is the newest Backdrop release compatible with this project's
    // compileSdk 36 / AGP 8 line. Backdrop 2.x requires compileSdk 37 and AGP
    // 9.1+, which would turn this visual change into a toolchain migration.
    implementation("io.github.kyant0:backdrop:1.0.6")
    // The renderer behind @Preview. Without it the annotations compile and the
    // preview pane stays empty, which is why the chrome could only be looked at
    // by installing the app - see Previews.kt. Debug only: it is a development
    // tool and has no business in a release APK.
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    // Screenshots of the library's pieces without a device: Robolectric draws
    // the Compose screens on the JVM and Roborazzi saves them as pictures,
    // which the check build keeps (see .github/workflows/apk.yml).
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.77.0")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.77.0")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    // The real org.json for JVM tests: android.jar only has stubs that throw,
    // and the layer list is saved as JSON.
    testImplementation("org.json:json:20231013")
}
