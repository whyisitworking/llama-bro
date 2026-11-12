package com.suhel.llamabro.hub.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

internal class RepoIdTest {
    @Test fun repoIdsFollowUpstreamRules() {
        assertTrue(isValidRepoId("a/b"))
        listOf("a//b", "/a", "a/", "a/b/c", "a.-b/c", "a b/c", "a/" + "b".repeat(255))
            .forEach { assertFalse(it, isValidRepoId(it)) }
    }

    @Test fun pathEscapesRejected() {
        val base = Files.createTempDirectory("hub-path-")
        try {
            listOf("../x", "/etc/x", "a/../../x").forEach {
                assertFalse(it, isValidSubpath(base, it))
            }
            assertTrue(isValidSubpath(base, "a/../b"))
        } finally {
            Files.delete(base)
        }
    }

    @Test fun tokenCommitAndOidRules() {
        assertTrue(isValidHfToken("hf_" + "a".repeat(34)))
        assertFalse(isValidHfToken("abc"))
        assertFalse(isValidHfToken("hf_" + "-".repeat(34)))
        assertTrue(isValidCommit("a".repeat(40)))
        assertFalse(isValidCommit("g".repeat(40)))
        assertTrue(isValidOid("0".repeat(40)))
        assertTrue(isValidOid("F".repeat(64)))
        assertFalse(isValidOid("0".repeat(63)))
    }

    @Test fun folderRoundTrip() {
        assertEquals("models--org--repo", repoFolder("org/repo"))
        assertEquals("org/repo", repoOfFolder("models--org--repo"))
        assertEquals("", repoOfFolder("other--org--repo"))
    }
}
