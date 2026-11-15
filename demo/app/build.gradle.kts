plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.androidx.baselineprofile)
}

android {
    namespace = "com.suhel.llamabro.demo"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.suhel.llamabro.demo"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        ndk { abiFilters += "arm64-v8a" }
        testInstrumentationRunner = "com.suhel.llamabro.demo.testing.DemoTestRunner"
    }

    testOptions {
        // trace sections (androidx.tracing) call android.os.Trace, which does nothing off a phone
        unitTests.isReturnDefaultValues = true
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    buildTypes {
        debug {
            // the coverage gate (scripts/coverage.py) reads what the unit tests and the device tests cover together
            enableUnitTestCoverage = true
            enableAndroidTestCoverage = true
        }
        release {
            // minified like a real release; signed with the debug key so it installs here
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
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
        // targetSdk 36 is the newest AGP 9.1.0 supports; the app is arm64-v8a only by design (demo design §3)
        // the Gradle wrapper (now the repository root's, which lint finds above the demo) is pinned on purpose, and so are the versions in the shared catalog (updated deliberately, not by lint)
        disable +=
            setOf(
                "OldTargetApi",
                "ChromeOsAbiSupport",
                "AndroidGradlePluginVersion",
                "GradleDependency",
                "NewerVersionAvailable",
                // the Lifecycle library ships a lint check built for a newer lint API than this AGP version loads
                "ObsoleteLintCustomCheck",
            )
    }
}

// fakes used by both the JVM tests and the phone tests (FakeHubGateway, FakeScheduler)
android.sourceSets {
    getByName("test").kotlin.directories.add("src/sharedTest/kotlin")
    getByName("androidTest").kotlin.directories.add("src/sharedTest/kotlin")
    // the exported Room schemas, for the migration test
    getByName("androidTest").assets.directories.add("$projectDir/schemas")
}

ksp { arg("room.schemaLocation", "$projectDir/schemas") }

dependencies {
    implementation(project(":demo:designsystem"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.tracing.ktx)
    implementation(libs.androidx.compose.runtime.tracing)
    implementation(libs.androidx.profileinstaller)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.datastore.preferences)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    // markdown (D74): CommonMark and the GFM tables, strikethrough and bare-URL links
    implementation(libs.commonmark)
    implementation(libs.commonmark.ext.gfm.tables)
    implementation(libs.commonmark.ext.gfm.strikethrough)
    implementation(libs.commonmark.ext.autolink)

    // the SDK's modules, from the same build
    implementation(project(":llamabro"))
    implementation(project(":llamabro-hub"))
    implementation(project(":llamabro-hexagon"))
    implementation(project(":llamabro-opencl"))
    testImplementation(project(":llamabro-testing"))

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.room.testing)
    testImplementation(libs.androidx.work.testing)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.work.testing)
    androidTestImplementation(libs.room.testing)
    androidTestImplementation(libs.androidx.test.uiautomator)
    androidTestImplementation(project(":llamabro-testing"))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4.accessibility)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.leakcanary.android)
    androidTestImplementation(libs.leakcanary.android.instrumentation)
}

// the benchmark and profile-generating builds run over the SDK's fake engine and seeded chats (src/benchmark); the
// baseline profile plugin makes their build types after this script runs, so they are configured as they appear
val benchmarkBuilds = setOf("benchmarkRelease", "nonMinifiedRelease")
android.sourceSets.configureEach {
    if (name in benchmarkBuilds) {
        kotlin.directories.add("src/benchmark/kotlin")
    }
}
configurations.configureEach {
    if (name in benchmarkBuilds.map { "${it}Implementation" }) {
        dependencies.add(project.dependencies.create(project(":llamabro-testing")))
    }
}

dependencies {
    baselineProfile(project(":demo:benchmark"))
}
