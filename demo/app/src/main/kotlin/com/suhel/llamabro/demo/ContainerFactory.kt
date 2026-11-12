package com.suhel.llamabro.demo

/**
 * Where the app's container comes from. The real app makes the real one; the benchmark builds (`src/benchmark`) add a
 * [ContainerSource] that makes one over the SDK's fake engine and seeded chats, so UI cost is measured alone. The
 * builds that have no such class (debug, release) fall back to the real container.
 */
internal object ContainerFactory {
    private val source: ContainerSource? =
        try {
            Class.forName("com.suhel.llamabro.demo.BenchmarkContainerSource").getDeclaredConstructor().newInstance()
                as ContainerSource
        } catch (_: ClassNotFoundException) {
            null
        }

    fun create(app: DemoApp): AppContainer = source?.create(app) ?: AppContainer(app)

    /** Runs once at start-up. */
    fun onStart(app: DemoApp) {
        source?.onStart(app)
    }
}

/** What a build can add to the app's start-up. */
internal interface ContainerSource {
    fun create(app: DemoApp): AppContainer

    fun onStart(app: DemoApp)
}
