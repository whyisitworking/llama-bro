// The SDK's modules, written once. Applied by sdk/settings.gradle.kts (the SDK as its own build, which `./gradlew -p sdk`
// and the demo's `includeBuild("../sdk")` use) and by the repository root's settings.gradle.kts (so Android Studio, which
// opens the root, sees every module and the acceptance apps). Adding a module means one line here.

// where sdk/ is: the folder of this build's settings when the SDK is the build, a subfolder when the root is
val sdkDir: File =
    settings.settingsDir.resolve("sdk").takeIf { it.resolve("modules.gradle.kts").exists() } ?: settings.settingsDir
gradle.extensions.extraProperties["sdkDir"] = sdkDir

listOf(
    ":llamabro",
    ":llamabro-testing",
    ":llamabro-hexagon",
    ":llamabro-opencl",
    ":llamabro-hub",
    ":acceptance",
    ":acceptance:backends",
    ":acceptance:download",
).forEach { path ->
    settings.include(path)
    settings.project(path).projectDir = sdkDir.resolve(path.removePrefix(":").replace(':', '/'))
}
