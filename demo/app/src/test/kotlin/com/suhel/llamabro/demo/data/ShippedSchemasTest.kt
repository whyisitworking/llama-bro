package com.suhel.llamabro.demo.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * A database version that has been installed must keep its schema: Room checks the identity hash it stored on the
 * phone and refuses to open a database whose version's hash changed. A change goes into a new version with its own
 * migration (D127 moved the search pages to version 7 for this reason). Unit tests run in the module's directory.
 */
class ShippedSchemasTest {
    private val schemas = File("schemas/com.suhel.llamabro.demo.data.db.ChatDatabase")

    private fun identityHash(version: Int): String? =
        Regex("\"identityHash\": \"([0-9a-f]+)\"")
            .find(File(schemas, "$version.json").readText())
            ?.groupValues
            ?.get(1)

    @Test fun installedVersionsKeepTheirIdentityHash() {
        val shipped =
            mapOf(
                1 to "b9f8036b9e06925e6fbce8e80e81b099",
                2 to "c317b69c0fdfe91743f4da8860191301",
                3 to "71085e353bf76e6951cefb4556898a2d",
                4 to "251f42e141f0a8ee2fa0145c3e35a2bd",
                5 to "5eb2ca5e9a370dca4777f5d9276ba4eb",
                6 to "be2a7bf480eb5a6080de34cc1d39a545",
                // in 2.0.0-alpha
                7 to "7d6619c3152a90dce9bcf61f3711fdb9",
            )
        shipped.forEach { (version, hash) -> assertEquals("version $version's schema", hash, identityHash(version)) }
    }
}
