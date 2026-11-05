package com.suhel.llamabro

/**
 * Marks the engine seam that `llamabro-testing` is built on (D27). Apps don't opt in: test against
 * `FakeLlamaBro` instead. Anything marked can change in any release.
 */
@RequiresOptIn(
    message = "The engine seam is for llamabro-testing; use FakeLlamaBro in tests. It changes without notice.",
    level = RequiresOptIn.Level.ERROR,
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
public annotation class InternalLlamaBroApi
