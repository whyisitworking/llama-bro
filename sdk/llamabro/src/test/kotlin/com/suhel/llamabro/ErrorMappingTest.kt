package com.suhel.llamabro

import com.suhel.llamabro.internal.ErrorMapping
import com.suhel.llamabro.spi.NativeError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Each llama-server error type and each bridge error to its [LlamaBroException] (F41). */
class ErrorMappingTest {
    private fun body(type: String, message: String = "m", extra: String = "") =
        """{"error":{"code":400,"message":"$message","type":"$type"$extra}}"""

    @Test
    fun exceedContextKeepsCountsAndMessage() {
        val e =
            ErrorMapping.fromBody(
                400,
                body("exceed_context_size_error", "too long", ""","n_prompt_tokens":9000,"n_ctx":8192"""),
            )
        e as LlamaBroException.ContextExceeded
        assertEquals(9000, e.promptTokens)
        assertEquals(8192, e.contextSize)
        assertEquals("too long", e.message)
    }

    @Test
    fun exceedContextWithoutCountsIsNull() {
        val e = ErrorMapping.fromBody(400, body("exceed_context_size_error")) as LlamaBroException.ContextExceeded
        assertNull(e.promptTokens)
        assertNull(e.contextSize)
    }

    @Test
    fun requestErrorsAreInvalidRequest() {
        for (type in listOf("invalid_request_error", "not_found_error", "authentication_error", "permission_error")) {
            val e = ErrorMapping.fromBody(400, body(type, "bad"))
            assertTrue("$type -> $e", e is LlamaBroException.InvalidRequest && e.message == "bad")
        }
    }

    @Test
    fun notSupported() {
        assertTrue(ErrorMapping.fromBody(501, body("not_supported_error")) is LlamaBroException.NotSupported)
    }

    @Test
    fun unavailable() {
        assertTrue(ErrorMapping.fromBody(503, body("unavailable_error")) is LlamaBroException.Unavailable)
    }

    @Test
    fun otherTypesAreServerErrorWithStatus() {
        val e = ErrorMapping.fromBody(500, """{"error":{"code":500,"message":"boom","type":"server_error"}}""")
        e as LlamaBroException.ServerError
        assertEquals(500, e.httpStatus)
        assertEquals("boom", e.message)
    }

    @Test
    fun nonJsonBodyIsServerError() {
        val e = ErrorMapping.fromBody(502, "gateway") as LlamaBroException.ServerError
        assertEquals(502, e.httpStatus)
        assertEquals("gateway", e.message)
    }

    @Test
    fun loadFileNotFoundCode() {
        val native =
            NativeError(4, """{"error":"cannot open /m.gguf: No such file or directory","code":"file_not_found"}""")
        val e = ErrorMapping.loadFailed(native)
        assertEquals(LlamaBroException.ModelLoadFailed.Reason.FileNotFound, e.reason)
        assertEquals("cannot open /m.gguf: No such file or directory", e.message)
    }

    @Test
    fun loadWithoutCodeIsOther() {
        val e = ErrorMapping.loadFailed(NativeError(4, """{"error":"failed to load model: /m.gguf"}"""))
        assertEquals(LlamaBroException.ModelLoadFailed.Reason.Other, e.reason)
    }

    @Test
    fun causeIsKept() {
        val native = NativeError(4, """{"error":"x"}""")
        assertSame(native, ErrorMapping.loadFailed(native).cause)
    }

    @Test
    fun undecodableMediaIsInvalidRequest() {
        val e = ErrorMapping.fromBody(500, body("server_error", "Failed to load image or audio file"))
        assertTrue("$e", e is LlamaBroException.InvalidRequest)
        assertTrue(e.message!!, e.message!!.contains("JPEG, PNG") && e.message!!.contains("not HEIC or WebP"))
        assertTrue("${e.cause}", e.cause is LlamaBroException.ServerError)
        assertTrue(ErrorMapping.isMediaError(e))
        // the bridge's own refusal of a file or a clip is a media error too; a plain bad request isn't
        assertTrue(ErrorMapping.isMediaError(ErrorMapping.fromBody(400, body("invalid_request_error", "attachment x"))))
        assertTrue(!ErrorMapping.isMediaError(ErrorMapping.fromBody(400, body("invalid_request_error", "bad"))))
    }
}
