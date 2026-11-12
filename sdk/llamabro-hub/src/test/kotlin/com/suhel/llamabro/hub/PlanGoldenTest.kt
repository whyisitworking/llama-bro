package com.suhel.llamabro.hub

import com.suhel.llamabro.hub.internal.HttpTransport
import com.suhel.llamabro.hub.internal.JvmStorage
import com.suhel.llamabro.hub.internal.Resolver
import com.suhel.llamabro.hub.internal.repoFolder
import com.suhel.llamabro.hub.internal.resolvePlan
import com.suhel.llamabro.hub.internal.splitRepoTag
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Exercises upstream's recorded choices through the same HTTP resolver used by plan(). */
internal class PlanGoldenTest {
    private val golden =
        Json
            .parseToJsonElement(
                requireNotNull(javaClass.getResourceAsStream("/upstream/model-resolution.json"))
                    .bufferedReader()
                    .use { it.readText() },
            ).jsonObject

    @Test fun upstreamPlansThroughResolver() =
        runBlocking {
            val cases = mutableListOf<Case>()
            val repos =
                buildJsonObject {
                    for ((index, element) in golden.getValue("plan_cases").jsonArray.withIndex()) {
                        val c = element.jsonObject
                        val paths = c.getValue("files").jsonArray.map { it.jsonPrimitive.content }
                        val rotations = if (c.getValue("order_dependent").jsonPrimitive.boolean) 1 else paths.size
                        for (rot in 0 until rotations) {
                            val ordered =
                                (paths.drop(rot) + paths.take(rot)).let {
                                    if (rot % 2 == 1) it.reversed() else it
                                }
                            val repo = "golden/c${index}r$rot"
                            put(repo, listing(ordered))
                            cases +=
                                Case(
                                    repo,
                                    c,
                                    "${c.getValue("name").jsonPrimitive.content} rotation $rot",
                                    splitRepoTag(c.getValue("hf_repo").jsonPrimitive.content).second,
                                    c.getValue("hf_file").jsonPrimitive.content,
                                    c.getValue("sidecars").jsonPrimitive.boolean,
                                    false,
                                )
                        }
                    }
                    for (element in golden.getValue("real_repos").jsonArray) {
                        val r = element.jsonObject
                        val repo = r.getValue("id").jsonPrimitive.content
                        put(repo, listing(r.getValue("files").jsonArray.map { it.jsonPrimitive.content }))
                        for (plan in r.getValue("plans").jsonArray) {
                            val p = plan.jsonObject
                            cases +=
                                Case(
                                    repo,
                                    p,
                                    "$repo:${p.getValue("tag").jsonPrimitive.content}",
                                    p.getValue("tag").jsonPrimitive.content,
                                    "",
                                    true,
                                    true,
                                )
                        }
                    }
                }
            val failures = mutableListOf<String>()
            FakeHub(repos).use { hub ->
                val resolver =
                    Resolver(
                        hub.apiUrl,
                        Files.createTempDirectory("hub-plan-golden-").toFile(),
                        HttpTransport(hub.apiUrl, null, null),
                        JvmStorage(),
                    )
                for (case in cases) {
                    val listing = resolver.list(case.repo)
                    val actual = resolvePlan(listing.files, case.tag, case.file, case.sidecars, case.sidecars)

                    fun check(field: String, expected: Any, got: Any) {
                        if (expected != got) failures += "${case.name} $field: expected $expected, got $got"
                    }
                    check(
                        "primary",
                        case.expected
                            .getValue("primary")
                            .jsonPrimitive.content,
                        actual.primary?.path ?: "",
                    )
                    if (case.real) {
                        check(
                            "parts",
                            case.expected
                                .getValue("parts")
                                .jsonPrimitive.int,
                            actual.modelFiles.size,
                        )
                    } else {
                        check(
                            "model_files",
                            case.expected
                                .getValue("model_files")
                                .jsonArray
                                .map { it.jsonPrimitive.content }
                                .toSet(),
                            actual.modelFiles.map { it.path }.toSet(),
                        )
                    }
                    check(
                        "mmproj",
                        case.expected
                            .getValue("mmproj")
                            .jsonPrimitive.content,
                        actual.mmproj?.path ?: "",
                    )
                    check(
                        "dflash",
                        case.expected
                            .getValue("dflash")
                            .jsonPrimitive.content,
                        actual.dflash?.path ?: "",
                    )
                }
            }
            assertTrue(failures.joinToString("\n"), failures.isEmpty())
        }

    @Test fun upstreamCachedTagsMatchOfflineListing() =
        runBlocking {
            val cache = Files.createTempDirectory("hub-cached-golden-").toFile()
            val expected = mutableMapOf<String, List<String>>()
            for (element in golden.getValue("real_repos").jsonArray) {
                val repo = element.jsonObject
                val id = repo.getValue("id").jsonPrimitive.content
                expected[id] =
                    repo
                        .getValue("listing")
                        .jsonArray
                        .map { it.jsonPrimitive.content }
                        .sorted()
                val root = File(cache, "${repoFolder(id)}/snapshots/${"a".repeat(40)}")
                for (path in repo.getValue("files").jsonArray.map { it.jsonPrimitive.content }) {
                    File(root, path).apply {
                        parentFile!!.mkdirs()
                        writeText("gguf")
                    }
                }
                File(cache, "${repoFolder(id)}/refs/main").apply {
                    parentFile!!.mkdirs()
                    writeText("a".repeat(40))
                }
            }
            val config =
                HubConfig {
                    cacheDir = cache
                    endpoint = "http://127.0.0.1:1/"
                }
            val hub = LlamaBroHub(config, cache, HttpTransport(config.endpoint, null, null), JvmStorage())
            val actual =
                hub
                    .cached()
                    .groupBy { it.model.repo }
                    .mapValues { (_, models) -> models.mapNotNull { it.model.tag }.sorted() }
            val failures =
                expected.mapNotNull { (repo, tags) ->
                    if (actual[repo] == tags) null else "$repo: expected $tags, got ${actual[repo]}"
                }
            assertTrue(failures.joinToString("\n"), failures.isEmpty())
        }

    private fun listing(paths: List<String>): JsonObject =
        buildJsonObject {
            put("tree_order", "insertion")
            put(
                "files",
                buildJsonArray {
                    for ((index, path) in paths.withIndex()) {
                        add(
                            buildJsonObject {
                                put("path", path)
                                put("oid", "%040x".format(index + 1))
                                put("size", 1)
                            },
                        )
                    }
                },
            )
        }

    private class Case(
        val repo: String,
        val expected: JsonObject,
        val name: String,
        val tag: String,
        val file: String,
        val sidecars: Boolean,
        val real: Boolean,
    )
}
