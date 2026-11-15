package com.suhel.llamabro

/**
 * Marks the seams the test fakes are built on: the engine seam under `llamabro-testing`'s `FakeLlamaBro` (D27) and the
 * hub's transport seam under `llamabro-hub-testing`'s `FakeLlamaBroHub` (D75). Apps don't opt in: test against the
 * fakes instead. Anything marked can change in any release.
 */
@RequiresOptIn(
    message =
        "These seams are for llamabro-testing and llamabro-hub-testing; use FakeLlamaBro or FakeLlamaBroHub in " +
            "tests. They change without notice.",
    level = RequiresOptIn.Level.ERROR,
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
public annotation class InternalLlamaBroApi
