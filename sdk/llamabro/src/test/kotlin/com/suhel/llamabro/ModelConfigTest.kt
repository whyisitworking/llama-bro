package com.suhel.llamabro

import com.suhel.llamabro.internal.LlamaBroRuntime
import com.suhel.llamabro.internal.MtpPolicy
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
    fun defaultsMatchPhoneDefaults() =
        runTest {
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
            // path attachments reach llama-server as file:// links made here (D84, F157)
            // in this process's own folder (A7), named by its pid and a random token
            val media = cfg["media_dir"]!!.jsonPrimitive.content
            assertTrue(media, Regex(".*/llamabro/media/\\d+-[0-9a-f]{16}/").matches(media))
            assertTrue(File(media).isDirectory)
            assertFalse(cfg.containsKey("mmproj_path"))
            assertFalse(cfg.containsKey("speculative"))
        }

    @Test
    fun startSweepsOnlyTheMediaFoldersOfProcessesThatEnded() =
        runTest {
            // A7: every process of the app shares noBackupFilesDir; a start must not delete a live process's links
            val media = File(tempDir(), "llamabro/media")

            fun file(path: String) = File(media, path).apply { parentFile!!.mkdirs() }.apply { writeText("link") }
            val live = file("111-00000000000000aa/a.wav")
            val ended = file("222-00000000000000bb/b.wav")
            val earlierWithOurPid = file("7-00000000000000cc/c.wav") // our pid, reused: that process ended
            val oldLayout = file("0123456789abcdef.wav")
            val engine = FakeEngine()
            LlamaBro(
                LlamaBroRuntime(
                    engine,
                    media.parentFile!!.parentFile!!,
                    "/backends",
                    LlamaBroConfig.Default,
                    processId = 7,
                    processAlive = { it == 111 || it == 7 },
                ),
            ).loadModel(ModelSource.file("/m.gguf"))
            assertTrue(live.exists())
            assertFalse(ended.parentFile!!.exists())
            assertFalse(earlierWithOurPid.parentFile!!.exists())
            assertFalse(oldLayout.exists())
            val own =
                File(
                    Json
                        .parseToJsonElement(engine.loadConfigs.single())
                        .jsonObject["media_dir"]!!
                        .jsonPrimitive.content,
                )
            assertTrue(own.isDirectory)
            assertTrue(own.name, own.name.startsWith("7-"))
            assertEquals(setOf("111-00000000000000aa", own.name), media.list()!!.toSet())
        }

    @Test
    fun fileSourceBuilderSendsMmproj() =
        runTest {
            val source =
                ModelSource.file("/m.gguf") {
                    mmproj = "/p.gguf"
                    draft = "/draft.gguf"
                }
            assertEquals(FileSource("/m.gguf", "/p.gguf", "/draft.gguf"), source)

            val engine = FakeEngine()
            testLlamaBro(engine).loadModel(source)
            val cfg = Json.parseToJsonElement(engine.loadConfigs.single()).jsonObject
            assertEquals("/m.gguf", cfg["model_path"]!!.jsonPrimitive.content)
            assertEquals("/p.gguf", cfg["mmproj_path"]!!.jsonPrimitive.content)
            assertFalse(cfg.containsKey("draft_path"))
        }

    @Test
    fun overridesSent() =
        runTest {
            val engine = FakeEngine()
            testLlamaBro(engine).loadModel(ModelSource.file("/m.gguf") { mmproj = "/p.gguf" }) {
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
    fun loadFeaturesSentOnlyWhenSet() =
        runTest {
            // F69
            val engine = FakeEngine()
            testLlamaBro(engine).loadModel(ModelSource.file("/m.gguf"))
            val plain = Json.parseToJsonElement(engine.loadConfigs.single()).jsonObject
            listOf(
                "kv_cache_type",
                "embeddings",
                "pooling",
                "reranking",
                "lora",
            ).forEach { assertFalse(it, plain.containsKey(it)) }

            testLlamaBro(engine).loadModel(ModelSource.file("/m.gguf")) {
                kvCacheType = KvCacheType.Q8_0
                task = ModelTask.Embedding(Pooling.Mean)
                loraAdapters = listOf(LoraAdapter("/a.gguf", 0.5f), LoraAdapter("/b.gguf"))
            }
            val set = Json.parseToJsonElement(engine.loadConfigs.last()).jsonObject
            assertEquals("q8_0", set["kv_cache_type"]!!.jsonPrimitive.content)
            assertEquals("true", set["embeddings"].toString())
            assertEquals("mean", set["pooling"]!!.jsonPrimitive.content)
            assertEquals("""[{"path":"/a.gguf","scale":0.5},{"path":"/b.gguf","scale":1.0}]""", set["lora"].toString())

            testLlamaBro(engine).loadModel(ModelSource.file("/m.gguf")) { task = ModelTask.Embedding() }
            val modelPooling = Json.parseToJsonElement(engine.loadConfigs.last()).jsonObject
            assertFalse(modelPooling.containsKey("pooling")) // the model file's own

            testLlamaBro(engine).loadModel(ModelSource.file("/m.gguf")) { task = ModelTask.Rerank }
            val rerank = Json.parseToJsonElement(engine.loadConfigs.last()).jsonObject
            assertEquals("true", rerank["reranking"].toString())
            assertFalse(rerank.containsKey("embeddings"))
        }

    @Test
    fun speculativeAutoIsOffOnEveryChip() =
        runTest {
            // the fake model has an MTP head; Auto still sends no speculative setting, as MTP won nowhere (F67)
            val engine = FakeEngine()
            val model = testLlamaBro(engine).loadModel(ModelSource.file("/m.gguf"))
            assertFalse(Json.parseToJsonElement(engine.loadConfigs.single()).jsonObject.containsKey("speculative"))
            assertEquals(Speculative.Off, model.loadReport.speculative)
            assertTrue(
                model.loadReport.speculativeReason,
                model.loadReport.speculativeReason.startsWith("off: MTP measured slower"),
            )
            DeviceKind.entries.forEach {
                assertTrue(
                    MtpPolicy
                        .reason(it)
                        .contains("F67"),
                )
            }
        }

    @Test
    fun invalidSizesAreRejected() {
        // ContextSize.Fixed(0) would be upstream's "the training context", reserved at load
        for (bad in listOf<() -> Any>(
            { ContextSize.Fixed(0) },
            { ContextSize.Fixed(-1) },
            { ContextSize.UpTo(0) },
            { Speculative.Mtp(0) },
            { Speculative.DFlash(0) },
        )) {
            assertTrue(runCatching(bad).exceptionOrNull() is IllegalArgumentException)
        }
    }

    @Test
    fun speculativeSetByTheAppIsReported() =
        runTest {
            val mtp = testLlamaBro().loadModel(ModelSource.file("/m.gguf")) { speculative = Speculative.Mtp(2) }
            assertEquals(Speculative.Mtp(2), mtp.loadReport.speculative)
            assertEquals("MTP: set by the app", mtp.loadReport.speculativeReason)
            val off = testLlamaBro().loadModel(ModelSource.file("/m.gguf")) { speculative = Speculative.Off }
            assertEquals("off: set by the app", off.loadReport.speculativeReason)
        }

    @Test
    fun ngramSentAndReported() =
        runTest {
            // D35
            val engine = FakeEngine()
            val alone =
                testLlamaBro(
                    engine,
                ).loadModel(ModelSource.file("/m.gguf")) { speculative = Speculative.Ngram() }
            val both =
                testLlamaBro(engine).loadModel(ModelSource.file("/m.gguf")) {
                    speculative =
                        Speculative.Ngram(then = Speculative.Mtp(4))
                }
            val cfgs = engine.loadConfigs.map { Json.parseToJsonElement(it).jsonObject }
            assertEquals("""{"type":"ngram"}""", cfgs[0]["speculative"].toString())
            assertEquals("""{"type":"ngram","mtp_n_max":4}""", cfgs[1]["speculative"].toString())
            assertEquals(Speculative.Ngram(), alone.loadReport.speculative)
            assertEquals("n-gram: set by the app", alone.loadReport.speculativeReason)
            assertEquals(Speculative.Ngram(Speculative.Mtp(4)), both.loadReport.speculative)
            assertEquals("n-gram, then MTP: set by the app", both.loadReport.speculativeReason)
        }

    @Test
    fun dflashSentWithDraft() =
        runTest {
            val engine = FakeEngine()
            val source = ModelSource.file("/m.gguf") { draft = "/draft.gguf" }
            val model = testLlamaBro(engine).loadModel(source) { speculative = Speculative.DFlash() }
            val cfg = Json.parseToJsonElement(engine.loadConfigs.single()).jsonObject
            assertEquals("/draft.gguf", cfg["draft_path"]!!.jsonPrimitive.content)
            assertEquals("""{"type":"dflash","n_max":15}""", cfg["speculative"].toString())
            assertEquals(Speculative.DFlash(), model.loadReport.speculative)
            assertEquals("DFlash draft (D41)", model.loadReport.speculativeReason)
            assertEquals(Speculative.DFlash(8), Speculative.DFlash(8))
            assertEquals(Speculative.DFlash(8).hashCode(), Speculative.DFlash(8).hashCode())
            assertEquals("DFlash(draftMax=8)", Speculative.DFlash(8).toString())
        }

    @Test
    fun dflashWithoutDraftThrows() =
        runTest {
            val engine = FakeEngine()
            try {
                testLlamaBro(engine).loadModel(ModelSource.file("/m.gguf")) { speculative = Speculative.DFlash() }
                throw AssertionError("DFlash without a draft loaded")
            } catch (error: LlamaBroException.InvalidRequest) {
                assertEquals("Speculative.DFlash needs ModelSource.file(path) { draft = … }", error.message)
            }
            assertTrue(engine.loadConfigs.isEmpty())
        }

    @Test
    fun draftUnusedWithoutDflash() =
        runTest {
            val engine = FakeEngine()
            val model =
                testLlamaBro(engine).loadModel(ModelSource.file("/m.gguf") { draft = "/draft.gguf" }) {
                    speculative = Speculative.Mtp()
                }
            val cfg = Json.parseToJsonElement(engine.loadConfigs.single()).jsonObject
            assertFalse(cfg.containsKey("draft_path"))
            assertEquals(
                "MTP: set by the app; draft file unused (Speculative is not DFlash)",
                model.loadReport.speculativeReason,
            )
        }

    @Test
    fun infoParsed() =
        runTest {
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
