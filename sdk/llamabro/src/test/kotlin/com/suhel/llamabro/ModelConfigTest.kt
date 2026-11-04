package com.suhel.llamabro

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ModelConfigTest {
    @Test
    fun defaultsMatchPhoneDefaults() = runTest {
        val engine = FakeEngine()
        testLlamaBro(engine).loadModel(ModelSource.file("/m.gguf"))
        val cfg = Json.parseToJsonElement(engine.loadConfigs.single()).jsonObject
        assertEquals("/m.gguf", cfg["model_path"]!!.jsonPrimitive.content)
        assertEquals(1, cfg["n_parallel"]!!.jsonPrimitive.int)
        assertEquals(1024, cfg["cache_ram_mib"]!!.jsonPrimitive.int)
        assertEquals(8, cfg["n_ctx_checkpoints"]!!.jsonPrimitive.int)
        assertEquals(512, cfg["n_batch"]!!.jsonPrimitive.int)
        assertEquals(512, cfg["n_ubatch"]!!.jsonPrimitive.int)
        assertEquals("auto", cfg["n_ctx"]!!.jsonPrimitive.content)
        val slots = cfg["slot_save_path"]!!.jsonPrimitive.content
        assertTrue(slots, slots.endsWith("llamabro/slots/"))
        assertTrue(File(slots).isDirectory)
        assertFalse(cfg.containsKey("mmproj_path"))
        assertFalse(cfg.containsKey("speculative"))
    }

    @Test
    fun overridesSent() = runTest {
        val engine = FakeEngine()
        testLlamaBro(engine).loadModel(ModelSource.file("/m.gguf", mmproj = "/p.gguf")) {
            contextSize = ContextSize.Fixed(4096)
            speculative = Speculative.Mtp(draftMax = 2)
            threads = 4
        }
        val cfg = Json.parseToJsonElement(engine.loadConfigs.single()).jsonObject
        assertEquals("/p.gguf", cfg["mmproj_path"]!!.jsonPrimitive.content)
        assertEquals(4096, cfg["n_ctx"]!!.jsonPrimitive.int)
        assertEquals(4, cfg["n_threads"]!!.jsonPrimitive.int)
        assertEquals("""{"type":"mtp","n_max":2}""", cfg["speculative"].toString())
    }

    @Test
    fun infoParsed() = runTest {
        val info = testLlamaBro().loadModel(ModelSource.file("/m.gguf")).info
        assertEquals(8192, info.contextSize)
        assertEquals(262144, info.trainedContextSize)
        assertTrue(info.supportsVision)
        assertFalse(info.supportsAudio)
        assertTrue(info.hasMtp)
        assertEquals(true, info.templateCaps["supports_tools"])
        assertEquals("qwen35", info.metadata["general.architecture"])
        assertEquals("{{ messages }}", info.chatTemplate)
    }
}
