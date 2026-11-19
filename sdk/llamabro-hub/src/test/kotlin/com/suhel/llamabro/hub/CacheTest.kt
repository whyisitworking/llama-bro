package com.suhel.llamabro.hub

import com.suhel.llamabro.hub.internal.Cache
import com.suhel.llamabro.hub.internal.JvmStorage
import com.suhel.llamabro.hub.internal.LocalCopies
import com.suhel.llamabro.hub.internal.PlanStore
import com.suhel.llamabro.hub.internal.Resolver
import com.suhel.llamabro.hub.internal.Response
import com.suhel.llamabro.hub.internal.Transport
import com.suhel.llamabro.hub.internal.repoFolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

internal class CacheTest {
    @Test fun cachedListsInstalledCopiesByTagWithModelPartsOnly() {
        val root = Files.createTempDirectory("hub-list-").toFile()
        val tree = Tree(root, "test/split")
        tree.add("model-Q4_K_M-00001-of-00002.gguf", "one")
        tree.add("model-Q4_K_M-00002-of-00002.gguf", "two-two")
        tree.add("mmproj-model-Q4_K_M.gguf", "projector")
        tree.add("model-Q8_0.gguf", "eight")
        val split =
            listOf(
                "model-Q4_K_M-00001-of-00002.gguf" to "Model" to 3,
                "model-Q4_K_M-00002-of-00002.gguf" to "Model" to 7,
                "mmproj-model-Q4_K_M.gguf" to "Mmproj" to 9,
            )
        record(root, "installed", "test/split:Q4_K_M", split)
        record(root, "installed", "test/split:Q8_0", listOf("model-Q8_0.gguf" to "Model" to 5))
        val cached = cache(root).list()
        assertEquals(listOf("test/split:Q4_K_M", "test/split:Q8_0"), cached.map { it.model.toString() })
        assertEquals(listOf(10L, 5L), cached.map { it.sizeBytes })
    }

    @Test fun cachedSkipsACopyWithNoInstalledRecord() {
        val root = Files.createTempDirectory("hub-list-unrecorded-").toFile()
        val tree = Tree(root, "test/plain")
        tree.add("model-Q4_K_M.gguf", "four")
        record(root, "plans", "test/plain:Q4_K_M", listOf("model-Q4_K_M.gguf" to "Model" to 4))
        assertEquals("complete on disk, but no download finished", emptyList<String>(), listed(root))
        val e = runCatching { LocalCopies(root, JvmStorage()).source(HfModel("test/plain:Q4_K_M"), PlanOptions()) }
        assertTrue("${e.exceptionOrNull()}", e.exceptionOrNull() is HubException.NotFound)
    }

    @Test fun cachedSkipsASplitModelWithAPartMissing() {
        val root = Files.createTempDirectory("hub-list-split-").toFile()
        val tree = Tree(root, "test/split")
        tree.add("model-Q4_K_M-00001-of-00002.gguf", "one")
        val split =
            listOf(
                "model-Q4_K_M-00001-of-00002.gguf" to "Model" to 3,
                "model-Q4_K_M-00002-of-00002.gguf" to "Model" to 7,
            )
        record(root, "installed", "test/split:Q4_K_M", split)
        assertEquals(emptyList<String>(), listed(root))
    }

    @Test fun cachedSkipsAnInstalledCopyWithAFileGone() {
        val root = Files.createTempDirectory("hub-list-installed-").toFile()
        val tree = Tree(root, "test/vision")
        tree.add("model-Q4_K_M.gguf", "four")
        val files = listOf("model-Q4_K_M.gguf" to "Model" to 4, "mmproj-F16.gguf" to "Mmproj" to 9)
        record(root, "plans", "test/vision:Q4_K_M", files) // a download writes both
        record(root, "installed", "test/vision:Q4_K_M", files)
        assertEquals("its projector is gone", emptyList<String>(), listed(root))
        tree.add("mmproj-F16.gguf", "projector")
        assertEquals(listOf("test/vision:Q4_K_M"), listed(root))
    }

    @Test fun cachedSkipsAnInstalledCopyWhoseDraftIsGone() {
        val root = Files.createTempDirectory("hub-list-draft-gone-").toFile()
        val tree = Tree(root, "test/main")
        tree.add("model-Q4_K_M.gguf", "four")
        val draftRepo = Tree(root, "test/draft")
        draftRepo.add("dflash-Q4_K_M.gguf", "draft")
        val commit = "a".repeat(40)
        val withDraft = PlanOptions().apply { draft = DraftRequest.dflash(HfModel("test/draft")) }
        val files =
            listOf(
                PlanStore.FileRecord("model-Q4_K_M.gguf", HubFile.Role.Model, 4, null),
                PlanStore.FileRecord("dflash-Q4_K_M.gguf", HubFile.Role.Draft, 5, null, "test/draft", commit),
            )
        PlanStore(root, JvmStorage(), PlanStore.INSTALLED)
            .write(HfModel("test/main:Q4_K_M"), withDraft, PlanStore.Record(commit, files))
        assertEquals(listOf("test/main:Q4_K_M"), listed(root))
        draftRepo.repoDir.deleteRecursively()
        assertEquals(emptyList<String>(), listed(root))
    }

    private fun listed(root: File): List<String> = cache(root).list().map { it.model.toString() }

    /**
     * A plan or installed record of [model] with the default options, where the hub keeps it, at the [Tree]'s commit:
     * ((path, role), size) per file.
     */
    private fun record(root: File, folder: String, model: String, files: List<Pair<Pair<String, String>, Int>>) {
        val records =
            files.map { (file, size) ->
                PlanStore.FileRecord(file.first, HubFile.Role.valueOf(file.second), size.toLong(), null)
            }
        val record = PlanStore.Record("a".repeat(40), records)
        PlanStore(root, JvmStorage(), folder).write(HfModel(model), PlanOptions(), record)
    }

    @Test fun twoDeletesOfOneModelAtOnceBothReturnAndOneRemoves() =
        runBlocking {
            val root = Files.createTempDirectory("hub-delete-race-").toFile()
            val hub =
                LlamaBroHub(HubConfig { cacheDir = root }, root, offline, JvmStorage())
            repeat(DELETE_ROUNDS) { round ->
                val tree = Tree(root, "test/race")
                for (part in 1..SPLIT_PARTS) {
                    tree.add("model-Q4_K_M-%05d-of-%05d.gguf".format(part, SPLIT_PARTS), "part $part of $round")
                }
                tree.add("model-Q8_0.gguf", "eight")
                record(root, "installed", "test/race:Q8_0", listOf("model-Q8_0.gguf" to "Model" to 5))
                val removed =
                    List(2) { async(Dispatchers.IO) { hub.delete(HfModel("test/race:Q4_K_M")) } }.awaitAll()
                assertEquals("round $round: the second sees it gone", listOf(false, true), removed.sorted())
                assertEquals(listOf("test/race:Q8_0"), listed(root))
                tree.repoDir.deleteRecursively()
            }
        }

    @Test fun deleteKeepsFileOtherTagUses() {
        val root = Files.createTempDirectory("hub-shared-").toFile()
        val tree = Tree(root, "test/shared")
        tree.add("model-Q4_K_M.gguf", "four")
        tree.add("model-Q8_0.gguf", "eight")
        tree.add("mmproj-Q4_K_M.gguf", "shared")
        record(root, "installed", "test/shared:Q8_0", listOf("model-Q8_0.gguf" to "Model" to 5))
        val sharedBlob = tree.blob("mmproj-Q4_K_M.gguf")
        val fourBlob = tree.blob("model-Q4_K_M.gguf")
        assertTrue(delete(root, HfModel("test/shared:Q4_K_M")))
        assertFalse(tree.snapshot("model-Q4_K_M.gguf").exists())
        assertFalse(fourBlob.exists())
        assertTrue(tree.snapshot("model-Q8_0.gguf").exists())
        assertTrue(tree.snapshot("mmproj-Q4_K_M.gguf").exists())
        assertTrue(sharedBlob.exists())
        assertEquals(listOf("test/shared:Q8_0"), cache(root).list().map { it.model.toString() })
        assertFalse(delete(root, HfModel("test/shared:Q4_K_M")))
    }

    @Test fun deleteAloneRemovesSnapshotAndUnreferencedBlob() {
        val root = Files.createTempDirectory("hub-alone-").toFile()
        val tree = Tree(root, "test/alone")
        tree.add("model-Q4_K_M.gguf", "four")
        tree.add("mmproj-Q4_K_M.gguf", "projector")
        val modelBlob = tree.blob("model-Q4_K_M.gguf")
        val projectorBlob = tree.blob("mmproj-Q4_K_M.gguf")
        assertTrue(delete(root, HfModel("test/alone:Q4_K_M")))
        assertFalse(tree.snapshot("model-Q4_K_M.gguf").exists())
        assertFalse(tree.snapshot("mmproj-Q4_K_M.gguf").exists())
        assertFalse(modelBlob.exists())
        assertFalse(projectorBlob.exists())
        assertTrue(cache(root).list().isEmpty())
    }

    @Test fun deleteKeepsBlobReferencedByAnotherSnapshot() {
        val root = Files.createTempDirectory("hub-two-snapshots-").toFile()
        val tree = Tree(root, "test/history")
        tree.add("model-Q4_K_M.gguf", "four")
        val blob = tree.blob("model-Q4_K_M.gguf")
        val oldSnapshot = File(tree.repoDir, "snapshots/${"b".repeat(40)}/model-Q8_0.gguf")
        oldSnapshot.parentFile!!.mkdirs()
        Files.createSymbolicLink(oldSnapshot.toPath(), oldSnapshot.parentFile!!.toPath().relativize(blob.toPath()))
        assertTrue(delete(root, HfModel("test/history:Q4_K_M")))
        assertFalse(tree.snapshot("model-Q4_K_M.gguf").exists())
        assertTrue(oldSnapshot.exists())
        assertTrue(blob.exists())
    }

    @Test fun deleteWholeRepoAndMatchingPlanRecords() {
        val root = Files.createTempDirectory("hub-repo-delete-").toFile()
        val target = Tree(root, "test/target")
        target.add("model-Q4_K_M.gguf", "four")
        val other = Tree(root, "test/other")
        other.add("model-Q8_0.gguf", "eight")
        val plans = File(root, ".llamabro/plans").apply { mkdirs() }
        val targetPlan = File(plans, "target.json").apply { writeText("""{"model":"test/target:Q4_K_M"}""") }
        val otherPlan = File(plans, "other.json").apply { writeText("""{"model":"test/other:Q8_0"}""") }
        assertTrue(delete(root, HfModel("test/target")))
        assertFalse(target.repoDir.exists())
        assertFalse(targetPlan.exists())
        assertTrue(other.repoDir.exists())
        assertTrue(otherPlan.exists())
        assertFalse(delete(root, HfModel("test/target")))
    }

    @Test fun deleteTagRemovesOnlyMatchingPlanRecords() {
        val root = Files.createTempDirectory("hub-plan-delete-").toFile()
        val tree = Tree(root, "test/tags")
        tree.add("model-Q4_K_M.gguf", "four")
        tree.add("model-Q8_0.gguf", "eight")
        val plans = File(root, ".llamabro/plans").apply { mkdirs() }
        val four = File(plans, "four.json").apply { writeText("""{"model":"test/tags:Q4_K_M"}""") }
        val eight = File(plans, "eight.json").apply { writeText("""{"model":"test/tags:Q8_0"}""") }
        assertTrue(delete(root, HfModel("test/tags:Q4_K_M")))
        assertFalse(four.exists())
        assertTrue(eight.exists())
    }

    @Test fun deleteExactFileKeepsTheRepoOtherQuants() {
        val root = Files.createTempDirectory("hub-file-delete-").toFile()
        val tree = Tree(root, "test/files")
        tree.add("model-Q4_K_M.gguf", "four")
        tree.add("model-Q8_0.gguf", "eight")
        record(root, "installed", "test/files:Q8_0", listOf("model-Q8_0.gguf" to "Model" to 5))
        assertTrue(delete(root, HfModel("test/files#model-Q4_K_M.gguf")))
        assertFalse(tree.snapshot("model-Q4_K_M.gguf").exists())
        assertFalse(tree.blob("model-Q4_K_M.gguf").exists())
        assertTrue(tree.snapshot("model-Q8_0.gguf").isFile)
        assertEquals(listOf("test/files:Q8_0"), cache(root).list().map { it.model.toString() })
    }

    @Test fun deleteTagRemovesItsUnfinishedDownloadsOnly() {
        val root = Files.createTempDirectory("hub-partial-delete-").toFile()
        val tree = Tree(root, "test/partials")
        tree.add("model-Q8_0.gguf", "eight")
        val own = "1".repeat(64)
        val shared = "2".repeat(64)
        val blobs = File(tree.repoDir, "blobs")
        val ownPartial = File(blobs, "$own.downloadInProgress").apply { writeText("half") }
        val sharedPartial = File(blobs, "$shared.downloadInProgress").apply { writeText("half") }
        val plans = File(root, ".llamabro/plans").apply { mkdirs() }
        File(
            plans,
            "four.json",
        ).writeText("""{"model":"test/partials:Q4_K_M","files":[{"oid":"$own"},{"oid":"$shared"}]}""")
        File(plans, "eight.json").writeText("""{"model":"test/partials:Q8_0","files":[{"oid":"$shared"}]}""")
        assertTrue(delete(root, HfModel("test/partials:Q4_K_M")))
        assertFalse("the deleted tag's own partial goes", ownPartial.exists())
        assertTrue("a partial another tag plans stays", sharedPartial.exists())
    }

    private fun cache(root: File): Cache {
        val resolver = Resolver("https://example.invalid", root, offline, JvmStorage())
        return Cache(root)
    }

    private fun delete(root: File, model: HfModel): Boolean = runBlocking { cache(root).delete(model) }

    private val offline =
        object : Transport {
            override suspend fun open(
                method: String,
                url: String,
                headers: Map<String, String>,
                range: Long?,
                rangeEnd: Long?,
            ): Response = error("cache operations must not use the network")
        }

    private companion object {
        const val DELETE_ROUNDS = 100
        const val SPLIT_PARTS = 20
    }

    private class Tree(root: File, repo: String) {
        val repoDir = File(root, repoFolder(repo))
        private val commit = "a".repeat(40)
        private val snapshotDir = File(repoDir, "snapshots/$commit")

        init {
            File(repoDir, "refs/main").apply {
                parentFile!!.mkdirs()
                writeText(commit)
            }
        }

        fun snapshot(name: String): File = File(snapshotDir, name)

        fun blob(name: String): File = File(repoDir, "blobs/${name.replace('/', '_')}")

        fun add(name: String, content: String) {
            val blob =
                blob(name).apply {
                    parentFile!!.mkdirs()
                    writeText(content)
                }
            val link = snapshot(name).apply { parentFile!!.mkdirs() }
            Files.createSymbolicLink(link.toPath(), requireNotNull(link.parentFile).toPath().relativize(blob.toPath()))
        }
    }
}
