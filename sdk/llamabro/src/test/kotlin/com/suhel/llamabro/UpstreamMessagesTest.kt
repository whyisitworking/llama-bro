package com.suhel.llamabro

import com.suhel.llamabro.internal.ErrorMapping
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Upstream gives no code for a few errors the SDK types (an undecodable picture, a missing projector, an input past the
 * batch), so the SDK matches their words (D115). Each phrase must still be in the pinned llama.cpp's source: an upgrade
 * that rewords one fails here instead of turning the error into a plain ServerError.
 */
class UpstreamMessagesTest {
    @Test
    fun everyMatchedPhraseIsInThePinnedSource() {
        val llama = File("../native/third_party/llama.cpp")
        assumeTrue("the llama.cpp submodule is checked out", llama.resolve("tools/server").isDirectory)
        for ((path, phrases) in ErrorMapping.UPSTREAM_WORDS) {
            val source = llama.resolve(path).readText()
            for (phrase in phrases) assertTrue("\"$phrase\" not in $path", phrase in source)
        }
    }
}
