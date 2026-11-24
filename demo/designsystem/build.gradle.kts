plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

apply(from = "../robolectric.gradle.kts")

android {
    namespace = "com.suhel.llamabro.demo.designsystem"
    compileSdk = 37
    defaultConfig {
        minSdk = 29
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { compose = true }
    testOptions {
        // the components' host tests run Compose under Robolectric, which reads the module's resources
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            // Robolectric on a current JDK reaches into java.base's internals
            it.jvmArgs("--add-opens=java.base/jdk.internal.access=ALL-UNNAMED")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint {
        abortOnError = true
        warningsAsErrors = true
        checkDependencies = false
        // the Gradle wrapper (the repository root's, found above this module) is pinned on purpose, and so are the
        // versions in the shared catalog (updated deliberately, not by lint) the Lifecycle library ships a lint check
        // built for a newer lint API than this AGP version loads
        disable +=
            setOf(
                "AndroidGradlePluginVersion",
                "GradleDependency",
                "NewerVersionAvailable",
                "ObsoleteLintCustomCheck",
            )
    }
}

composeCompiler {
    reportsDestination = layout.buildDirectory.dir("compose_compiler")
    metricsDestination = layout.buildDirectory.dir("compose_compiler")
    stabilityConfigurationFiles.add(layout.projectDirectory.file("compose-stability.conf"))
}

dependencies {
    // the design-system rule (D113), run by lint with the rest of its checks
    lintChecks(project(":demo:lint"))
    api(platform(libs.androidx.compose.bom))
    api(libs.androidx.compose.ui)
    api(libs.androidx.compose.ui.graphics)
    api(libs.androidx.compose.material3)
    // the photo viewer sets its dialog window's system bar icons (WindowCompat)
    implementation(libs.androidx.core.ktx)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.activity.compose)

    testImplementation(libs.junit)
    // host Compose tests of the components (layout at large font, notices, ellipsis), under Robolectric
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.androidx.junit)
    testImplementation(libs.robolectric)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    // Compose's ui-test brings espresso 3.5, whose InputManager reflection fails on Android 17.
    androidTestImplementation(libs.androidx.espresso.core)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
