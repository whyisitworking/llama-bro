package com.suhel.llamabro.hub.internal

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

internal class FileChoiceTest {
    @Test fun aTagMatchesOnlyAtATokenBoundary() {
        // upstream b12352 (#31620, #31639): ":F16" must not pick a BF16 file listed first, nor ":Q4_XS" an IQ4_XS one
        val files =
            listOf("m-BF16.gguf", "m-F16.gguf", "m-IQ4_XS.gguf", "m-Q4_XS.gguf", "sub/F16.gguf", "m_Q8_0.gguf")
                .map { RepoFile(it) }
        assertEquals("m-F16.gguf", findBestModel(files, "F16")?.path)
        assertEquals("m-Q4_XS.gguf", findBestModel(files, "Q4_XS")?.path)
        assertEquals("m-BF16.gguf", findBestModel(files, "BF16")?.path)
        // an underscore is not a boundary
        assertEquals(null, findBestModel(files, "Q8_0"))
        // a directory separator and the start of the path are boundaries
        assertEquals(
            "sub/F16.gguf",
            findBestModel(listOf(RepoFile("sub/BF16.gguf"), RepoFile("sub/F16.gguf")), "F16")?.path,
        )
        assertEquals("F16.gguf", findBestModel(listOf(RepoFile("BF16.gguf"), RepoFile("F16.gguf")), "F16")?.path)
    }

    @Test fun upstreamFixtureNamesHaveExpectedSplitAndModelClassification() {
        val expected =
            """
            README.md||0|0|0|false
            model-BF16.gguf|BF16|1|1|16|true
            model-Q4_K_M.gguf|Q4_K_M|1|1|4|true
            model-Q8_0.gguf|Q8_0|1|1|8|true
            mmproj-model-BF16.gguf|BF16|1|1|16|false
            mmproj-model-Q8_0.gguf|Q8_0|1|1|8|false
            mtp-model-BF16.gguf|BF16|1|1|16|false
            mtp-model-Q4_0.gguf|Q4_0|1|1|4|false
            mtp-model-Q8_0.gguf|Q8_0|1|1|8|false
            dflash-model-BF16.gguf|BF16|1|1|16|false
            dflash-model-Q8_0.gguf|Q8_0|1|1|8|false
            mmproj-model-f16.gguf|F16|1|1|16|false
            model-mtp-BF16.gguf|BF16|1|1|16|false
            model-mtp-Q8_0.gguf|Q8_0|1|1|8|false
            Q3_K_M/model-Q3_K_M-00001-of-00003.gguf|Q3_K_M|1|3|3|true
            Q3_K_M/model-Q3_K_M-00002-of-00003.gguf|Q3_K_M|2|3|3|true
            Q3_K_M/model-Q3_K_M-00003-of-00003.gguf|Q3_K_M|3|3|3|true
            Q8_0/model-Q8_0-00001-of-00002.gguf|Q8_0|1|2|8|true
            Q8_0/model-Q8_0-00002-of-00002.gguf|Q8_0|2|2|8|true
            model-UD-Q8_K_XL.gguf|Q8_K_XL|1|1|8|true
            mmproj-BF16.gguf|BF16|1|1|16|false
            model-MTP-BF16.gguf|BF16|1|1|16|true
            TheDrummer_Model-24B-v4.1-Q8_0.gguf|Q8_0|1|1|8|true
            BlackSheep-24B.Q8_0.gguf|Q8_0|1|1|8|true
            """.trimIndent().lines().associate { line ->
                val fields = line.split('|')
                fields[0] to fields.drop(1)
            }
        val resource = requireNotNull(javaClass.getResource("/upstream/model-resolution.json"))
        val cases =
            Json
                .parseToJsonElement(
                    File(resource.toURI()).readText(),
                ).jsonObject
                .getValue("plan_cases")
                .jsonArray
        val fixtureNames =
            cases
                .filter {
                    it.jsonObject
                        .getValue("name")
                        .jsonPrimitive.content in
                        setOf("flat exact tag", "subdir shards", "unsloth fallback", "vendor prefix")
                }.flatMap {
                    it.jsonObject
                        .getValue("files")
                        .jsonArray
                        .map { name -> name.jsonPrimitive.content }
                }.toSet()
        assertEquals(fixtureNames, expected.keys)
        for ((path, fields) in expected) {
            val info = splitInfo(path)
            assertEquals(path, fields[0], info.tag)
            assertEquals(path, fields[1].toInt(), info.index)
            assertEquals(path, fields[2].toInt(), info.count)
            assertEquals(path, fields[3].toInt(), quantBits(path))
            assertEquals(path, fields[4].toBoolean(), isModelGguf(path))
        }
    }
}
