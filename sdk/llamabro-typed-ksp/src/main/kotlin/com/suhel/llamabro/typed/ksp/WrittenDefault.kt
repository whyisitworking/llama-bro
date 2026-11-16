package com.suhel.llamabro.typed.ksp

import com.google.devtools.ksp.symbol.FileLocation
import com.google.devtools.ksp.symbol.KSValueParameter
import java.io.File
import java.io.IOException

/**
 * Why a separate parameter's default is refused, or null when it is a nullable `= null` (D61). A default that
 * couldn't be read ([written] null) is refused too: the processor never assumes `= null` it hasn't seen.
 */
internal fun defaultProblem(name: String, nullable: Boolean, written: String?): String? =
    when {
        written == null -> {
            "can't read the default of parameter $name: write = null in its source, or use one @Serializable " +
                "arguments class with the default on its property"
        }

        nullable && written == "null" -> {
            null
        }

        else -> {
            "parameter $name has a default other than = null: a separate parameter is optional only as a " +
                "nullable = null; for another default, use one @Serializable arguments class with the default " +
                "on its property"
        }
    }

/**
 * A parameter's default as written (`null`, `3`), comments left out, read from its source file, since KSP has no API
 * for a default's expression; null when the source can't be read (a supertype from a library) or the parameter or its
 * default isn't found.
 */
internal fun writtenDefault(param: KSValueParameter): String? {
    val location = param.location as? FileLocation ?: return null
    val text =
        try {
            File(location.filePath).readText()
        } catch (_: IOException) {
            return null
        }
    var line = 0
    repeat(location.lineNumber - 1) { line = text.indexOf('\n', line) + 1 }
    val name = param.name?.asString() ?: return null
    return SourceScan(text).defaultOf(name, line)
}

/** Just enough of Kotlin's lexis to walk a parameter list: strings, comments and brackets are skipped whole. */
private class SourceScan(private val text: String) {
    fun defaultOf(name: String, from: Int): String? {
        val at = declaration(name, from) ?: return null
        val afterType = skipTo(text.indexOf(':', at) + 1, angles = true) { it == '=' || it == ',' || it == ')' }
        if (afterType >= text.length || text[afterType] != '=') return null
        val end = skipTo(afterType + 1, angles = false) { it == ',' || it == ')' }
        return withoutComments(text.substring(afterType + 1, minOf(end, text.length))).trim()
    }

    /** [code] with its comments left out; strings are kept as they are. */
    private fun withoutComments(code: String): String {
        val scan = SourceScan(code)
        return buildString {
            var i = 0
            while (i < code.length) {
                val next = scan.skipLiteral(i)
                val comment = code.startsWith("//", i) || code.startsWith("/*", i)
                if (next == i) {
                    append(code[i])
                } else if (!comment) {
                    append(code, i, next)
                }
                i = maxOf(next, i + 1)
            }
        }
    }

    /** Where `name:` starts, outside strings and comments, at or after [from]. */
    private fun declaration(name: String, from: Int): Int? {
        var i = from
        while (i < text.length) {
            val skipped = skipLiteral(i)
            if (skipped != i) {
                i = skipped
                continue
            }
            if (isDeclaration(name, i)) return i
            i++
        }
        return null
    }

    /** The index of the first character at bracket depth 0 that [stop] accepts, from [from]. */
    private fun skipTo(from: Int, angles: Boolean, stop: (Char) -> Boolean): Int {
        var depth = 0
        var i = from
        while (i < text.length) {
            val skipped = skipLiteral(i)
            if (skipped != i) {
                i = skipped
                continue
            }
            val c = text[i]
            when {
                depth == 0 && stop(c) -> return i
                c in "([{" || (angles && c == '<') -> depth++
                c in ")]}" || (angles && c == '>' && text.getOrNull(i - 1) != '-') -> depth--
            }
            i++
        }
        return i
    }

    /** Past the string or comment starting at [i], or [i] when none does. */
    fun skipLiteral(i: Int): Int =
        when {
            text.startsWith(RAW, i) -> past(RAW, i + RAW.length)
            text.startsWith("//", i) -> lineEnd(i)
            text.startsWith("/*", i) -> past("*/", i + 2)
            text[i] == '"' || text[i] == '\'' -> quoted(i)
            else -> i
        }

    /** Where the line holding [i] ends: its `\n`, or the end of the text. */
    private fun lineEnd(i: Int): Int {
        val at = text.indexOf('\n', i)
        return if (at < 0) text.length else at
    }

    /** Just past the next [end] from [from], or the end of the text. */
    private fun past(end: String, from: Int): Int {
        val at = text.indexOf(end, from)
        return if (at < 0) text.length else at + end.length
    }

    private fun quoted(i: Int): Int {
        var j = i + 1
        while (j < text.length && text[j] != text[i]) j += if (text[j] == '\\') 2 else 1
        return j + 1
    }

    /** [name] as a whole word at [i], followed by `:`. */
    private fun isDeclaration(name: String, i: Int): Boolean =
        text.startsWith(name, i) && boundary(i - 1) && colonAfter(i + name.length)

    /** A word ends at [from] and a `:` follows it. */
    private fun colonAfter(from: Int): Boolean {
        if (!boundary(from)) return false
        var i = from
        while (i < text.length && text[i].isWhitespace()) i++
        return text.getOrNull(i) == ':'
    }

    private fun boundary(i: Int): Boolean = i !in text.indices || !(text[i].isLetterOrDigit() || text[i] == '_')

    private companion object {
        const val RAW = "\"\"\""
    }
}
