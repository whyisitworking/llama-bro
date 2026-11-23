// Applied by every demo module whose host tests run under Robolectric.
//
// Robolectric would fetch its Android runtimes from Maven in the middle of a test run, with no retry and no cache, and
// fresh CI runners failed on it. Gradle fetches them instead, with its retries and its cache, and Robolectric reads
// them offline. A Robolectric upgrade needs the runtimes' versions in the catalog bumped with it; until they are, the
// tests fail at once and name the jar they could not find.

val catalog = extensions.getByType<VersionCatalogsExtension>().named("libs")

// one configuration per runtime: they share a Maven coordinate, so in one configuration only the newest would resolve
val runtimes =
    listOf("robolectric-runtime-sdk35", "robolectric-runtime-sdk36").map { alias ->
        configurations.create(alias.replace("-", "_")) { isTransitive = false }.also {
            dependencies.add(it.name, catalog.findLibrary(alias).get())
        }
    }

val runtimesDir = layout.buildDirectory.dir("robolectric-runtimes")
val syncRuntimes =
    tasks.register<Sync>("robolectricRuntimes") {
        from(runtimes)
        into(runtimesDir)
    }

tasks.withType<Test>().configureEach {
    dependsOn(syncRuntimes)
    systemProperty("robolectric.offline", "true")
    systemProperty("robolectric.dependency.dir", runtimesDir.get().asFile.path)
}
