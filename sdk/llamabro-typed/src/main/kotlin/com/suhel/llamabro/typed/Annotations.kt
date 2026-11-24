@file:OptIn(ExperimentalSerializationApi::class)

package com.suhel.llamabro.typed

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialInfo

/**
 * Describes a property, or a class, to the model: the schema's `description`. A property's description wins over its
 * class's.
 */
@SerialInfo
@Target(AnnotationTarget.PROPERTY, AnnotationTarget.CLASS)
public annotation class LbDescription(val text: String)

/**
 * Bounds an integer property: the schema's `minimum` and `maximum`, which llama.cpp's grammar enforces. The range is
 * intersected with the field type's range (`Int`, `Byte`, the unsigned types…), which is written even without this
 * annotation ([D88](https://github.com/whyisitworking/llama-bro/blob/main/docs/decisions.md#d88)), and refused, with
 * the field's path, when nothing is left; a `Long` has only the bounds set here. Decimals have no bounds, since the
 * grammar ignores them ([F143](https://github.com/whyisitworking/llama-bro/blob/main/docs/research/findings.md#f143)).
 */
@SerialInfo
@Target(AnnotationTarget.PROPERTY)
public annotation class LbRange(val min: Long = Long.MIN_VALUE, val max: Long = Long.MAX_VALUE)

/**
 * Bounds a string property's length: the schema's `minLength` and `maxLength`. A bound left at its default is not
 * written.
 */
@SerialInfo
@Target(AnnotationTarget.PROPERTY)
public annotation class LbLength(val min: Int = 0, val max: Int = Int.MAX_VALUE)

/**
 * Holds a string property to a regular expression: the schema's `pattern`, in llama.cpp's grammar's subset
 * ([F158](https://github.com/whyisitworking/llama-bro/blob/main/docs/research/findings.md#f158)): an anchored pattern
 * (`^…$`) of literal characters, `.`, classes such as `[a-z]`, groups, `|` and repetition. llama.cpp drops any other
 * pattern (unanchored, `\d`, `\w`, `\p{…}`, lookarounds, POSIX classes) with only a log warning, so the field takes any
 * string; a malformed one (unbalanced parentheses) fails the request with `InvalidRequest`, and in a conversation with
 * typed tools every request in it, since the tools are sent with each one. [assertTypedSchema] can't check the pattern;
 * a test against a real model does.
 */
@SerialInfo
@Target(AnnotationTarget.PROPERTY)
public annotation class LbPattern(val regex: String)

/**
 * Bounds a list property's item count: the schema's `minItems` and `maxItems`. A bound left at its default is not
 * written.
 */
@SerialInfo
@Target(AnnotationTarget.PROPERTY)
public annotation class LbItems(val min: Int = 0, val max: Int = Int.MAX_VALUE)
