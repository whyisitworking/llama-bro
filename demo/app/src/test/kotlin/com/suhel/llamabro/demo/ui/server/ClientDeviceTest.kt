package com.suhel.llamabro.demo.ui.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A browser's user agent as the pairing question names it: the system and the browser, nothing else. */
class ClientDeviceTest {
    @Test fun browsersAndSystemsAreNamed() {
        val agents =
            mapOf(
                "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) " +
                    "Chrome/131.0.0.0 Safari/537.36" to "Mac · Chrome",
                "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) " +
                    "Version/18.1 Safari/605.1.15" to "Mac · Safari",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                    "Chrome/131.0.0.0 Safari/537.36 Edg/131.0.0.0" to "Windows · Edge",
                "Mozilla/5.0 (X11; Linux x86_64; rv:133.0) Gecko/20100101 Firefox/133.0" to "Linux · Firefox",
                "Mozilla/5.0 (X11; CrOS x86_64 14541.0.0) AppleWebKit/537.36 (KHTML, like Gecko) " +
                    "Chrome/131.0.0.0 Safari/537.36" to "ChromeOS · Chrome",
                "Mozilla/5.0 (iPhone; CPU iPhone OS 18_1 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) " +
                    "Version/18.1 Mobile/15E148 Safari/604.1" to "iPhone · Safari",
                "Mozilla/5.0 (Linux; Android 15; Pixel 9) AppleWebKit/537.36 (KHTML, like Gecko) " +
                    "Chrome/131.0.0.0 Mobile Safari/537.36" to "Android · Chrome",
                "curl/8.7.1" to "curl",
            )
        agents.forEach { (agent, device) -> assertEquals(agent, device, ServerFormat.device(agent)) }
    }

    @Test fun anUnknownAgentSaysNothing() {
        assertNull(ServerFormat.device(null))
        assertNull(ServerFormat.device(""))
        assertNull(ServerFormat.device("SomethingElse/1.0"))
    }
}
