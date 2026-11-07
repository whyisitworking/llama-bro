plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
}

// The optional backends' native libraries (P2 T3/T4), built in Qualcomm's Docker image by one script for every
// backend module; up to date while the patched copy, the CMake file and the script are (findings F62).
tasks.register<Exec>("buildBackends") {
    description = "Builds the Hexagon and OpenCL backend libraries in the Snapdragon Docker image, and Vulkan on the host"
    val native = rootDir.resolve("native")
    dependsOn(":llamabro:prepareLlama")
    inputs.file(native.resolve("build/llama.cpp/.lb-stamp"))
    inputs.file(native.resolve("CMakeLists.txt"))
    inputs.file(native.resolve("scripts/build-backends.sh"))
    inputs.file(native.resolve("scripts/fetch-deps.sh"))
    outputs.dir(native.resolve("build/android-backends"))
    commandLine("bash", native.resolve("scripts/build-backends.sh").path)
}
