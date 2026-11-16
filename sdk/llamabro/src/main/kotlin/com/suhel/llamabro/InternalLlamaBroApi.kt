package com.suhel.llamabro

/**
 * Marks the seams the test fakes are built on: the engine seam under `llamabro-testing`'s `FakeLlamaBro` (D27), the
 * hub's transport seam under `llamabro-hub-testing`'s `FakeLlamaBroHub` (D75), the host engine under other modules'
 * host end-to-end tests, and the raw tool registration under `llamabro-typed`'s typed tools (D61). Apps don't opt in:
 * test against the fakes instead. Anything marked can change in any release.
 */
@RequiresOptIn(
    message =
        "These seams are for the SDK's own sibling modules (llamabro-testing, llamabro-hub-testing, " +
            "llamabro-typed); in tests use FakeLlamaBro or FakeLlamaBroHub. They change without notice.",
    level = RequiresOptIn.Level.ERROR,
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
public annotation class InternalLlamaBroApi
