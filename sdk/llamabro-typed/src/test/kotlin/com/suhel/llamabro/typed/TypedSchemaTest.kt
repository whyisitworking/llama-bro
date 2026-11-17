@file:OptIn(ExperimentalSerializationApi::class)

package com.suhel.llamabro.typed

import kotlinx.serialization.Contextual
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Polymorphic
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNamingStrategy
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date

class TypedSchemaTest {
    @Serializable
    data class Primitives(
        val flag: Boolean,
        val byte: Byte,
        val short: Short,
        val int: Int,
        val long: Long,
        val float: Float,
        val double: Double,
        val char: Char,
        val text: String,
        val id: Id,
    )

    @Serializable
    @JvmInline
    value class Id(val raw: String)

    @Test
    fun primitivesAndStrings() {
        assertSchema(
            obj(
                """"flag":{"type":"boolean"},"byte":$BYTE,"short":$SHORT,""" +
                    """"int":$INT,"long":{"type":"integer"},"float":{"type":"number"},""" +
                    """"double":{"type":"number"},"char":{"type":"string","minLength":1,"maxLength":1},""" +
                    """"text":{"type":"string"},"id":{"type":"string"}""",
                "flag",
                "byte",
                "short",
                "int",
                "long",
                "float",
                "double",
                "char",
                "text",
                "id",
            ),
            typedSchema<Primitives>(),
        )
    }

    @Serializable
    enum class Mood {
        @SerialName("happy")
        Happy,
        Sad,
    }

    @Serializable
    data class WithEnum(val mood: Mood)

    @Test
    fun enumUsesSerialNames() {
        assertSchema(obj(""""mood":{"type":"string","enum":["happy","Sad"]}""", "mood"), typedSchema<WithEnum>())
    }

    @Serializable
    data class WithNullable(val note: String?, val count: Int? = null)

    @Test
    fun nullableIsAnyOfNull() {
        assertSchema(
            obj(
                """"note":{"anyOf":[{"type":"string"},{"type":"null"}]},""" +
                    """"count":{"anyOf":[$INT,{"type":"null"}]}""",
                "note",
            ),
            typedSchema<WithNullable>(),
        )
    }

    @Test
    fun explicitNullsOffMakesANullableFieldOptional() {
        assertSchema(
            obj(
                """"note":{"anyOf":[{"type":"string"},{"type":"null"}]},""" +
                    """"count":{"anyOf":[$INT,{"type":"null"}]}""",
            ),
            typedSchema<WithNullable>(Json { explicitNulls = false }),
        )
    }

    @Serializable
    data class WithDefaults(val name: String, val size: Int = 3, val tag: String)

    @Test
    fun defaultMakesAFieldOptional() {
        assertSchema(
            obj(""""name":{"type":"string"},"size":$INT,"tag":{"type":"string"}""", "name", "tag"),
            typedSchema<WithDefaults>(),
        )
    }

    @Serializable
    data class Ordered(val zeta: String, val alpha: String, val reasoning: String, val answer: Int)

    @Test
    fun declarationOrderIsKept() {
        val schema = typedSchema<Ordered>()
        assertEquals(
            listOf("zeta", "alpha", "reasoning", "answer"),
            Json
                .parseToJsonElement(schema)
                .jsonObject["properties"]!!
                .jsonObject.keys
                .toList(),
        )
        assertSchema(
            obj(
                """"zeta":{"type":"string"},"alpha":{"type":"string"},"reasoning":{"type":"string"},""" +
                    """"answer":$INT""",
                "zeta",
                "alpha",
                "reasoning",
                "answer",
            ),
            schema,
        )
    }

    @Serializable
    data class Address(val city: String)

    @Serializable
    data class Person(val name: String, val address: Address)

    @Test
    fun nestedClassIsInlineClosedObject() {
        assertSchema(
            obj(
                """"name":{"type":"string"},"address":${obj(""""city":{"type":"string"}""", "city")}""",
                "name",
                "address",
            ),
            typedSchema<Person>(),
        )
    }

    @Serializable
    data class Basket(
        @LbItems(min = 1, max = 5) val items: List<String>,
        val tags: Set<Int>,
    )

    @Test
    fun listWithItemBounds() {
        assertSchema(
            obj(
                """"items":{"type":"array","items":{"type":"string"},"minItems":1,"maxItems":5},""" +
                    """"tags":{"type":"array","items":$INT}""",
                "items",
                "tags",
            ),
            typedSchema<Basket>(),
        )
    }

    @Serializable
    data class Scores(val scores: Map<String, Int>)

    @Test
    fun mapOfStringIsAdditionalProperties() {
        assertSchema(
            obj(""""scores":{"type":"object","additionalProperties":$INT}""", "scores"),
            typedSchema<Scores>(),
        )
    }

    @Serializable
    sealed interface Shape {
        @Serializable
        @SerialName("circle")
        data class Circle(val radius: Double) : Shape

        @Serializable
        @SerialName("square")
        data class Square(val side: Double, val filled: Boolean = false) : Shape

        @Serializable
        @SerialName("none")
        data object Blank : Shape
    }

    @Serializable
    data class Drawing(val shape: Shape)

    @Test
    fun sealedIsAnyOfWithConstDiscriminator() {
        // subclasses in the serialization plugin's order (by class name), not declaration order
        val shapes =
            """{"anyOf":[""" +
                obj(""""type":{"const":"none"}""", "type") + "," +
                obj(""""type":{"const":"circle"},"radius":{"type":"number"}""", "type", "radius") + "," +
                obj(
                    """"type":{"const":"square"},"side":{"type":"number"},"filled":{"type":"boolean"}""",
                    "type",
                    "side",
                ) +
                "]}"
        assertSchema(obj(""""shape":$shapes""", "shape"), typedSchema<Drawing>())
        assertSchema(
            shapes.replace("\"type\":{\"const\"", "\"kind\":{\"const\"").replace("[\"type\"", "[\"kind\""),
            typedSchema<Shape>(Json { classDiscriminator = "kind" }),
        )
    }

    @Serializable
    @LbDescription("A booking")
    data class Booking(
        @LbDescription("Guests") @LbRange(min = 1, max = 8) val guests: Int,
        @LbRange(min = 0) val nights: Long,
        @LbLength(min = 2, max = 40) @LbPattern("^[A-Z].*$") val name: String,
        @LbLength(max = 10) val code: String,
        @LbItems(max = 3) val extras: List<String>,
        @LbDescription("When") val note: String?,
        @LbRange val plain: Int,
    )

    @Test
    fun annotationsWriteTheirKeywords() {
        assertSchema(
            """{"type":"object","description":"A booking","properties":{""" +
                """"guests":{"type":"integer","description":"Guests","minimum":1,"maximum":8},""" +
                """"nights":{"type":"integer","minimum":0},""" +
                """"name":{"type":"string","minLength":2,"maxLength":40,"pattern":"^[A-Z].*$"},""" +
                """"code":{"type":"string","maxLength":10},""" +
                """"extras":{"type":"array","items":{"type":"string"},"maxItems":3},""" +
                """"note":{"anyOf":[{"type":"string","description":"When"},{"type":"null"}]},""" +
                """"plain":$INT},""" +
                """"required":["guests","nights","name","code","extras","note","plain"],"additionalProperties":false}""",
            typedSchema<Booking>(),
        )
    }

    @Serializable
    data class Misplaced(
        @LbRange(min = 1) val name: String,
    )

    @Test
    fun annotationOnTheWrongTypeIsRefused() {
        val e = assertThrows(IllegalArgumentException::class.java) { typedSchema<Misplaced>() }
        assertTrue(e.message, e.message!!.contains("\$.name"))
    }

    @Serializable
    data class Unsigned(
        val ubyte: UByte,
        val ushort: UShort,
        val uint: UInt,
        val ulong: ULong,
        val maybe: UInt?,
    )

    @Test
    fun unsignedTypesAreBoundedToTheirRange() {
        assertSchema(
            obj(
                """"ubyte":{"type":"integer","minimum":0,"maximum":255},""" +
                    """"ushort":{"type":"integer","minimum":0,"maximum":65535},""" +
                    """"uint":{"type":"integer","minimum":0,"maximum":4294967295},""" +
                    """"ulong":{"type":"integer","minimum":0},""" +
                    """"maybe":{"anyOf":[{"type":"integer","minimum":0,"maximum":4294967295},{"type":"null"}]}""",
                "ubyte",
                "ushort",
                "uint",
                "ulong",
                "maybe",
            ),
            typedSchema<Unsigned>(),
        )
    }

    @Serializable
    data class BoundedUnsigned(
        @LbDescription("Count") @LbRange(min = -5, max = 10) val inside: UInt,
        @LbRange(min = 3, max = 1000) val clamped: UByte,
        @LbRange(min = 7) val low: ULong,
        @LbRange(max = 99) val high: ULong,
        @LbRange(min = 100) val plain: UShort,
    )

    @Test
    fun rangeOnAnUnsignedTypeIsIntersectedWithItsRange() {
        assertSchema(
            obj(
                """"inside":{"type":"integer","description":"Count","minimum":0,"maximum":10},""" +
                    """"clamped":{"type":"integer","minimum":3,"maximum":255},""" +
                    """"low":{"type":"integer","minimum":7},""" +
                    """"high":{"type":"integer","minimum":0,"maximum":99},""" +
                    """"plain":{"type":"integer","minimum":100,"maximum":65535}""",
                "inside",
                "clamped",
                "low",
                "high",
                "plain",
            ),
            typedSchema<BoundedUnsigned>(),
        )
    }

    @Serializable
    data class EmptyUnsigned(
        @LbRange(max = -1) val never: UInt,
    )

    @Test
    fun rangeOutsideAnUnsignedTypeIsRefused() {
        val e = assertThrows(IllegalArgumentException::class.java) { typedSchema<EmptyUnsigned>() }
        assertTrue(e.message, e.message!!.contains("\$.never"))
        assertTrue(e.message, e.message!!.contains("UInt"))
    }

    @Serializable
    data class BigInt(
        @LbRange(max = 10_000_000_000) val n: Int,
        @LbRange(min = 1, max = 5) val small: Int,
    )

    @Test
    fun rangeOnASignedTypeIsClampedToItsRange() {
        val props = Json.parseToJsonElement(typedSchema<BigInt>()).jsonObject["properties"]!!.jsonObject
        assertEquals("-2147483648", props["n"]!!.jsonObject["minimum"].toString())
        assertEquals("2147483647", props["n"]!!.jsonObject["maximum"].toString())
        assertEquals("1", props["small"]!!.jsonObject["minimum"].toString())
        assertEquals("5", props["small"]!!.jsonObject["maximum"].toString())
    }

    @Serializable
    data class SignedFields(
        val byte: Byte,
        val short: Short,
        val int: Int,
        val long: Long,
        @LbRange(min = 1) val atLeastOne: Int,
        @LbRange(max = 9) val atMostNine: Short,
    )

    @Test
    fun integerFieldsCarryTheirTypesBounds() {
        // a Long's bounds are the grammar's own unbounded integer, so none is written (F158)
        assertSchema(
            obj(
                """"byte":$BYTE,"short":$SHORT,"int":$INT,"long":{"type":"integer"},""" +
                    """"atLeastOne":{"type":"integer","minimum":1,"maximum":2147483647},""" +
                    """"atMostNine":{"type":"integer","minimum":-32768,"maximum":9}""",
                "byte",
                "short",
                "int",
                "long",
                "atLeastOne",
                "atMostNine",
            ),
            typedSchema<SignedFields>(),
        )
    }

    @Serializable
    data class Shorts(
        @LbRange(min = -100_000, max = 100_000) val s: Short,
    )

    @Test
    fun rangeOnAShortIsClampedToItsRange() {
        val s =
            Json
                .parseToJsonElement(typedSchema<Shorts>())
                .jsonObject["properties"]!!
                .jsonObject["s"]!!
                .jsonObject
        assertEquals("-32768", s["minimum"].toString())
        assertEquals("32767", s["maximum"].toString())
    }

    @Serializable
    data class EmptyByte(
        @LbRange(min = 200) val never: Byte,
    )

    @Test
    fun rangeOutsideASignedTypeIsRefused() {
        val e = assertThrows(IllegalArgumentException::class.java) { typedSchema<EmptyByte>() }
        assertTrue(e.message, e.message!!.contains("\$.never"))
        assertTrue(e.message, e.message!!.contains("Byte"))
    }

    @Serializable
    data class Named(
        val firstName: String,
        @SerialName("years") val ageInYears: Int,
        val nested: Inner,
    )

    @Serializable
    data class Inner(val innerValue: Int)

    @Test
    fun namingStrategyAndSerialNameApply() {
        assertSchema(
            obj(
                """"first_name":{"type":"string"},"years":$INT,""" +
                    """"nested":${obj(""""inner_value":$INT""", "inner_value")}""",
                "first_name",
                "years",
                "nested",
            ),
            typedSchema<Named>(Json { namingStrategy = JsonNamingStrategy.SnakeCase }),
        )
    }

    @Serializable
    data class Node(val name: String, val children: List<Node>)

    @Test
    fun recursiveTypeIsRefusedWithPath() {
        val e = assertThrows(IllegalArgumentException::class.java) { typedSchema<Node>() }
        assertTrue(e.message, e.message!!.contains("\$.children[]"))
        assertTrue(e.message, e.message!!.contains("recursive"))
    }

    @Polymorphic
    interface Animal

    @Serializable
    data class Zoo(val star: Animal)

    @Test
    fun openPolymorphismIsRefused() {
        val e = assertThrows(IllegalArgumentException::class.java) { typedSchema<Zoo>() }
        assertTrue(e.message, e.message!!.contains("\$.star"))
    }

    @Serializable
    data class WithContextual(
        @Contextual val at: Date,
    )

    @Test
    fun contextualIsRefused() {
        val e = assertThrows(IllegalArgumentException::class.java) { typedSchema<WithContextual>() }
        assertTrue(e.message, e.message!!.contains("\$.at"))
    }

    @Serializable
    data class IntKeys(val byId: Map<Int, String>)

    @Test
    fun nonStringMapKeyIsRefused() {
        val e = assertThrows(IllegalArgumentException::class.java) { typedSchema<IntKeys>() }
        assertTrue(e.message, e.message!!.contains("\$.byId"))
    }

    @Serializable
    sealed class Event {
        @Serializable
        data class Click(val x: Int) : Event()
    }

    @Serializable
    data class Log(val event: Event)

    @Test
    fun sealedSubclassWithoutSerialNameIsRefused() {
        val e = assertThrows(IllegalArgumentException::class.java) { typedSchema<Log>() }
        assertTrue(e.message, e.message!!.contains("\$.event"))
        assertTrue(e.message, e.message!!.contains("@SerialName"))
    }

    @Test
    fun assertTypedSchemaThrowsForABadType() {
        assertThrows(IllegalArgumentException::class.java) { assertTypedSchema<Node>() }
        assertTypedSchema<Person>()
    }

    @Test
    fun topLevelValuesAreAllowed() {
        assertSchema("""{"type":"array","items":{"type":"string"}}""", typedSchema<List<String>>())
    }

    @Serializable
    data class Trip(val from: Address, val to: Address)

    @Test
    fun siblingReuseIsAccepted() {
        val address = obj(""""city":{"type":"string"}""", "city")
        assertSchema(obj(""""from":$address,"to":$address""", "from", "to"), typedSchema<Trip>())
    }

    @Serializable
    data class Box<T>(val value: T)

    @Serializable
    data class Nested(val box: Box<Box<String>>, val pair: Pair<String, Pair<Int, Int>>)

    @Test
    fun nestedGenericsAreNotRecursion() {
        val box = obj(""""value":${obj(""""value":{"type":"string"}""", "value")}""", "value")
        val inner = obj(""""first":$INT,"second":$INT""", "first", "second")
        val pair = obj(""""first":{"type":"string"},"second":$inner""", "first", "second")
        assertSchema(obj(""""box":$box,"pair":$pair""", "box", "pair"), typedSchema<Nested>())
    }

    @Serializable
    data class Tree<T>(val value: T, val children: List<Tree<T>>)

    @Test
    fun genericRecursionIsRefused() {
        val e = assertThrows(IllegalArgumentException::class.java) { typedSchema<Tree<String>>() }
        assertTrue(e.message, e.message!!.contains("\$.children[]"))
    }

    @Serializable
    data class Twisted<T>(val value: T, val deeper: Twisted<List<T>>? = null)

    @Test
    fun polymorphicRecursionIsRefused() {
        // each level is a new type (Twisted<Int>, Twisted<List<Int>>, …), so no descriptor repeats: without a
        // limit the schema would never end
        val e = assertThrows(IllegalArgumentException::class.java) { typedSchema<Twisted<Int>>() }
        assertTrue(e.message, e.message!!.contains("\$.deeper"))
        assertTrue(e.message, e.message!!.contains("Twisted"))
        assertTrue(e.message, e.message!!.contains("type arguments"))
    }

    @Serializable
    data class FreeForm(val name: String, val extra: JsonElement)

    @Serializable
    data class FreeFormObject(val extra: JsonObject)

    @Serializable
    data class FreeFormArray(val extra: JsonArray)

    @Test
    fun jsonTreeTypesAreRefused() {
        for (refused in listOf<() -> String>(
            { typedSchema<FreeForm>() },
            { typedSchema<FreeFormObject>() },
            { typedSchema<FreeFormArray>() },
        )) {
            val e = assertThrows(IllegalArgumentException::class.java) { refused() }
            assertTrue(e.message, e.message!!.contains("\$.extra"))
            assertTrue(e.message, e.message!!.contains("free-form JSON"))
        }
    }

    private object HandWrittenSealed : KSerializer<String> {
        @OptIn(InternalSerializationApi::class)
        override val descriptor: SerialDescriptor = buildSerialDescriptor("custom.Sealed", PolymorphicKind.SEALED)

        override fun serialize(encoder: Encoder, value: String) = encoder.encodeString(value)

        override fun deserialize(decoder: Decoder): String = decoder.decodeString()
    }

    @Serializable
    data class WithHandWritten(
        @Serializable(with = HandWrittenSealed::class) val odd: String,
    )

    @Test
    fun unknownSealedLayoutIsRefused() {
        val e = assertThrows(IllegalArgumentException::class.java) { typedSchema<WithHandWritten>() }
        assertTrue(e.message, e.message!!.contains("\$.odd"))
        assertTrue(e.message, e.message!!.contains("custom.Sealed"))
    }

    @Serializable
    @LbDescription("An ISO 4217 code")
    @JvmInline
    value class Currency(val code: String)

    @Serializable
    data class Price(
        val currency: Currency,
        @LbDescription("Paid in") val paidIn: Currency,
    )

    @Test
    fun valueClassDescriptionCarriesThrough() {
        assertSchema(
            obj(
                """"currency":{"type":"string","description":"An ISO 4217 code"},""" +
                    """"paidIn":{"type":"string","description":"Paid in"}""",
                "currency",
                "paidIn",
            ),
            typedSchema<Price>(),
        )
    }

    @Serializable
    data class Gaps(val words: List<String?>)

    @Test
    fun listOfNullableItems() {
        assertSchema(
            obj(""""words":{"type":"array","items":{"anyOf":[{"type":"string"},{"type":"null"}]}}""", "words"),
            typedSchema<Gaps>(),
        )
    }

    @Serializable
    @JsonClassDiscriminator("op")
    sealed interface Command {
        @Serializable
        @SerialName("stop")
        data object Stop : Command
    }

    @Test
    fun jsonClassDiscriminatorApplies() {
        assertSchema("""{"anyOf":[${obj(""""op":{"const":"stop"}""", "op")}]}""", typedSchema<Command>())
    }

    @Serializable
    data class Clash(
        val fooBar: String,
        @SerialName("foo_bar") val other: String,
    )

    @Test
    fun namingStrategyCollisionSaysSo() {
        val e =
            assertThrows(IllegalArgumentException::class.java) {
                typedSchema<Clash>(Json { namingStrategy = JsonNamingStrategy.SnakeCase })
            }
        assertTrue(e.message, e.message!!.contains("naming strategy"))
    }

    private companion object {
        const val BYTE = """{"type":"integer","minimum":-128,"maximum":127}"""
        const val SHORT = """{"type":"integer","minimum":-32768,"maximum":32767}"""
        const val INT = """{"type":"integer","minimum":-2147483648,"maximum":2147483647}"""
    }

    private fun assertSchema(expected: String, actual: String) {
        // the strings themselves, since key order is part of the schema (F143); parsing first proves both are JSON
        assertEquals(Json.parseToJsonElement(expected) as JsonObject, Json.parseToJsonElement(actual))
        assertEquals(expected, actual)
    }

    private fun obj(properties: String, vararg required: String): String =
        """{"type":"object","properties":{$properties},""" +
            (if (required.isEmpty()) "" else required.joinToString(",", """"required":[""", "],") { "\"$it\"" }) +
            """"additionalProperties":false}"""
}
