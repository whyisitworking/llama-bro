// What every SDK library module shares: Android 37 to compile, Android 10 (API 29) as the floor, Java 17, and lint with
// warnings as errors. A module adds its namespace and what is its own.
import com.android.build.api.dsl.LibraryExtension

plugins.withId("com.android.library") {
    extensions.configure<LibraryExtension> {
        compileSdk = 37
        defaultConfig { minSdk = 29 }
        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_17
            targetCompatibility = JavaVersion.VERSION_17
        }
        lint {
            abortOnError = true
            warningsAsErrors = true
            checkDependencies = false
        }
    }
}
