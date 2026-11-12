package com.suhel.llamabro.hub

import com.suhel.llamabro.hub.internal.HttpTransport
import com.suhel.llamabro.hub.internal.JvmStorage
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.security.MessageDigest

/** Compares the cache layout from this SDK with upstream's actual download handler. */
internal class CacheGoldenTest {
    @Test fun cacheTreesMatchUpstream() =
        runBlocking {
            val goldenRoot = System.getProperty("llamabro.hub.golden.dir")
            assumeTrue("set llamabro.hub.golden.dir to run cache golden", !goldenRoot.isNullOrBlank())
            val corpus =
                File(requireNotNull(System.getProperty("llamabro.hub.fake")))
                    .resolveSibling("corpus-golden.json")
            val repos =
                Json
                    .parseToJsonElement(corpus.readText())
                    .jsonObject
                    .getValue("repos")
                    .jsonObject
            val failures = mutableListOf<String>()
            FakeHub(repos).use { server ->
                for (scenario in scenarios) {
                    val upstreamDir = File(goldenRoot, "${scenario.name}/upstream")
                    val upstreamPaths = File(goldenRoot, "${scenario.name}/paths.json")
                    check(upstreamDir.isDirectory && upstreamPaths.isFile) {
                        "missing upstream cache golden for ${scenario.name} under $goldenRoot"
                    }
                    val cache = Files.createTempDirectory("hub-cache-${scenario.name}-").toFile()
                    val config =
                        HubConfig {
                            endpoint = server.apiUrl
                            cacheDir = cache
                        }
                    val hub = LlamaBroHub(config, cache, HttpTransport(server.apiUrl, null, null), JvmStorage())
                    for (request in scenario.requests) {
                        hub
                            .download(HfModel(request.ref)) {
                                mmproj = request.mmproj
                                draft =
                                    request.draft?.let {
                                        DraftRequest.dflash(
                                            it.takeIf(String::isNotEmpty)?.let { ref ->
                                                HfModel(ref)
                                            },
                                        )
                                    }
                            }.collect()
                    }
                    compareTree(scenario.name, upstreamDir.toPath(), cache.toPath(), failures)
                    val last = scenario.requests.last()
                    val plan =
                        hub.plan(HfModel(last.ref)) {
                            mmproj = last.mmproj
                            draft =
                                last.draft?.let {
                                    DraftRequest.dflash(
                                        it.takeIf(String::isNotEmpty)?.let { ref ->
                                            HfModel(ref)
                                        },
                                    )
                                }
                        }
                    val actualPaths =
                        mapOf(
                            "model" to plan.files.firstOrNull { it.role == HubFile.Role.Model }?.snapshotRelative(),
                            "mmproj" to plan.files.firstOrNull { it.role == HubFile.Role.Mmproj }?.snapshotRelative(),
                            "draft" to plan.files.firstOrNull { it.role == HubFile.Role.Draft }?.snapshotRelative(),
                        )
                    val expectedPaths = Json.parseToJsonElement(upstreamPaths.readText()).jsonObject
                    for ((role, actual) in actualPaths) {
                        val expected = expectedPaths.getValue(role).jsonPrimitive.content
                        if (expected != actual.orEmpty()) {
                            failures += "${scenario.name} $role path: expected $expected, got $actual"
                        }
                    }
                }
            }
            assertTrue(failures.joinToString("\n"), failures.isEmpty())
        }

    private fun HubFile.snapshotRelative(): String =
        "${com.suhel.llamabro.hub.internal.repoFolder(sourceRepo)}/snapshots/$sourceCommit/$path"

    private fun compareTree(name: String, upstream: Path, sdk: Path, failures: MutableList<String>) {
        val expected = entries(upstream)
        val actual = entries(sdk)
        val onlyUpstream = expected.keys - actual.keys
        val onlySdk = actual.keys - expected.keys
        if (onlyUpstream.isNotEmpty()) failures += "$name only upstream: $onlyUpstream"
        if (onlySdk.isNotEmpty()) failures += "$name only SDK: $onlySdk"
        for (path in expected.keys.intersect(actual.keys)) {
            if (expected[path] != actual[path]) {
                failures += "$name $path: upstream ${expected[path]}, SDK ${actual[path]}"
            }
        }
    }

    private fun entries(root: Path): Map<String, String> =
        Files.walk(root).use { paths ->
            paths
                .filter { it != root }
                .map { path ->
                    root.relativize(path).toString().replace(File.separatorChar, '/') to path
                }.filter { (relative, _) ->
                    !relative.startsWith(".locks/") && relative != ".locks" &&
                        !relative.startsWith(".llamabro/") && relative != ".llamabro"
                }.toList()
                .associate { (relative, path) ->
                    relative to
                        when {
                            Files.isSymbolicLink(path) -> "link:${Files.readSymbolicLink(path)}"
                            Files.isDirectory(path, NOFOLLOW_LINKS) -> "dir"
                            Files.isRegularFile(path, NOFOLLOW_LINKS) -> "sha256:${sha256(path)}"
                            else -> "unknown"
                        }
                }
        }

    private fun sha256(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { stream ->
            val bytes = ByteArray(1 shl 20)
            while (true) {
                val read = stream.read(bytes)
                if (read < 0) break
                digest.update(bytes, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private data class Request(val ref: String, val mmproj: Boolean = true, val draft: String? = null)

    private data class Scenario(val name: String, val requests: List<Request>)

    private val scenarios =
        listOf(
            Scenario("flat", listOf(Request("test/flat:Q8_0"))),
            Scenario("default-tag", listOf(Request("test/flat"))),
            Scenario("no-mmproj", listOf(Request("test/flat:Q8_0", mmproj = false))),
            Scenario("split", listOf(Request("test/split:Q3_K_M"))),
            Scenario("dflash-same", listOf(Request("test/dflash:Q4_K_M", draft = ""))),
            Scenario("dflash-other", listOf(Request("test/main:Q4_K_M", draft = "test/draft"))),
            Scenario("draft-full", listOf(Request("test/main:Q4_K_M", draft = "test/draftfull:Q8_0"))),
            Scenario("two-tags", listOf(Request("test/flat:Q4_K_M"), Request("test/flat:Q8_0"))),
        )
}
