import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent
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

    // ============================================
    // TEST CONFIGURATION
    // ============================================
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true

            all {
                it.useJUnitPlatform()

                // ✅ CONCISE TEST OUTPUT - Only show failures
                it.testLogging {
                    // Only show failures and skipped tests
                    events(
                        TestLogEvent.FAILED,
                        TestLogEvent.SKIPPED
                    )

                    // Exception details - SHORT is more concise than FULL
                    exceptionFormat = TestExceptionFormat.SHORT
                    showExceptions = true
                    showCauses = true
                    showStackTraces = true
                    showStandardStreams = false

                    // Don't show individual test details unless they fail
                    displayGranularity = 2
                }

                // ✅ Show summary after each test suite
                it.addTestListener(object : TestListener {
                    override fun beforeSuite(suite: TestDescriptor) {}
                    override fun beforeTest(testDescriptor: TestDescriptor) {}
                    override fun afterTest(testDescriptor: TestDescriptor, result: TestResult) {}

                    override fun afterSuite(suite: TestDescriptor, result: TestResult) {
                        if (suite.parent == null) { // Root suite only
                            val output = """
                                |
                                |================================================================================
                                |  TEST SUMMARY
                                |================================================================================
                                |  Total:  ${result.testCount}
                                |  Passed: ${result.successfulTestCount} ✓
                                |  Failed: ${result.failedTestCount} ${if (result.failedTestCount > 0) "✗" else ""}
                                |  Skipped: ${result.skippedTestCount}
                                |  Time:   ${(result.endTime - result.startTime) / 1000}s
                                |================================================================================
                                |  Result: ${result.resultType}
                                |================================================================================
                                |
                            """.trimMargin()
                            println(output)
                        }
                    }
                })

                // Test timeout
                it.timeout.set(Duration.ofSeconds(10))

                // Parallel execution
                it.maxParallelForks = Runtime.getRuntime().availableProcessors().div(2).takeIf { it > 0 } ?: 1
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

    // AssertJ - Better assertions
    testImplementation("org.assertj:assertj-core:3.24.2")

    // Turbine - Flow testing
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
// CUSTOM TEST TASKS
// ============================================

// Task to run tests with verbose output (shows everything)
tasks.register("testVerbose") {
    group = "verification"
    description = "Run tests with full output"
    dependsOn("testDebugUnitTest")

    doFirst {
        tasks.withType<Test> {
            testLogging {
                events(
                    TestLogEvent.PASSED,
                    TestLogEvent.FAILED,
                    TestLogEvent.SKIPPED,
                    TestLogEvent.STANDARD_OUT,
                    TestLogEvent.STANDARD_ERROR
                )
                exceptionFormat = TestExceptionFormat.FULL
                showExceptions = true
                showCauses = true
                showStackTraces = true
                showStandardStreams = true
            }
        }
    }
}

// Task to run tests with minimal output (only failures)
tasks.register("testQuiet") {
    group = "verification"
    description = "Run tests with minimal output (failures only)"
    dependsOn("testDebugUnitTest")

    doFirst {
        tasks.withType<Test> {
            testLogging {
                events(TestLogEvent.FAILED)
                exceptionFormat = TestExceptionFormat.SHORT
                showExceptions = true
                showCauses = true
                showStackTraces = false
                showStandardStreams = false
            }
        }
    }
}