import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import java.time.Duration

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.advancedvoice"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.advancedvoice"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            applicationIdSuffix = ""
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    packaging {
        resources.excludes.apply {
            add("META-INF/AL2.0")
            add("META-INF/LGPL2.1")
            add("META-INF/LICENSE.md")
            add("META-INF/LICENSE-notice.md")
        }
    }

    // ✅ CRITICAL: Enable JUnit 5
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
            all {
                it.useJUnitPlatform()
            }
        }
    }
}

dependencies {
    // ============================================
    // CORE ANDROID DEPENDENCIES
    // ============================================
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.navigation.fragment.ktx)
    implementation(libs.androidx.navigation.ui.ktx)

    // ============================================
    // NETWORKING
    // ============================================
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // ============================================
    // COROUTINES
    // ============================================
    val coroutinesVersion = "1.8.1"
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutinesVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:$coroutinesVersion")

    // ============================================
    // LIFECYCLE
    // ============================================
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")

    // ============================================
    // TENSORFLOW LITE
    // ============================================
    implementation("org.tensorflow:tensorflow-lite:2.13.0")
    implementation("org.tensorflow:tensorflow-lite-support:0.4.4")

    // ============================================
    // UNIT TESTING DEPENDENCIES
    // ============================================

    // JUnit 5 (Jupiter) - Modern testing framework
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.10.1")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.10.1")
    testImplementation("org.junit.jupiter:junit-jupiter-params:5.10.1")

    // Kotlin Test
    testImplementation("org.jetbrains.kotlin:kotlin-test:1.9.22")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5:1.9.22")

    // MockK - Mocking library for Kotlin
    testImplementation("io.mockk:mockk:1.13.8")
    testImplementation("io.mockk:mockk-android:1.13.8")

    // Coroutines Test
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:$coroutinesVersion")

    // AssertJ - Better assertions (optional but recommended)
    testImplementation("org.assertj:assertj-core:3.24.2")

    // Turbine - Flow testing made easy (optional but very useful)
    testImplementation("app.cash.turbine:turbine:1.0.0")

    // Android Test Core (for Robolectric support if needed)
    testImplementation("androidx.test:core-ktx:1.5.0")

    // ============================================
    // INSTRUMENTED TESTING DEPENDENCIES
    // ============================================

    // JUnit 4 (still needed for Android instrumented tests)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.junit)

    // Espresso
    androidTestImplementation(libs.androidx.espresso.core)

    // Android Test Core
    androidTestImplementation("androidx.test:core-ktx:1.5.0")

    // MockK for instrumented tests
    androidTestImplementation("io.mockk:mockk-android:1.13.8")

    // Coroutines Test for instrumented tests
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:$coroutinesVersion")
}

// ============================================
// TEST CONFIGURATION
// ============================================
tasks.withType<Test> {
    timeout.set(Duration.ofSeconds(10))

    testLogging {
        // ✅ Show all test events in terminal
        events("passed", "failed", "skipped", "standard_out", "standard_error")

        // Show detailed info
        exceptionFormat = TestExceptionFormat.FULL
        showExceptions = true
        showCauses = true
        showStackTraces = true

        // Show which test is running
        showStandardStreams = false
    }

    maxParallelForks = Runtime.getRuntime().availableProcessors().div(2).takeIf { it > 0 } ?: 1
}