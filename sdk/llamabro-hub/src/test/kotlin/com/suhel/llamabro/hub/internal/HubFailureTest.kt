package com.suhel.llamabro.hub.internal

import com.suhel.llamabro.hub.HubException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every hub request reads an HTTP error the same way: a header fetch's 429 is a rate limit, as a search's is. */
internal class HubFailureTest {
    private fun failure(
        status: Int,
        headers: Map<String, List<String>> = emptyMap(),
        alsoRetryable: Set<Int> = emptySet(),
    ) = hubFailure(Response(status, headers, "https://hf.co/x", null) {}, "denied", "missing", "other", alsoRetryable)

    @Test fun tokenProblemsAreUnauthorized() {
        for (status in listOf(401, 403)) {
            val e = failure(status) as HubException.Unauthorized
            assertEquals(status, e.httpStatus)
            assertEquals("denied", e.message)
        }
    }

    @Test fun missingIsNotFound() {
        assertTrue(failure(404) is HubException.NotFound)
    }

    @Test fun tooManyRequestsCarriesRetryAfter() {
        val e = failure(429, mapOf("retry-after" to listOf("7"))) as HubException.RateLimited
        assertEquals(7L, e.retryAfterSeconds)
        assertTrue(e.retryable)
    }

    @Test fun serverErrorsAreRetryableClientErrorsAreNot() {
        assertTrue((failure(503) as HubException.Network).retryable)
        assertFalse((failure(416) as HubException.Network).retryable)
        assertTrue((failure(416, alsoRetryable = setOf(416)) as HubException.Network).retryable)
    }
}
