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
 * Bounds an integer property: the schema's `minimum` and `maximum`, which llama.cpp's grammar enforces. A bound left at
 * its default is not written. The range is intersected with the field type's range (`Int`, `Byte`, the unsigned
 * types…) and refused, with the field's path, when nothing is left. Decimals have no bounds, since the grammar ignores
 * them (F143).
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
 * Holds a string property to a regular expression: the schema's `pattern`, in llama.cpp's grammar's subset.
 * llama.cpp rejects the rest of regex when it builds the grammar, so a bad pattern fails the first request, and in a
 * conversation with typed tools every request in it, since the tools are sent with each one. A test against a real
 * model catches it; [assertTypedSchema] can't check the pattern.
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
