// Public API dump (findings F45), for each published library module: `apiCheck` compares the release build's public
// API with the checked-in api/<module>.api, and `apiDump` updates it. BCV's plugin would set these up itself only for
// the org.jetbrains.kotlin.android plugin, which AGP 9's built-in Kotlin doesn't apply, so its tasks are wired here to
// the release variant's classes through AGP's variant API.
import com.android.build.api.artifact.ScopedArtifact
import com.android.build.api.variant.LibraryAndroidComponentsExtension
import com.android.build.api.variant.ScopedArtifacts
import kotlinx.validation.KotlinApiBuildTask
import kotlinx.validation.KotlinApiCompareTask

val catalog = extensions.getByType<VersionCatalogsExtension>().named("libs")
val bcvRuntime: Configuration by configurations.creating {
    isCanBeConsumed = false
    description = "BCV's worker classpath (the same artifacts its plugin resolves)"
}
dependencies {
    for (alias in listOf("kotlinx-bcv", "bcv-asm", "bcv-asm-tree", "kotlin-metadata-jvm")) {
        bcvRuntime(catalog.findLibrary(alias).get())
    }
}

abstract class ReleaseApiBuild : KotlinApiBuildTask() {
    /** The release variant's class directories (Kotlin and Java); jars there are only R classes. */
    @get:Internal
    abstract val classDirs: ListProperty<Directory>

    @get:Internal
    abstract val classJars: ListProperty<RegularFile>

    init {
        inputClassesDirs.from(classDirs)
    }
}

val apiName = "${project.name}.api"
val apiFile = layout.projectDirectory.file("api/$apiName")
val apiBuild =
    tasks.register<ReleaseApiBuild>("apiBuild") {
        description = "Writes the release build's public API to build/api/$apiName"
        outputApiFile.set(layout.buildDirectory.file("api/$apiName"))
        runtimeClasspath.from(bcvRuntime)
    }
val components = extensions.getByType<LibraryAndroidComponentsExtension>()
components.onVariants(components.selector().withBuildType("release")) { variant ->
    variant.artifacts
        .forScope(ScopedArtifacts.Scope.PROJECT)
        .use(apiBuild)
        .toGet(ScopedArtifact.CLASSES, ReleaseApiBuild::classJars, ReleaseApiBuild::classDirs)
}
tasks.register<KotlinApiCompareTask>("apiCheck") {
    description = "Fails if the public API differs from api/$apiName (run apiDump after a reviewed change)"
    group = "verification"
    projectApiFile.set(apiFile)
    generatedApiFile.set(apiBuild.flatMap { it.outputApiFile })
}
tasks.register<Copy>("apiDump") {
    description = "Updates api/$apiName from the release build"
    from(apiBuild.flatMap { it.outputApiFile })
    into(apiFile.asFile.parentFile)
}
