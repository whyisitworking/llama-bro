@file:OptIn(ExperimentalCompilerApi::class)

package com.suhel.llamabro.typed.ksp

import com.tschuchort.compiletesting.JvmCompilationResult
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import com.tschuchort.compiletesting.configureKsp
import com.tschuchort.compiletesting.sourcesGeneratedBySymbolProcessor
import com.tschuchort.compiletesting.useKsp2
import kotlinx.serialization.json.Json
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The processor over snippets compiled by kctfork (D81). The Android llamabro-typed can't be on this JVM classpath,
 * so each snippet compiles beside [STAND_INS], which declare what generated code calls, in its package. The
 * serialization compiler plugin is not registered: a success test asserts on the generated source and on the
 * toolbox it builds at runtime through the stand-ins, and llamabro-typed's AnnotatedToolboxTest runs real generated
 * code with real serializers.
 */
class ToolboxProcessorTest {
    // ---- build errors (D61): each a KSPLogger.error naming the fix

    @Test
    fun blankDescription() =
        assertError(
            """
            @LbToolbox class Tools { @LbTool("  ") fun ping(): String = "pong" }
            """,
            "tool ping needs a description",
        )

    @Test
    fun nameFailsThePatternWithThePrefix() {
        assertError(
            """
            @LbToolbox(prefix = "my tools.") class Tools { @LbTool("Pings") fun ping(): String = "pong" }
            """,
            "'my tools.ping' must match ^[a-zA-Z0-9_-]{1,64}$",
        )
        assertError(
            """
            @LbToolbox class Tools { @LbTool("Pings", name = "${"x".repeat(65)}") fun ping(): String = "pong" }
            """,
            "must match ^[a-zA-Z0-9_-]{1,64}$",
        )
    }

    @Test
    fun twoToolsOneName() =
        assertError(
            """
            @LbToolbox class Tools {
                @LbTool("Pings") fun ping(): String = "pong"
                @LbTool("Pings too", name = "ping") fun pingAgain(): String = "pong"
            }
            """,
            "two tools are named 'ping'",
        )

    @Test
    fun overloadsCollide() =
        assertError(
            """
            @LbToolbox class Tools {
                @LbTool("Pings") fun ping(): String = "pong"
                @LbTool("Pings a host") fun ping(@LbToolParam host: String): String = host
            }
            """,
            "two tools are named 'ping'",
        )

    @Test
    fun overrideAnnotatedAgain() =
        assertError(
            """
            @LbToolbox interface Base { @LbTool("Pings") fun ping(): String }
            @LbToolbox class Tools : Base { @LbTool("Pings again") override fun ping(): String = "pong" }
            """,
            "ping overrides a tool and is annotated again",
        )

    @Test
    fun defaultOtherThanNull() {
        assertError(
            """
            @LbToolbox class Tools { @LbTool("Forecast") fun forecast(@LbToolParam days: Int = 3): String = "" }
            """,
            "parameter days has a default other than = null",
        )
        assertError(
            """
            @LbToolbox class Tools { @LbTool("Forecast") fun forecast(@LbToolParam days: Int? = 3): String = "" }
            """,
            "use one @Serializable arguments class",
        )
    }

    @Test
    fun parameterWithoutToolParamBesideAnnotatedOnes() =
        assertError(
            """
            @LbToolbox class Tools {
                @LbTool("Forecast") fun forecast(@LbToolParam city: String, days: Int): String = ""
            }
            """,
            "parameter days has no @LbToolParam",
        )

    @Test
    fun loneNonSerializableParameter() =
        assertError(
            """
            class City(val name: String)
            @LbToolbox class Tools { @LbTool("Forecast") fun forecast(city: City): String = "" }
            """,
            "parameter city is not a @Serializable class",
        )

    @Test
    fun toolOutsideAToolbox() =
        assertError(
            """
            class Tools { @LbTool("Pings") fun ping(): String = "pong" }
            """,
            "ping is in Tools, which is not an @LbToolbox",
        )

    @Test
    fun sealedSubclassWithoutSerialName() =
        assertError(
            """
            @Serializable sealed interface Shape
            @Serializable @SerialName("circle") data class Circle(val r: Int) : Shape
            @Serializable data class Square(val side: Int) : Shape
            @Serializable data class Draw(val shapes: List<Shape>)
            @LbToolbox class Tools { @LbTool("Draws") fun draw(args: Draw): String = "" }
            """,
            "the sealed subclass app.Square has no @SerialName",
        )

    @Test
    fun sealedSubclassWithDottedSerialName() =
        assertError(
            """
            @Serializable sealed interface Shape
            @Serializable @SerialName("shapes.Circle") data class Circle(val r: Int) : Shape
            @LbToolbox class Tools { @LbTool("Draws") fun draw(@LbToolParam shape: Shape): String = "" }
            """,
            "the sealed subclass app.Circle has the dotted @SerialName \"shapes.Circle\"",
        )

    @Test
    fun unsupportedReturnType() =
        assertError(
            """
            class Forecast(val sky: String)
            @LbToolbox class Tools { @LbTool("Forecast") fun forecast(): Forecast = Forecast("sunny") }
            """,
            "forecast returns app.Forecast, which can't be sent to the model",
        )

    @Test
    fun unreadableDefaultIsAnError() {
        // a toolbox interface compiled earlier: on the classpath as classes, so its default's source can't be read
        val library =
            compile(
                "@LbToolbox interface Remote { @LbTool(\"Forecast\") fun forecast(@LbToolParam days: Int? = null): String }",
            )
        assertSuccess(library)
        assertError(
            "@LbToolbox class Tools : Remote { override fun forecast(days: Int?): String = \"\" }",
            "can't read the default of parameter days: write = null in its source",
            classpath = library.outputDirectory,
        )
    }

    @Test
    fun defaultDecision() {
        assertEquals(null, defaultProblem("days", nullable = true, written = "null"))
        assertTrue(defaultProblem("days", nullable = true, written = null)!!.startsWith("can't read the default"))
        assertTrue(defaultProblem("days", nullable = true, written = "3")!!.contains("other than = null"))
        assertTrue(defaultProblem("days", nullable = false, written = "null")!!.contains("other than = null"))
    }

    @Test
    fun nullDefaultWithCommentsIsAccepted() {
        val result =
            compile(
                """
                @LbToolbox class Tools {
                    @LbTool("Forecast")
                    fun forecast(
                        @LbToolParam days: Int? = null /* the model may leave it out */,
                        @LbToolParam hours: Int? = null // so may this
                    ): String = ""
                }
                """,
            )
        assertSuccess(result)
        assertTrue("val hours: kotlin.Int? = null," in generated(result, "Tools_LbToolbox.kt"))
    }

    @Test
    fun toolsGeneratedCodeCantCall() {
        assertError(
            "@LbToolbox class Tools { @LbTool(\"Pings\") private fun ping(): String = \"\" }",
            "tool ping is not visible to generated code",
        )
        assertError(
            "@LbToolbox open class Tools { @LbTool(\"Pings\") protected fun ping(): String = \"\" }",
            "tool ping is not visible to generated code",
        )
        assertError(
            "@LbToolbox class Tools { @LbTool(\"Echoes\") fun <T> echo(@LbToolParam x: String): String = x }",
            "tool echo has type parameters",
        )
        assertError(
            "@LbToolbox class Tools { @LbTool(\"Shouts\") fun String.shout(): String = uppercase() }",
            "tool shout is an extension",
        )
        assertError("@LbTool(\"Pings\") fun ping(): String = \"\"", "tool ping is not in a class")
    }

    @Test
    fun toolboxesGeneratedCodeCantReach() {
        assertError(
            "@LbToolbox private class Tools { @LbTool(\"Pings\") fun ping(): String = \"\" }",
            "@LbToolbox Tools is not visible to generated code",
        )
        assertError(
            "class Outer { @LbToolbox protected class Tools { @LbTool(\"Pings\") fun ping(): String = \"\" } }",
            "@LbToolbox Tools is not visible to generated code",
        )
        assertError(
            "@LbToolbox class Tools<T> { @LbTool(\"Pings\") fun ping(): String = \"\" }",
            "@LbToolbox Tools has type parameters",
        )
    }

    @Test
    fun localToolboxGetsNoToolbox() {
        // KSP doesn't show a processor what is declared inside a function, so a local toolbox can't be reported:
        // it gets no asToolbox(), and a call to one fails to compile as unresolved
        val result =
            compile(
                """
                fun make(): Any {
                    @LbToolbox class Tools { @LbTool("Pings") fun ping(): String = "" }
                    return Tools()
                }
                """,
            )
        assertSuccess(result)
        assertTrue(result.sourcesGeneratedBySymbolProcessor.none())
    }

    @Test
    fun nameCollisionAcrossSupertypes() =
        assertError(
            """
            @LbToolbox interface Clock { @LbTool("The time") fun now(): String }
            @LbToolbox interface Watch { @LbTool("The time too", name = "now") fun current(): String }
            @LbToolbox object Both : Clock, Watch {
                override fun now(): String = ""
                override fun current(): String = ""
            }
            """,
            "two tools are named 'now' in Both: now and current",
        )

    @Test
    fun generatedFileNameClash() =
        assertError(
            """
            class Outer { @LbToolbox class Tools { @LbTool("Pings") fun ping(): String = "" } }
            @LbToolbox class Outer_Tools { @LbTool("Pongs") fun pong(): String = "" }
            """,
            "would both generate Outer_Tools_LbToolbox.kt: rename one of them",
        )

    // ---- generation

    @Test
    fun snakeCaseNameAndPrefix() {
        val result =
            compile(
                """
                @LbToolbox(prefix = "weather_") object Weather {
                    @LbTool("Forecast") fun getForecast(): String = ""
                    @LbTool("Status") suspend fun readHTTPStatus2Code(): Int = 200
                    @LbTool("Named", name = "Lookup-City") fun whatever(): String = ""
                }
                """,
            )
        assertSuccess(result)
        assertEquals(
            listOf("weather_get_forecast", "weather_read_http_status2_code", "weather_Lookup-City"),
            toolNames(result, "app.Weather"),
        )
    }

    @Test
    fun supertypeToolsIncludedOnce() {
        val result =
            compile(
                """
                @LbToolbox interface Clock { @LbTool("The time") fun now(): String }
                @LbToolbox interface Calendar : Clock { @LbTool("The date") fun today(): String }
                @LbToolbox interface Alarms : Clock { @LbTool("Sets an alarm") fun setAlarm(@LbToolParam at: String) }
                // Clock reached twice; now overridden without an annotation, which is how an override implements a tool
                @LbToolbox object Assistant : Calendar, Alarms {
                    override fun now(): String = "noon"
                    override fun today(): String = "Monday"
                    override fun setAlarm(at: String) {}
                    @LbTool("Says hello") fun hello(): String = "hi"
                }
                """,
            )
        assertSuccess(result)
        val names = toolNames(result, "app.Assistant")
        assertEquals(names.toSet(), setOf("hello", "today", "now", "set_alarm"))
        assertEquals(4, names.size)
    }

    @Test
    fun separateParamsGenerateAnArgsClass() {
        val result =
            compile(
                """
                @LbToolbox class Tools {
                    @LbTool("Forecast")
                    fun forecast(@LbToolParam("The city") city: String, @LbToolParam days: Int? = null): String = ""
                }
                """,
            )
        assertSuccess(result)
        val source = generated(result, "Tools_LbToolbox.kt")
        assertTrue(source, "@kotlinx.serialization.Serializable" in source)
        assertTrue(source, "private class Tools_LbArgs_forecast(" in source)
        assertTrue(source, "@com.suhel.llamabro.typed.LbDescription(\"The city\") val city: kotlin.String," in source)
        assertTrue(source, "val days: kotlin.Int? = null," in source)
        assertTrue(source, "box.forecast(args.city, args.days)" in source)
    }

    @Test
    fun contextParamLeftOutOfArgs() {
        val result =
            compile(
                """
                @Serializable data class Query(val q: String)
                @LbToolbox class Tools {
                    @LbTool("Search") suspend fun search(args: Query, call: LbToolCallContext): String = call.callId
                    @LbTool("Echo") suspend fun echo(call: LbToolCallContext, @LbToolParam text: String): String = text
                }
                """,
            )
        assertSuccess(result)
        val source = generated(result, "Tools_LbToolbox.kt")
        assertTrue(source, "tool<app.Query, kotlin.String>(" in source)
        assertTrue(source, "box.search(args, this@tool)" in source)
        assertTrue(source, "box.echo(this@tool, args.text)" in source)
        assertFalse(source, "val call" in source)
    }

    @Test
    fun nonSuspendIsWrappedInIo() {
        val result =
            compile(
                """
                @LbToolbox class Tools {
                    @LbTool("Blocks") fun blocking(): String = ""
                    @LbTool("Suspends") suspend fun suspending(): String = ""
                }
                """,
            )
        assertSuccess(result)
        val source = generated(result, "Tools_LbToolbox.kt")
        assertTrue(
            source,
            "kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { box.blocking() }" in source,
        )
        // no arguments: the lambda's parameter is `_`, so generated code adds no unused-parameter warning
        assertTrue(source, "{ _ -> box.suspending() }" in source)
    }

    @Test
    fun visibilityFollowsTheToolbox() {
        val result =
            compile(
                """
                @LbToolbox class Open { @LbTool("Pings") fun ping(): String = "" }
                @LbToolbox internal class Hidden { @LbTool("Pings") fun ping(): String = "" }
                internal class Outer { @LbToolbox class Nested { @LbTool("Pings") fun ping(): String = "" } }
                """,
            )
        assertSuccess(result)
        assertTrue("public fun app.Open.asToolbox(" in generated(result, "Open_LbToolbox.kt"))
        assertTrue("internal fun app.Hidden.asToolbox(" in generated(result, "Hidden_LbToolbox.kt"))
        assertTrue("internal fun app.Outer.Nested.asToolbox(" in generated(result, "Outer_Nested_LbToolbox.kt"))
    }

    // ---- harness

    /** Compiles [snippet] with the processor; with [classpath] (an earlier compilation), the stand-ins come from it. */
    private fun compile(snippet: String, classpath: File? = null): JvmCompilationResult =
        KotlinCompilation()
            .apply {
                val app = SourceFile.kotlin("App.kt", HEADER + snippet.trimIndent())
                sources =
                    if (classpath == null) listOf(SourceFile.kotlin("StandIns.kt", STAND_INS), app) else listOf(app)
                if (classpath != null) classpaths += classpath
                inheritClassPath = true
                messageOutputStream = System.out
                useKsp2()
                configureKsp { symbolProcessorProviders += ToolboxProcessorProvider() }
            }.compile()

    private fun assertError(snippet: String, expected: String, classpath: File? = null) {
        val result = compile(snippet, classpath)
        assertEquals(result.messages, KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertTrue(result.messages, "e: " in result.messages && expected in result.messages)
    }

    private fun assertSuccess(result: JvmCompilationResult) =
        assertEquals(result.messages, KotlinCompilation.ExitCode.OK, result.exitCode)

    private fun generated(result: JvmCompilationResult, name: String): String =
        result.sourcesGeneratedBySymbolProcessor.single { it.name == name }.readText()

    /** The tool names of the toolbox [className] builds, read through the stand-in `tool`. */
    private fun toolNames(result: JvmCompilationResult, className: String): List<String> {
        val box = result.classLoader.loadClass(className)
        val instance = box.getField("INSTANCE").get(null)
        val file = result.classLoader.loadClass(className.substringAfterLast('.').let { "app.${it}_LbToolboxKt" })
        val toolbox = file.methods.single { it.name == "asToolbox" }.invoke(null, instance, Json)
        val tools = toolbox.javaClass.getMethod("getTools").invoke(toolbox) as List<*>
        return tools.map { it!!.javaClass.getMethod("getName").invoke(it) as String }
    }

    private companion object {
        const val HEADER =
            "package app\n\nimport com.suhel.llamabro.typed.*\n" +
                "import kotlinx.serialization.SerialName\nimport kotlinx.serialization.Serializable\n\n"

        /** llamabro-typed's annotations and the calls generated code makes, in its package; bodies are minimal. */
        val STAND_INS =
            """
            package com.suhel.llamabro.typed

            import kotlinx.serialization.json.Json

            annotation class LbToolbox(val prefix: String = "")
            annotation class LbTool(val description: String, val name: String = "", val requiresApproval: Boolean = false)
            annotation class LbToolParam(val description: String = "")
            annotation class LbDescription(val text: String)

            class LbToolCallContext(val callId: String, val toolName: String, val rawArguments: String)
            class TypedTool<A, R>(val name: String)
            interface Toolbox { val tools: List<TypedTool<*, *>> }
            fun toolboxOf(vararg tools: TypedTool<*, *>): Toolbox =
                object : Toolbox { override val tools = tools.toList() }
            inline fun <reified A, reified R> tool(
                name: String,
                description: String,
                requiresApproval: Boolean = false,
                json: Json = Json,
                noinline handler: suspend LbToolCallContext.(A) -> R,
            ): TypedTool<A, R> = TypedTool(name)
            """.trimIndent()
    }
}
