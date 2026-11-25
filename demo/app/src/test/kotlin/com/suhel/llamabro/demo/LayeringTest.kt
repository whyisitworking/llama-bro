package com.suhel.llamabro.demo

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The demo's packages are layered (D144): a package imports only those below it, `ui`, `benchmark` (the benchmark
 * build's seeding) and the root (the app, the container, the activity) import any, and no domain package knows the
 * design system. Read from the package and import lines of every main-code source set (each `kotlin` folder under
 * `src` but the tests' and the instrumented tests') and from fully qualified names in code, so a new edge, or a folder
 * the table doesn't list, fails here with its file.
 */
class LayeringTest {
    private val src = File("src")

    // every source set of the app's own code: main, and the build types' and flavors' (benchmark, debug, …)
    private val sourceSets =
        src
            .listFiles { dir -> dir.isDirectory && dir.name !in TEST_SETS }
            .orEmpty()
            .map { File(it, "kotlin") }
            .filter { it.isDirectory }

    private val below =
        mapOf(
            "data.settings" to emptySet(),
            "data" to setOf("data.settings"),
            "models" to setOf("data", "data.settings"),
            "audio" to setOf("data", "data.settings"),
            "chat" to setOf("data", "data.settings", "models", "audio"),
            "voice" to setOf("data", "data.settings", "models", "audio", "chat"),
            "server" to setOf("data", "data.settings", "models", "audio", "chat"),
        )

    @Test fun everyDomainPackageImportsOnlyThoseBelowIt() {
        assertTrue("sources not found at ${src.absolutePath}", sourceSets.any { it.name == "kotlin" })
        val breaks =
            sourceSets
                .flatMap { set -> set.walkTopDown().filter { it.extension == "kt" } }
                .flatMap(::forbiddenEdges)
        assertTrue("forbidden imports:\n" + breaks.joinToString("\n"), breaks.isEmpty())
    }

    private fun forbiddenEdges(file: File): Sequence<String> {
        val name = file.relativeTo(src).path
        val lines = file.readLines()
        val declaration =
            checkNotNull(lines.firstOrNull { it.startsWith(PACKAGE) }) { "$name has no package line" }
        val from = layerOf(declaration.removePrefix(PACKAGE).trim())
        if (from in ANY) return emptySequence()
        val allowed = below[from] ?: return sequenceOf("$name is in a package ($from) the layer table doesn't know")

        fun wrong(target: String) = importLayer(target).let { it != from && it !in allowed }
        val imports =
            lines
                .asSequence()
                .filter { it.startsWith(IMPORT) }
                .map { it.removePrefix(IMPORT).trim() }
                .filter(::wrong)
                .map { "$name ($from) imports $it from ${importLayer(it)}" }
        // a fully qualified name is an import in disguise; comments and KDoc may name anything
        val qualified =
            lines
                .asSequence()
                .filter { line -> !line.startsWith(IMPORT) && !line.startsWith(PACKAGE) && !line.trim().isComment() }
                .flatMap { QUALIFIED.findAll(it) }
                .map { it.groupValues[1] }
                .filter(::wrong)
                .map { "$name ($from) names $BASE.$it from ${importLayer(it)}" }
        return imports + qualified
    }

    private fun String.isComment() = startsWith("//") || startsWith("/*") || startsWith("*")

    /**
     * The layer of a package below `com.suhel.llamabro.demo`: `data.settings` is its own layer, `data.db` is `data`.
     */
    private fun layerOf(packageName: String): String {
        val parts =
            packageName
                .removePrefix(BASE)
                .removePrefix(".")
                .split('.')
                .filter { it.isNotEmpty() }
        return when {
            parts.isEmpty() -> ROOT
            parts[0] == "data" && parts.getOrNull(1) == "settings" -> "data.settings"
            else -> parts[0]
        }
    }

    private fun importLayer(name: String): String {
        val parts = name.split('.')
        return when {
            parts[0] == "data" && parts.getOrNull(1) == "settings" -> "data.settings"

            parts[0] in LAYERS -> parts[0]

            parts[0].first().isUpperCase() -> ROOT

            // a class of the root package: the app, the container, the activity
            else -> UNKNOWN
        }
    }

    private companion object {
        const val BASE = "com.suhel.llamabro.demo"
        const val PACKAGE = "package "
        const val IMPORT = "import $BASE."
        const val ROOT = "root"
        const val UI = "ui"
        const val BENCHMARK = "benchmark"
        const val UNKNOWN = "unknown package"

        // layers that may import any other
        val ANY = setOf(UI, ROOT, BENCHMARK)
        val LAYERS = setOf("data", "models", "audio", "chat", "voice", "server", UI, "designsystem", BENCHMARK)
        val TEST_SETS = setOf("test", "androidTest", "sharedTest", "testFixtures")

        // the package (or class) path after the demo's base package, for a name written out in full in code
        val QUALIFIED = Regex("""com\.suhel\.llamabro\.demo\.([A-Za-z_]\w*(?:\.[A-Za-z_]\w*)?)""")
    }
}
