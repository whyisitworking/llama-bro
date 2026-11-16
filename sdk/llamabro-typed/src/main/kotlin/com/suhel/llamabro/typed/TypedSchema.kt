@file:OptIn(ExperimentalSerializationApi::class)

package com.suhel.llamabro.typed

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.descriptors.elementDescriptors
import kotlinx.serialization.descriptors.nonNullOriginal
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.serializer
import kotlin.reflect.KClass

/**
 * The JSON Schema of [serializer]'s type, as llama.cpp's grammar reads it (F143), for `ResponseFormat.JsonSchema` and
 * a tool's parameters. [json] is the `Json` that decodes the reply: its naming strategy, class discriminator and
 * `explicitNulls` shape the schema.
 *
 * Properties keep their declaration order, which is the order the model writes them in, required ones first (F143):
 * declare a reasoning field before the answer. A property with a default is optional; objects are closed; a nullable
 * type is `anyOf` with `null`; enums use their serial names; a sealed class is an `anyOf` of its subclasses' objects,
 * each opening with the discriminator as a `const`; `Map<String, V>` is an object of `V`s; an unsigned integer is
 * bounded to its type's range. [LbDescription], [LbRange], [LbLength], [LbPattern] and [LbItems] add their keywords.
 *
 * @throws IllegalArgumentException naming the field's path for what a schema can't hold: a recursive type, open
 *   polymorphism, a contextual serializer, a map key that isn't a `String`, a sealed subclass without `@SerialName`,
 *   or an annotation on a type it doesn't bound.
 */
public fun <T> typedSchema(serializer: KSerializer<T>, json: Json = Json): String =
    SchemaWriter(json).schema(serializer.descriptor, ROOT, emptyList(), emptyList()).toString()

/** The JSON Schema of [T]; see the overload that takes a serializer. */
public inline fun <reified T> typedSchema(json: Json = Json): String =
    typedSchema(json.serializersModule.serializer<T>(), json)

/**
 * Throws [IllegalArgumentException] if [T] has no schema, so an app's unit tests find what a typed call would refuse
 * at its first use. It can't check an `@LbPattern`: llama.cpp rejects the regex it doesn't support when it builds the
 * grammar, so a bad pattern fails the first request, and in a conversation with typed tools every request, since the
 * tools go with each one. Only a test against a real model catches it.
 */
public inline fun <reified T> assertTypedSchema(json: Json = Json) {
    typedSchema<T>(json)
}

private const val ROOT = "$"

private class SchemaWriter(private val json: Json) {
    init {
        require(!json.configuration.useArrayPolymorphism) {
            "typedSchema: a Json with useArrayPolymorphism has no object schema for sealed classes"
        }
    }

    /**
     * [annotations] are the property's (they bound its value), [stack] the classes open on the way here, which find
     * recursion. Descriptors compare by type arguments too, so `Box<Box<String>>` is not recursive and `Node<T>`
     * holding a `Node<T>` is.
     */
    fun schema(
        descriptor: SerialDescriptor,
        path: String,
        annotations: List<Annotation>,
        stack: List<SerialDescriptor>,
    ): JsonObject {
        if (descriptor.isNullable) {
            val inner = schema(descriptor.nonNullOriginal, path, annotations, stack)
            return JsonObject(mapOf("anyOf" to JsonArray(listOf(inner, JsonObject(mapOf("type" to str("null")))))))
        }
        if (descriptor.isInline) return inline(descriptor, path, annotations, stack)
        require(descriptor.serialName !in JSON_TREE_TYPES) {
            refusal(
                path,
                "${descriptor.serialName.substringAfterLast('.')} is free-form JSON, which a schema can't hold; " +
                    "declare the fields as a @Serializable class",
            )
        }
        val description = annotations.description() ?: descriptor.annotations.description()
        return when (val kind = descriptor.kind) {
            is PrimitiveKind -> {
                primitive(kind, description, path, annotations)
            }

            SerialKind.ENUM -> {
                enumeration(descriptor, description, path, annotations)
            }

            StructureKind.LIST -> {
                list(descriptor, description, path, annotations, stack)
            }

            StructureKind.MAP -> {
                map(descriptor, description, path, annotations, stack)
            }

            StructureKind.CLASS, StructureKind.OBJECT -> {
                checkAnnotations(path, annotations, "an object")
                objectOf(descriptor, description, path, stack, discriminator = null)
            }

            PolymorphicKind.SEALED -> {
                checkAnnotations(path, annotations, "a sealed class")
                sealed(descriptor, description, path, stack)
            }

            PolymorphicKind.OPEN -> {
                throw IllegalArgumentException(
                    refusal(
                        path,
                        "${descriptor.serialName} is open polymorphism; only a sealed class has a fixed set of shapes",
                    ),
                )
            }

            SerialKind.CONTEXTUAL -> {
                throw IllegalArgumentException(
                    refusal(
                        path,
                        "${descriptor.serialName} has a contextual serializer, whose shape is known only at runtime",
                    ),
                )
            }
        }
    }

    private fun inline(
        descriptor: SerialDescriptor,
        path: String,
        annotations: List<Annotation>,
        stack: List<SerialDescriptor>,
    ): JsonObject {
        require(descriptor.elementsCount == 1) {
            refusal(path, "the inline class ${descriptor.serialName} has no single underlying value")
        }
        // a description on the value class itself carries to its value, unless the property has its own
        val own = annotations.description() != null
        val carried = if (own) annotations else annotations + descriptor.annotations.descriptions()
        if (descriptor.serialName !in UNSIGNED_MAX) {
            return schema(descriptor.getElementDescriptor(0), path, carried, stack)
        }
        // the signed twin's own clamp would cut an unsigned bound (a UByte's 200 to a Byte's 127), so the range is
        // applied here, to the unsigned range
        val range = carried.find<LbRange>()
        val value = schema(descriptor.getElementDescriptor(0), path, carried.filter { it !is LbRange }, stack)
        val bounded = if (range == null) value else withRange(value, range, path)
        return unsigned(bounded, descriptor.serialName, UNSIGNED_MAX[descriptor.serialName], path)
    }

    private fun withRange(value: JsonObject, range: LbRange, path: String): JsonObject {
        require(range.min <= range.max) { refusal(path, "@LbRange's min ${range.min} is above its max ${range.max}") }
        val out = LinkedHashMap(value)
        if (range.min != Long.MIN_VALUE) out["minimum"] = JsonPrimitive(range.min)
        if (range.max != Long.MAX_VALUE) out["maximum"] = JsonPrimitive(range.max)
        return JsonObject(out)
    }

    /**
     * An unsigned type is serialized from its signed twin, so without bounds the model's `-5` decodes as 4294967291:
     * bound it to the type's range, and an [LbRange] to its intersection with that range.
     */
    private fun unsigned(value: JsonObject, serialName: String, typeMax: Long?, path: String): JsonObject {
        val min = maxOf(value["minimum"]?.jsonPrimitive?.long ?: 0L, 0L)
        val max = listOfNotNull(value["maximum"]?.jsonPrimitive?.long, typeMax).minOrNull()
        require(max == null || min <= max) {
            refusal(path, "@LbRange leaves no value a ${serialName.substringAfterLast('.')} can hold")
        }
        val out = LinkedHashMap(value.filterKeys { it != "minimum" && it != "maximum" })
        out["minimum"] = JsonPrimitive(min)
        max?.let { out["maximum"] = JsonPrimitive(it) }
        return JsonObject(out)
    }

    private fun writeRange(out: MutableMap<String, JsonElement>, range: LbRange, kind: PrimitiveKind, path: String) {
        require(range.min <= range.max) { refusal(path, "@LbRange's min ${range.min} is above its max ${range.max}") }
        // an Int field's `max = 10_000_000_000` would otherwise ask the model for a value that can't decode; only a
        // bound the annotation set is clamped, a default bound stays unwritten
        val (typeMin, typeMax) = signedBounds(kind)
        val min = if (range.min == Long.MIN_VALUE) null else maxOf(range.min, typeMin)
        val max = if (range.max == Long.MAX_VALUE) null else minOf(range.max, typeMax)
        require((min ?: typeMin) <= (max ?: typeMax)) {
            refusal(path, "@LbRange leaves no value ${signedName(kind)} can hold")
        }
        min?.let { out["minimum"] = JsonPrimitive(it) }
        max?.let { out["maximum"] = JsonPrimitive(it) }
    }

    private fun signedName(kind: PrimitiveKind) =
        when (kind) {
            PrimitiveKind.BYTE -> "a Byte"
            PrimitiveKind.SHORT -> "a Short"
            else -> "an Int"
        }

    private fun signedBounds(kind: PrimitiveKind): Pair<Long, Long> =
        when (kind) {
            PrimitiveKind.BYTE -> Byte.MIN_VALUE.toLong() to Byte.MAX_VALUE.toLong()
            PrimitiveKind.SHORT -> Short.MIN_VALUE.toLong() to Short.MAX_VALUE.toLong()
            PrimitiveKind.INT -> Int.MIN_VALUE.toLong() to Int.MAX_VALUE.toLong()
            else -> Long.MIN_VALUE to Long.MAX_VALUE
        }

    private fun primitive(
        kind: PrimitiveKind,
        description: String?,
        path: String,
        annotations: List<Annotation>,
    ): JsonObject {
        val out = LinkedHashMap<String, JsonElement>()
        when (kind) {
            PrimitiveKind.BOOLEAN -> {
                checkAnnotations(path, annotations, "a boolean")
                out["type"] = str("boolean")
                out.describe(description)
            }

            PrimitiveKind.BYTE, PrimitiveKind.SHORT, PrimitiveKind.INT, PrimitiveKind.LONG -> {
                checkAnnotations(path, annotations, "an integer", LbRange::class)
                out["type"] = str("integer")
                out.describe(description)
                annotations.find<LbRange>()?.let { writeRange(out, it, kind, path) }
            }

            PrimitiveKind.FLOAT, PrimitiveKind.DOUBLE -> {
                // no decimal bounds: llama.cpp's grammar ignores them (F143)
                checkAnnotations(path, annotations, "a decimal number")
                out["type"] = str("number")
                out.describe(description)
            }

            PrimitiveKind.CHAR -> {
                checkAnnotations(path, annotations, "a character")
                out["type"] = str("string")
                out.describe(description)
                out["minLength"] = JsonPrimitive(1)
                out["maxLength"] = JsonPrimitive(1)
            }

            PrimitiveKind.STRING -> {
                checkAnnotations(path, annotations, "a string", LbLength::class, LbPattern::class)
                out["type"] = str("string")
                out.describe(description)
                annotations.find<LbLength>()?.let { length ->
                    require(length.min in 0..length.max) {
                        refusal(path, "@LbLength with min ${length.min} and max ${length.max}")
                    }
                    if (length.min != 0) out["minLength"] = JsonPrimitive(length.min)
                    if (length.max != Int.MAX_VALUE) out["maxLength"] = JsonPrimitive(length.max)
                }
                annotations.find<LbPattern>()?.let { out["pattern"] = str(it.regex) }
            }
        }
        return JsonObject(out)
    }

    private fun enumeration(
        descriptor: SerialDescriptor,
        description: String?,
        path: String,
        annotations: List<Annotation>,
    ): JsonObject {
        checkAnnotations(path, annotations, "an enum")
        val out = LinkedHashMap<String, JsonElement>()
        out["type"] = str("string")
        out.describe(description)
        out["enum"] = JsonArray(descriptor.elementNames().map(::str))
        return JsonObject(out)
    }

    private fun list(
        descriptor: SerialDescriptor,
        description: String?,
        path: String,
        annotations: List<Annotation>,
        stack: List<SerialDescriptor>,
    ): JsonObject {
        checkAnnotations(path, annotations, "a list", LbItems::class)
        val out = LinkedHashMap<String, JsonElement>()
        out["type"] = str("array")
        out.describe(description)
        out["items"] = schema(descriptor.getElementDescriptor(0), "$path[]", emptyList(), stack)
        annotations.find<LbItems>()?.let { items ->
            require(items.min in 0..items.max) { refusal(path, "@LbItems with min ${items.min} and max ${items.max}") }
            if (items.min != 0) out["minItems"] = JsonPrimitive(items.min)
            if (items.max != Int.MAX_VALUE) out["maxItems"] = JsonPrimitive(items.max)
        }
        return JsonObject(out)
    }

    private fun map(
        descriptor: SerialDescriptor,
        description: String?,
        path: String,
        annotations: List<Annotation>,
        stack: List<SerialDescriptor>,
    ): JsonObject {
        checkAnnotations(path, annotations, "a map")
        val key = descriptor.getElementDescriptor(0)
        require(!key.isNullable && key.kind == PrimitiveKind.STRING) {
            refusal(path, "a map keyed by ${key.serialName}; JSON object keys are strings, so only Map<String, V> fits")
        }
        val out = LinkedHashMap<String, JsonElement>()
        out["type"] = str("object")
        out.describe(description)
        out["additionalProperties"] = schema(descriptor.getElementDescriptor(1), "$path{}", emptyList(), stack)
        return JsonObject(out)
    }

    private fun objectOf(
        descriptor: SerialDescriptor,
        description: String?,
        path: String,
        stack: List<SerialDescriptor>,
        discriminator: Pair<String, String>?,
    ): JsonObject {
        require(descriptor !in stack) {
            refusal(path, "${descriptor.serialName} is recursive, and a schema of it would never end")
        }
        val inner = stack + descriptor
        val properties = LinkedHashMap<String, JsonElement>()
        val required = mutableListOf<String>()
        discriminator?.let { (name, value) ->
            properties[name] = JsonObject(mapOf("const" to str(value)))
            required += name
        }
        val strategy = json.configuration.namingStrategy.takeIf { descriptor.kind == StructureKind.CLASS }
        for (i in 0 until descriptor.elementsCount) {
            val serialName = descriptor.getElementName(i)
            val name = strategy?.serialNameForJson(descriptor, i, serialName) ?: serialName
            val element = descriptor.getElementDescriptor(i)
            require(name !in properties) {
                val whose =
                    if (name == discriminator?.first) {
                        "the class discriminator's"
                    } else {
                        "another property's under the Json's naming strategy"
                    }
                refusal("$path.$serialName", "its JSON name $name is $whose")
            }
            properties[name] = schema(element, "$path.$serialName", descriptor.getElementAnnotations(i), inner)
            val optional =
                descriptor.isElementOptional(i) || (element.isNullable && !json.configuration.explicitNulls)
            if (!optional) required += name
        }
        val out = LinkedHashMap<String, JsonElement>()
        out["type"] = str("object")
        out.describe(description)
        out["properties"] = JsonObject(properties)
        if (required.isNotEmpty()) out["required"] = JsonArray(required.map(::str))
        out["additionalProperties"] = JsonPrimitive(false)
        return JsonObject(out)
    }

    private fun sealed(
        descriptor: SerialDescriptor,
        description: String?,
        path: String,
        stack: List<SerialDescriptor>,
    ): JsonObject {
        val discriminator =
            descriptor.annotations
                .filterIsInstance<JsonClassDiscriminator>()
                .firstOrNull()
                ?.discriminator
                ?: json.configuration.classDiscriminator
        // the plugin's sealed serializer holds the discriminator, then one element whose elements are the subclasses;
        // any other sealed layout is a hand-written serializer whose JSON shape the descriptor doesn't tell
        val layoutKnown =
            descriptor.elementsCount == 2 &&
                descriptor.getElementDescriptor(1).let {
                    it.kind == SerialKind.CONTEXTUAL && it.serialName.startsWith("kotlinx.serialization.Sealed<")
                }
        require(layoutKnown) {
            refusal(
                path,
                "${descriptor.serialName} is sealed but not laid out as the serialization plugin's sealed classes",
            )
        }
        val variants =
            descriptor.getElementDescriptor(1).elementDescriptors.map { variant ->
                val name = variant.serialName
                require(!looksQualified(name)) {
                    refusal(
                        path,
                        "the sealed subclass $name has no @SerialName; its default name is its class's full name, " +
                            "which the model would have to write and a rename would break",
                    )
                }
                val variantPath = "$path($name)"
                when (variant.kind) {
                    StructureKind.CLASS, StructureKind.OBJECT -> {
                        objectOf(
                            variant,
                            variant.annotations.description(),
                            variantPath,
                            stack,
                            discriminator to name,
                        )
                    }

                    else -> {
                        throw IllegalArgumentException(refusal(variantPath, "a sealed subclass that is not an object"))
                    }
                }
            }
        val out = LinkedHashMap<String, JsonElement>()
        out.describe(description)
        out["anyOf"] = JsonArray(variants)
        return JsonObject(out)
    }

    private fun checkAnnotations(
        path: String,
        annotations: List<Annotation>,
        what: String,
        vararg allowed: KClass<out Annotation>,
    ) {
        val bounds = listOf(LbRange::class, LbLength::class, LbPattern::class, LbItems::class)
        val misplaced = annotations.map { it.annotationClass }.filter { it in bounds && it !in allowed }
        require(misplaced.isEmpty()) {
            refusal(path, "@${misplaced.first().simpleName} doesn't apply to $what")
        }
    }
}

/** A serial name that is a dotted Kotlin name, the default a class gets without `@SerialName`. */
private fun looksQualified(name: String): Boolean {
    val parts = name.split('.')
    return parts.size > 1 && parts.all { part -> part.isNotEmpty() && part.all { it.isLetterOrDigit() || it == '_' } }
}

private fun SerialDescriptor.elementNames(): List<String> = (0 until elementsCount).map(::getElementName)

/** kotlinx's JSON tree types: free-form JSON, which no fixed schema describes. */
private val JSON_TREE_TYPES =
    setOf("JsonElement", "JsonObject", "JsonArray", "JsonPrimitive", "JsonNull", "JsonLiteral", "JsonUnquotedLiteral")
        .map { "kotlinx.serialization.json.$it" }
        .toSet()

private fun List<Annotation>.descriptions(): List<Annotation> = filterIsInstance<LbDescription>()

private fun List<Annotation>.description(): String? = filterIsInstance<LbDescription>().firstOrNull()?.text

private inline fun <reified A : Annotation> List<Annotation>.find(): A? = filterIsInstance<A>().firstOrNull()

private fun MutableMap<String, JsonElement>.describe(description: String?) {
    if (description != null) this["description"] = str(description)
}

/** The largest value of each unsigned type; a `ULong`'s is above any `Long` (and any [LbRange]), so it has none. */
private val UNSIGNED_MAX: Map<String, Long?> =
    mapOf(
        "kotlin.UByte" to UByte.MAX_VALUE.toLong(),
        "kotlin.UShort" to UShort.MAX_VALUE.toLong(),
        "kotlin.UInt" to UInt.MAX_VALUE.toLong(),
        "kotlin.ULong" to null,
    )

private fun str(value: String) = JsonPrimitive(value)

private fun refusal(path: String, what: String) = "typedSchema: $path: $what"
