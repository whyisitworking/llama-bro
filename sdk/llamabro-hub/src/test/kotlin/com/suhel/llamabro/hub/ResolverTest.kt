package com.suhel.llamabro.hub

import com.suhel.llamabro.hub.internal.HttpTransport
import com.suhel.llamabro.hub.internal.JvmStorage
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.nio.file.Files

internal class ResolverTest {
    @Before fun reset() = hub.reset()

    @Test fun refsPickMainAndWriteWithoutNewline() =
        runBlocking {
            val cache = Files.createTempDirectory("hub-resolve-").toFile()
            val plan = instance(cache).plan(HfModel("test/choice"))
            assertEquals("a".repeat(40), plan.commit)
            assertEquals(listOf("model-Q4_K_M.gguf", "mmproj-model-Q8_0.gguf"), plan.files.map { it.path })
            assertEquals("a".repeat(40), File(cache, "models--test--choice/refs/main").readText())
            assertFalse(plan.offline)
        }

    @Test fun offlineFallbackOnApiError() =
        runBlocking {
            val cache = Files.createTempDirectory("hub-offline-").toFile()
            val client = instance(cache)
            client.plan(HfModel("test/choice"))
            File(cache, "models--test--choice/snapshots/${"a".repeat(40)}/model-Q4_K_M.gguf")
                .apply {
                    parentFile!!.mkdirs()
                    writeText("gguf")
                }
            hub.control("[{\"kind\":\"status\",\"host\":\"api\",\"match\":\"refs\",\"status\":503}]")
            val plan = client.plan(HfModel("test/choice"))
            assertTrue(plan.offline)
            assertEquals("model-Q4_K_M.gguf", plan.files.single().path)
        }

    @Test fun unknownRepoFailsWithNotFound() =
        runBlocking {
            try {
                instance(Files.createTempDirectory("hub-missing-").toFile()).plan(HfModel("test/absent"))
                fail("missing repo should fail")
            } catch (e: HubException.NotFound) {
                assertEquals(emptyList<String>(), e.available)
            }
        }

    @Test fun dflashFromSameRepoAndPlanFile() =
        runBlocking {
            val cache = Files.createTempDirectory("hub-dflash-").toFile()
            val plan = instance(cache).plan(HfModel("test/choice:Q8_0")) { draft = DraftRequest.dflash() }
            assertEquals(
                listOf("model-Q8_0.gguf", "mmproj-model-Q8_0.gguf", "dflash-model-Q8_0.gguf"),
                plan.files.map { it.path },
            )
            assertEquals(
                listOf(HubFile.Role.Model, HubFile.Role.Mmproj, HubFile.Role.Draft),
                plan.files.map { it.role },
            )
            val stored = File(cache, ".llamabro/plans").listFiles().orEmpty()
            assertEquals(1, stored.size)
            assertTrue(stored.single().readText().contains("\"model\":\"test/choice:Q8_0\""))
        }

    @Test fun refsFirstValidWhenNoMain() =
        runBlocking {
            val cache = Files.createTempDirectory("hub-branch-").toFile()
            val plan = instance(cache).plan(HfModel("test/branch"))
            assertEquals("model-Q4_K_M.gguf", plan.files.single().path)
            assertEquals("a".repeat(40), File(cache, "models--test--branch/refs/feature").readText())
            assertFalse(File(cache, "models--test--branch/refs/main").exists())
        }

    @Test fun treeSkipsEscapesAndDirectories() =
        runBlocking {
            val cache = Files.createTempDirectory("hub-escape-").toFile()
            val plan = instance(cache).plan(HfModel("test/escape"))
            assertEquals(listOf("model-Q4_K_M.gguf"), plan.files.map { it.path })
            assertFalse(File(cache, "evil.gguf").exists())
        }

    @Test fun lfsOidPreferredAndSizesFromTree() =
        runBlocking {
            val plan = instance(Files.createTempDirectory("hub-lfs-").toFile()).plan(HfModel("test/choice"))
            assertEquals(listOf(4096L, 4096L), plan.files.map { it.sizeBytes })
            assertEquals(8192L, plan.bytesTotal)
            assertEquals(8192L, plan.bytesToDownload)
            assertEquals(
                64,
                plan.files
                    .first()
                    .oid
                    ?.length,
            )
        }

    @Test fun noCacheRethrowsRetryableServerError() =
        runBlocking {
            hub.control("[{\"kind\":\"status\",\"host\":\"api\",\"match\":\"refs\",\"status\":503}]")
            try {
                instance(Files.createTempDirectory("hub-503-").toFile()).plan(HfModel("test/choice"))
                fail("server error should surface with no cache")
            } catch (e: HubException.Network) {
                assertTrue(e.retryable)
            }
        }

    @Test fun gatedNeedsToken() =
        runBlocking {
            val cache = Files.createTempDirectory("hub-gated-").toFile()
            try {
                instance(cache).plan(HfModel("test/gated"))
                fail("gated repo should require credentials")
            } catch (e: HubException.Unauthorized) {
                assertEquals(401, e.httpStatus)
            }
            val config =
                HubConfig {
                    endpoint = hub.apiUrl
                    cacheDir = cache
                    token { "hf_" + "a".repeat(34) }
                }
            val authorized = LlamaBroHub(config, cache, HttpTransport(hub.apiUrl, null, null), JvmStorage())
            assertEquals(
                "model-Q4_K_M.gguf",
                authorized
                    .plan(HfModel("test/gated"))
                    .files
                    .single()
                    .path,
            )
        }

    @Test fun notFoundListsGgufsAndMissingDraftFails() =
        runBlocking {
            val client = instance(Files.createTempDirectory("hub-not-found-").toFile())
            try {
                client.plan(HfModel("test/choice:IQ1_S"))
                fail("missing tag should fail")
            } catch (e: HubException.NotFound) {
                assertTrue(e.available.contains("model-Q4_K_M.gguf"))
            }
            try {
                client.plan(HfModel("test/flat")) { draft = DraftRequest.dflash() }
                fail("missing DFlash sidecar should fail")
            } catch (e: HubException.NotFound) {
                assertTrue(e.message!!.contains("no dflash- file"))
            }
        }

    @Test fun presetIniIgnoredWithNote() =
        runBlocking {
            val plan = instance(Files.createTempDirectory("hub-preset-").toFile()).plan(HfModel("test/preset"))
            assertEquals("model-Q4_K_M.gguf", plan.files.first().path)
            assertTrue(plan.notes.single().contains("preset.ini ignored"))
        }

    @Test fun draftFromOtherRepoSidecarAndFullModel() =
        runBlocking {
            val client = instance(Files.createTempDirectory("hub-draft-other-").toFile())
            val sidecar = client.plan(HfModel("test/flat")) { draft = DraftRequest.dflash(HfModel("test/draft")) }
            assertEquals("dflash-model-Q8_0.gguf", sidecar.files.last().path)
            assertEquals(HubFile.Role.Draft, sidecar.files.last().role)
            val full =
                client.plan(HfModel("test/flat")) {
                    draft = DraftRequest.dflash(HfModel("test/draftfull:Q8_0"))
                }
            assertEquals("small-Q8_0.gguf", full.files.last().path)
            assertEquals(HubFile.Role.Draft, full.files.last().role)
        }

    @Test fun alternateDraftOfflineMarksCombinedPlanOffline() =
        runBlocking {
            val cache = Files.createTempDirectory("hub-draft-offline-").toFile()
            val client = instance(cache)
            val model = HfModel("test/flat")
            val draft = DraftRequest.dflash(HfModel("test/draft"))
            client.plan(model) { this.draft = draft }
            File(cache, "models--test--draft/snapshots/${"a".repeat(40)}/dflash-model-Q8_0.gguf")
                .apply {
                    parentFile!!.mkdirs()
                    writeText("gguf")
                }
            hub.control(
                "[{\"kind\":\"status\",\"host\":\"api\",\"match\":\"refs\",\"path\":\"/api/models/test/draft/refs\",\"status\":503}]",
            )
            val plan = client.plan(model) { this.draft = draft }
            assertTrue(plan.offline)
            assertEquals("dflash-model-Q8_0.gguf", plan.files.last().path)
        }

    @Test fun planFileContainsChosenFilesAndOptions() =
        runBlocking {
            val cache = Files.createTempDirectory("hub-plan-file-").toFile()
            val plan = instance(cache).plan(HfModel("test/choice:Q8_0")) { mmproj = false }
            assertEquals(1, plan.files.size)
            val stored = File(cache, ".llamabro/plans").listFiles().orEmpty().single()
            val json =
                Json
                    .parseToJsonElement(stored.readText())
                    .jsonObject
            assertEquals(1, json.getValue("version").jsonPrimitive.int)
            assertEquals("test/choice:Q8_0", json.getValue("model").jsonPrimitive.content)
            assertEquals("a".repeat(40), json.getValue("commit").jsonPrimitive.content)
            assertEquals(
                "Model",
                json
                    .getValue("files")
                    .jsonArray
                    .single()
                    .jsonObject
                    .getValue("role")
                    .jsonPrimitive.content,
            )
        }

    @Test fun wrongShapeJsonIsRetryableOrUsesCache() =
        runBlocking {
            val cache = Files.createTempDirectory("hub-shape-").toFile()
            val client = instance(cache)
            hub.control("[{\"kind\":\"status\",\"host\":\"api\",\"match\":\"refs\",\"status\":200,\"body\":[]}]")
            try {
                client.plan(HfModel("test/choice"))
                fail("refs of the wrong shape should fail with no cache")
            } catch (e: HubException.Network) {
                assertTrue(e.retryable)
            }
            client.plan(HfModel("test/choice"))
            File(cache, "models--test--choice/snapshots/${"a".repeat(40)}/model-Q4_K_M.gguf")
                .apply {
                    parentFile!!.mkdirs()
                    writeText("gguf")
                }
            hub.control("[{\"kind\":\"status\",\"host\":\"api\",\"match\":\"tree\",\"status\":200,\"body\":{}}]")
            assertTrue(client.plan(HfModel("test/choice")).offline)
        }

    @Test fun emptyOnlineListingFallsBackToCache() =
        runBlocking {
            val cache = Files.createTempDirectory("hub-empty-tree-").toFile()
            val client = instance(cache)
            client.plan(HfModel("test/choice"))
            File(cache, "models--test--choice/snapshots/${"a".repeat(40)}/model-Q4_K_M.gguf")
                .apply {
                    parentFile!!.mkdirs()
                    writeText("gguf")
                }
            hub.control("[{\"kind\":\"status\",\"host\":\"api\",\"match\":\"tree\",\"status\":200,\"body\":[]}]")
            val plan = client.plan(HfModel("test/choice"))
            assertTrue(plan.offline)
            assertEquals("model-Q4_K_M.gguf", plan.files.single().path)
        }

    @Test fun lfsWithoutOidDoesNotUseGitOid() =
        runBlocking {
            val body =
                "[{\"type\":\"file\",\"path\":\"model-Q4_K_M.gguf\",\"oid\":\"${"b".repeat(40)}\"," +
                    "\"size\":1,\"lfs\":{\"size\":1}}]"
            hub.control("[{\"kind\":\"status\",\"host\":\"api\",\"match\":\"tree\",\"status\":200,\"body\":$body}]")
            try {
                instance(Files.createTempDirectory("hub-lfs-no-oid-").toFile()).plan(HfModel("test/choice"))
                fail("an LFS entry without its oid can't be verified")
            } catch (e: HubException.NotFound) {
                assertEquals(emptyList<String>(), e.available)
            }
        }

    @Test fun missingNamedFileSelectsNoDraft() =
        runBlocking {
            val client = instance(Files.createTempDirectory("hub-missing-file-").toFile())
            try {
                client.plan(HfModel("test/flat")) { draft = DraftRequest.dflash(HfModel("test/draft#missing.gguf")) }
                fail("a named draft file that isn't in the repo must not fall back to the repo's dflash- file")
            } catch (e: HubException.NotFound) {
                assertTrue(e.message!!.contains("test/draft"))
            }
        }

    @Test fun offlineRefReadsFirstLineAndSkipsTempFiles() =
        runBlocking {
            val cache = Files.createTempDirectory("hub-ref-line-").toFile()
            val repo = File(cache, "models--test--choice")
            File(repo, "refs/main").apply {
                parentFile!!.mkdirs()
                writeText("a".repeat(40) + "\n")
            }
            File(repo, "refs/a.tmp-1234").writeText("c".repeat(40))
            File(repo, "snapshots/${"a".repeat(40)}/model-Q4_K_M.gguf").apply {
                parentFile!!.mkdirs()
                writeText("gguf")
            }
            hub.control("[{\"kind\":\"status\",\"host\":\"api\",\"match\":\"refs\",\"status\":503}]")
            val plan = instance(cache).plan(HfModel("test/choice"))
            assertTrue(plan.offline)
            assertEquals("a".repeat(40), plan.commit)
        }

    private fun instance(cache: File): LlamaBroHub {
        val config =
            HubConfig {
                endpoint = hub.apiUrl
                cacheDir = cache
            }
        return LlamaBroHub(config, cache, HttpTransport(hub.apiUrl, null, null), JvmStorage())
    }

    private companion object {
        lateinit var hub: FakeHub

        @JvmStatic @BeforeClass
        fun startHub() {
            hub = FakeHub()
        }

        @JvmStatic @AfterClass
        fun stopHub() {
            hub.close()
        }
    }
}
