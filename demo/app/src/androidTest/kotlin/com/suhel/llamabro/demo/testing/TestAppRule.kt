package com.suhel.llamabro.demo.testing

import androidx.test.platform.app.InstrumentationRegistry
import com.suhel.llamabro.demo.AppContainer
import com.suhel.llamabro.demo.data.settings.SearchFilters
import com.suhel.llamabro.demo.data.settings.SizeFilter
import kotlinx.coroutines.runBlocking
import leakcanary.DetectLeaksAfterTestSuccess
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain

/** Gives a test a fresh [AppContainer] (empty database, DataStore, token and images) and removes it afterwards. */
class TestAppRule(
    private val clock: () -> Long = System::currentTimeMillis,
    /** Builds the container's config at the start of each test (fakes for the engine and the hub); default is fresh files. */
    private val config: (() -> TestConfig)? = null,
    /** Most tests aren't about first run: it is marked seen, so the app starts on its chats. */
    private val seenFirstRun: Boolean = true,
) : ExternalResource() {
    val app: TestDemoApp
        get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as TestDemoApp

    lateinit var container: AppContainer
        private set

    override fun before() {
        StrictViolations.reset()
        container = app.use(config?.invoke() ?: TestConfig.fresh(clock))
        if (seenFirstRun) runBlocking { container.settings.markFirstRunSeen() }
        // the fake Hub lists most repos without a parameter count, which a parameter range leaves out as the Hub does
        // (F176): search asks for any size, so a listed repo is found
        runBlocking { container.settings.setSearchFilters(SearchFilters(size = SizeFilter.Any)) }
    }

    override fun after() {
        app.release()
        StrictViolations.assertNone()
    }
}

/** The rules every screen, journey and data test runs under: LeakCanary's leak check around a fresh container. */
fun demoRules(app: TestAppRule): RuleChain = RuleChain.outerRule(DetectLeaksAfterTestSuccess()).around(app)
