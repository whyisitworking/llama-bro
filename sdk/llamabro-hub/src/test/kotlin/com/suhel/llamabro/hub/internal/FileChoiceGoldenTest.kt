package com.suhel.llamabro.hub.internal

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

/** The upstream tables, including every recorded real repository, checked without a network layer. */
internal class FileChoiceGoldenTest {
    private val root by lazy {
        val resource = requireNotNull(javaClass.getResource("/upstream/model-resolution.json"))
        Json.parseToJsonElement(File(resource.toURI()).readText()).jsonObject
    }

    @Test fun syntheticPlansMatchAtEveryRequiredListingOrder() {
        for (element in root.getValue("plan_cases").jsonArray) {
            val c = element.jsonObject
            val name = c.getValue("name").jsonPrimitive.content
            val paths = c.getValue("files").jsonArray.map { it.jsonPrimitive.content }
            val ref = c.getValue("hf_repo").jsonPrimitive.content
            val tag = splitRepoTag(ref).second
            val explicitFile = c.getValue("hf_file").jsonPrimitive.content
            val sidecars = c.getValue("sidecars").jsonPrimitive.boolean
            val rotations = if (c.getValue("order_dependent").jsonPrimitive.boolean) 1 else paths.size
            for (rot in 0 until rotations) {
                val ordered = (paths.drop(rot) + paths.take(rot)).let { if (rot % 2 == 1) it.reversed() else it }
                val actual = resolvePlan(ordered.map { RepoFile(it) }, tag, explicitFile, sidecars, sidecars)
                val context = "$name rotation $rot"
                assertEquals(context, c.getValue("primary").jsonPrimitive.content, actual.primary?.path ?: "")
                assertEquals(context, c.getValue("model_files").jsonArray.map { it.jsonPrimitive.content }.toSet(),
                    actual.modelFiles.map { it.path }.toSet())
                assertEquals(context, c.getValue("mmproj").jsonPrimitive.content, actual.mmproj?.path ?: "")
                assertEquals(context, c.getValue("dflash").jsonPrimitive.content, actual.dflash?.path ?: "")
            }
        }
    }

    @Test fun allRecordedRealRepositoryPlansMatch() {
        for (element in root.getValue("real_repos").jsonArray) {
            val repo = element.jsonObject
            val id = repo.getValue("id").jsonPrimitive.content
            val files = repo.getValue("files").jsonArray.map { RepoFile(it.jsonPrimitive.content) }
            for (plan in repo.getValue("plans").jsonArray) {
                val p = plan.jsonObject
                val tag = p.getValue("tag").jsonPrimitive.content
                val actual = resolvePlan(files, tag, "", true, true)
                val context = "$id:$tag"
                assertEquals(context, p.getValue("primary").jsonPrimitive.content, actual.primary?.path ?: "")
                assertEquals(context, p.getValue("parts").jsonPrimitive.int, actual.modelFiles.size)
                assertEquals(context, p.getValue("mmproj").jsonPrimitive.content, actual.mmproj?.path ?: "")
                assertEquals(context, p.getValue("dflash").jsonPrimitive.content, actual.dflash?.path ?: "")
            }
        }
    }
}
