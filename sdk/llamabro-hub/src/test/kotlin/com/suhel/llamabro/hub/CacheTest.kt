package com.suhel.llamabro.hub

import com.suhel.llamabro.hub.internal.Cache
import com.suhel.llamabro.hub.internal.JvmStorage
import com.suhel.llamabro.hub.internal.Resolver
import com.suhel.llamabro.hub.internal.Response
import com.suhel.llamabro.hub.internal.Transport
import com.suhel.llamabro.hub.internal.repoFolder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

internal class CacheTest {
    @Test fun cachedListsRepoAndTagWithModelPartsOnly() {
        val root = Files.createTempDirectory("hub-list-").toFile()
        val tree = Tree(root, "test/split")
        tree.add("model-Q4_K_M-00001-of-00002.gguf", "one")
        tree.add("model-Q4_K_M-00002-of-00002.gguf", "two-two")
        tree.add("mmproj-model-Q4_K_M.gguf", "projector")
        tree.add("model-Q8_0.gguf", "eight")
        val cached = cache(root).list()
        assertEquals(listOf("test/split:Q4_K_M", "test/split:Q8_0"), cached.map { it.model.toString() })
        assertEquals(listOf(10L, 5L), cached.map { it.sizeBytes })
    }

    @Test fun deleteKeepsFileOtherTagUses() {
        val root = Files.createTempDirectory("hub-shared-").toFile()
        val tree = Tree(root, "test/shared")
        tree.add("model-Q4_K_M.gguf", "four")
        tree.add("model-Q8_0.gguf", "eight")
        tree.add("mmproj-Q4_K_M.gguf", "shared")
        val sharedBlob = tree.blob("mmproj-Q4_K_M.gguf")
        val fourBlob = tree.blob("model-Q4_K_M.gguf")
        assertTrue(cache(root).delete(HfModel("test/shared:Q4_K_M")))
        assertFalse(tree.snapshot("model-Q4_K_M.gguf").exists())
        assertFalse(fourBlob.exists())
        assertTrue(tree.snapshot("model-Q8_0.gguf").exists())
        assertTrue(tree.snapshot("mmproj-Q4_K_M.gguf").exists())
        assertTrue(sharedBlob.exists())
        assertEquals(listOf("test/shared:Q8_0"), cache(root).list().map { it.model.toString() })
        assertFalse(cache(root).delete(HfModel("test/shared:Q4_K_M")))
    }

    @Test fun deleteAloneRemovesSnapshotAndUnreferencedBlob() {
        val root = Files.createTempDirectory("hub-alone-").toFile()
        val tree = Tree(root, "test/alone")
        tree.add("model-Q4_K_M.gguf", "four")
        tree.add("mmproj-Q4_K_M.gguf", "projector")
        val modelBlob = tree.blob("model-Q4_K_M.gguf")
        val projectorBlob = tree.blob("mmproj-Q4_K_M.gguf")
        assertTrue(cache(root).delete(HfModel("test/alone:Q4_K_M")))
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
        assertTrue(cache(root).delete(HfModel("test/history:Q4_K_M")))
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
        assertTrue(cache(root).delete(HfModel("test/target")))
        assertFalse(target.repoDir.exists())
        assertFalse(targetPlan.exists())
        assertTrue(other.repoDir.exists())
        assertTrue(otherPlan.exists())
        assertFalse(cache(root).delete(HfModel("test/target")))
    }

    @Test fun deleteTagRemovesOnlyMatchingPlanRecords() {
        val root = Files.createTempDirectory("hub-plan-delete-").toFile()
        val tree = Tree(root, "test/tags")
        tree.add("model-Q4_K_M.gguf", "four")
        tree.add("model-Q8_0.gguf", "eight")
        val plans = File(root, ".llamabro/plans").apply { mkdirs() }
        val four = File(plans, "four.json").apply { writeText("""{"model":"test/tags:Q4_K_M"}""") }
        val eight = File(plans, "eight.json").apply { writeText("""{"model":"test/tags:Q8_0"}""") }
        assertTrue(cache(root).delete(HfModel("test/tags:Q4_K_M")))
        assertFalse(four.exists())
        assertTrue(eight.exists())
    }

    @Test fun deleteExactFileKeepsTheRepoOtherQuants() {
        val root = Files.createTempDirectory("hub-file-delete-").toFile()
        val tree = Tree(root, "test/files")
        tree.add("model-Q4_K_M.gguf", "four")
        tree.add("model-Q8_0.gguf", "eight")
        assertTrue(cache(root).delete(HfModel("test/files#model-Q4_K_M.gguf")))
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
        assertTrue(cache(root).delete(HfModel("test/partials:Q4_K_M")))
        assertFalse("the deleted tag's own partial goes", ownPartial.exists())
        assertTrue("a partial another tag plans stays", sharedPartial.exists())
    }

    private fun cache(root: File): Cache {
        val transport =
            object : Transport {
                override suspend fun open(
                    method: String,
                    url: String,
                    headers: Map<String, String>,
                    range: Long?,
                    rangeEnd: Long?,
                ): Response = error("cache operations must not use the network")
            }
        return Cache(root, Resolver("https://example.invalid", root, transport, JvmStorage()))
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
