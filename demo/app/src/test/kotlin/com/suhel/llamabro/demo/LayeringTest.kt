package com.suhel.llamabro.demo

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The demo's packages are layered (D144): a package imports only those below it, `ui` and the root (the app, the
 * container, the activity) import any, and no domain package knows the design system. Read from the import lines of
 * every main source file, so a new edge fails here with its file and import.
 */
class LayeringTest {
    private val root = File("src/main/kotlin/com/suhel/llamabro/demo")

    private val below =
        mapOf(
            "data.settings" to emptySet(),
            "data" to setOf("data.settings"),
            "models" to setOf("data", "data.settings"),
            "audio" to setOf("data", "data.settings"),
            "chat" to setOf("data", "data.settings", "models", "audio"),
            "voice" to setOf("data", "data.settings", "models", "audio", "chat"),
        )

    @Test fun everyDomainPackageImportsOnlyThoseBelowIt() {
        assertTrue("sources not found at ${root.absolutePath}", root.isDirectory)
        val breaks =
            root
                .walkTopDown()
                .filter { it.extension == "kt" }
                .flatMap { file -> forbiddenEdges(file) }
                .toList()
        assertTrue("forbidden imports:\n" + breaks.joinToString("\n"), breaks.isEmpty())
    }

    private fun forbiddenEdges(file: File): Sequence<String> {
        val from = packageOf(file.relativeTo(root).invariantSeparatorsPath)
        val allowed = below[from] ?: return emptySequence()
        return file
            .readLines()
            .asSequence()
            .filter { it.startsWith(IMPORT) }
            .map { it.removePrefix(IMPORT).trim() }
            .filter { target -> packageOfImport(target).let { it != from && it !in allowed } }
            .map { "${file.relativeTo(root).path} ($from) imports $it" }
    }

    /** The layer of a source file by its folder: `data/settings` is its own layer, `data/db` belongs to `data`. */
    private fun packageOf(path: String): String {
        val folders = path.substringBeforeLast('/', "").split('/').filter { it.isNotEmpty() }
        return when {
            folders.isEmpty() -> ROOT
            folders[0] == "data" && folders.getOrNull(1) == "settings" -> "data.settings"
            else -> folders[0]
        }
    }

    private fun packageOfImport(name: String): String {
        val parts = name.split('.')
        return when {
            parts[0] == "data" && parts.getOrNull(1) == "settings" -> "data.settings"
            parts[0] in LAYERS -> parts[0]
            else -> ROOT // a class of the root package: the app, the container, the activity
        }
    }

    private companion object {
        const val IMPORT = "import com.suhel.llamabro.demo."
        const val ROOT = "root"
        val LAYERS = setOf("data", "models", "audio", "chat", "voice", "ui", "designsystem")
    }
}
