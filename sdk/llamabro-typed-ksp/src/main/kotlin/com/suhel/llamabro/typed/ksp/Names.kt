package com.suhel.llamabro.typed.ksp

import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSTypeAlias
import com.google.devtools.ksp.symbol.KSTypeArgument
import com.google.devtools.ksp.symbol.KSTypeReference
import com.google.devtools.ksp.symbol.Variance

// The annotations and types are read by their fully qualified names: llamabro-typed is an Android library, which a
// processor running in the compiler can't depend on.
internal const val TYPED = "com.suhel.llamabro.typed"
internal const val LB_TOOLBOX = "$TYPED.LbToolbox"
internal const val LB_TOOL = "$TYPED.LbTool"
internal const val LB_TOOL_PARAM = "$TYPED.LbToolParam"
internal const val LB_CONTEXT = "$TYPED.LbToolCallContext"
internal const val LB_DESCRIPTION = "$TYPED.LbDescription"
internal const val SERIALIZABLE = "kotlinx.serialization.Serializable"
internal const val SERIAL_NAME = "kotlinx.serialization.SerialName"
internal const val TRANSIENT = "kotlinx.serialization.Transient"

/** What `TypedTool` checks at runtime too; checked here with the prefix, so a bad name fails the build. */
internal val TOOL_NAME = Regex("^[a-zA-Z0-9_-]{1,64}$")

internal fun KSAnnotated.annotation(fqn: String): KSAnnotation? =
    annotations.firstOrNull {
        it.annotationType
            .resolve()
            .declaration.qualifiedName
            ?.asString() == fqn
    }

internal fun KSAnnotated.has(fqn: String): Boolean = annotation(fqn) != null

/** An argument as written, or the annotation's default for it. */
internal fun KSAnnotation.arg(name: String): Any? =
    (
        arguments.firstOrNull { it.name?.asString() == name }
            ?: defaultArguments.firstOrNull { it.name?.asString() == name }
    )?.value

internal fun KSDeclaration.fqn(): String = qualifiedName?.asString() ?: simpleName.asString()

/** The class a type names, through type aliases; null for a type parameter. */
internal fun KSType.classDeclaration(): KSClassDeclaration? =
    when (val d = declaration) {
        is KSClassDeclaration -> d
        is KSTypeAlias -> d.type.resolve().classDeclaration()
        else -> null
    }

/** `getForecast` → `get_forecast`, `readHTTPStatus` → `read_http_status`. */
internal fun snakeCase(name: String): String =
    name
        .replace(Regex("([A-Z]+)([A-Z][a-z])"), "$1_$2")
        .replace(Regex("([a-z0-9])([A-Z])"), "$1_$2")
        .lowercase()

/** A serial name that is a dotted Kotlin name; the runtime schema reads such a name as a missing `@SerialName`. */
internal fun looksQualified(name: String): Boolean {
    val parts = name.split('.')
    return parts.size > 1 && parts.all { part -> part.isNotEmpty() && part.all { it.isLetterOrDigit() || it == '_' } }
}

/**
 * The type as Kotlin source, every class fully qualified, so generated code needs no imports. With
 * [serialAnnotations], its type arguments keep their type-use `@Serializable(with = …)` ([renderSerialized]).
 */
internal fun KSType.render(serialAnnotations: Boolean = false): String {
    val name = declaration.qualifiedName?.asString()?.let(::qualified) ?: id(declaration.simpleName.asString())
    val args =
        if (arguments.isEmpty()) "" else arguments.joinToString(", ", "<", ">") { it.render(serialAnnotations) }
    return name + args + if (isMarkedNullable) "?" else ""
}

/**
 * The type as [render] writes it, keeping each type-use `@Serializable(with = …)`, at any depth, so a generated
 * property serializes as the app's parameter would.
 */
internal fun KSTypeReference.renderSerialized(): String {
    val resolved = resolve()
    return serializerAnnotation(annotations + resolved.annotations) + resolved.render(serialAnnotations = true)
}

private fun KSTypeArgument.render(serialAnnotations: Boolean): String {
    val reference = type ?: return "*"
    val resolved = reference.resolve()
    // a type argument's annotations may sit on its reference rather than on the resolved type
    val serializer =
        if (serialAnnotations) serializerAnnotation(reference.annotations + annotations + resolved.annotations) else ""
    val rendered = serializer + resolved.render(serialAnnotations)
    return when (variance) {
        Variance.STAR -> "*"
        Variance.COVARIANT -> "out $rendered"
        Variance.CONTRAVARIANT -> "in $rendered"
        Variance.INVARIANT -> rendered
    }
}

/** A type-use `@Serializable(with = X::class)` among [annotations] as source with a trailing space, or "". */
internal fun serializerAnnotation(annotations: Sequence<KSAnnotation>): String {
    val serializable = annotations.firstOrNull { it.fqnOf() == SERIALIZABLE } ?: return ""
    val with = serializable.arg("with") as? KSType ?: return ""
    val serializer = with.declaration.qualifiedName?.asString() ?: return ""
    // `with`'s default is KSerializer itself: the annotation then picks nothing
    if (serializer == "kotlinx.serialization.KSerializer") return ""
    return "@${qualified(SERIALIZABLE)}(with = ${qualified(serializer)}::class) "
}

internal fun KSAnnotation.fqnOf(): String? =
    annotationType
        .resolve()
        .declaration.qualifiedName
        ?.asString()

/** A name as Kotlin source: backticked when it is a keyword or not a plain identifier. */
internal fun id(name: String): String = if (name in KEYWORDS || !PLAIN.matches(name)) "`$name`" else name

internal fun qualified(name: String): String = name.split('.').joinToString(".", transform = ::id)

/** A Kotlin string literal of [text]. */
internal fun literal(text: String): String =
    buildString {
        append('"')
        text.forEach { c ->
            when (c) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '$' -> append("\\$")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(c)
            }
        }
        append('"')
    }

private val PLAIN = Regex("[A-Za-z_][A-Za-z0-9_]*")

private val KEYWORDS =
    setOf(
        "as",
        "break",
        "class",
        "continue",
        "do",
        "else",
        "false",
        "for",
        "fun",
        "if",
        "in",
        "interface",
        "is",
        "null",
        "object",
        "package",
        "return",
        "super",
        "this",
        "throw",
        "true",
        "try",
        "typealias",
        "typeof",
        "val",
        "var",
        "when",
        "while",
    )
