plugins {
    `kotlin-dsl`
}

dependencies {
    // the plugins the conventions configure: their classes only, applied by the modules themselves
    compileOnly(libs.android.gradle.api)
    implementation(libs.kotlinx.bcv)
    implementation(libs.maven.publish.plugin)
}
