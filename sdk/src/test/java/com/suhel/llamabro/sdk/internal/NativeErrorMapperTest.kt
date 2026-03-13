package com.suhel.llamabro.sdk.internal

import com.suhel.llamabro.sdk.model.LlamaError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeErrorMapperTest {

    @Test
    fun `code 1 maps to ModelNotFound`() {
        val error = mapNativeError(RuntimeException("1:/data/model.gguf"))
        assertTrue(error is LlamaError.ModelNotFound)
        assertEquals("/data/model.gguf", (error as LlamaError.ModelNotFound).path)
    }

    @Test
    fun `code 2 maps to ModelLoadFailed`() {
        val error = mapNativeError(RuntimeException("2:/data/model.gguf"))
        assertTrue(error is LlamaError.ModelLoadFailed)
        assertEquals("/data/model.gguf", (error as LlamaError.ModelLoadFailed).path)
    }

    @Test
    fun `code 3 maps to BackendLoadFailed`() {
        val error = mapNativeError(RuntimeException("3:ggml-cpu"))
        assertTrue(error is LlamaError.BackendLoadFailed)
        assertEquals("ggml-cpu", (error as LlamaError.BackendLoadFailed).backendName)
    }

    @Test
    fun `code 10 maps to ContextInitFailed`() {
        val error = mapNativeError(RuntimeException("10:"))
        assertTrue(error is LlamaError.ContextInitFailed)
    }

    @Test
    fun `code 11 maps to ContextOverflow`() {
        val error = mapNativeError(RuntimeException("11:"))
        assertTrue(error is LlamaError.ContextOverflow)
    }

    @Test
    fun `code 12 maps to DecodeFailed with decode error code`() {
        val error = mapNativeError(RuntimeException("12:42"))
        assertTrue(error is LlamaError.DecodeFailed)
        assertEquals(42, (error as LlamaError.DecodeFailed).code)
    }

    @Test
    fun `code 12 with non-numeric detail falls back to -1`() {
        val error = mapNativeError(RuntimeException("12:not_a_number"))
        assertTrue(error is LlamaError.DecodeFailed)
        assertEquals(-1, (error as LlamaError.DecodeFailed).code)
    }

    @Test
    fun `unknown code maps to NativeException`() {
        val error = mapNativeError(RuntimeException("999:something went wrong"))
        assertTrue(error is LlamaError.NativeException)
        assertEquals("something went wrong", (error as LlamaError.NativeException).nativeMessage)
    }

    @Test
    fun `null message maps to NativeException with Unknown`() {
        val error = mapNativeError(RuntimeException(null as String?))
        assertTrue(error is LlamaError.NativeException)
        assertEquals("Native error: Unknown native error", error.message)
    }

    @Test
    fun `message without colon maps to NativeException`() {
        val error = mapNativeError(RuntimeException("no colon here"))
        assertTrue(error is LlamaError.NativeException)
        assertEquals("no colon here", (error as LlamaError.NativeException).nativeMessage)
    }

    @Test
    fun `empty message maps to NativeException`() {
        val error = mapNativeError(RuntimeException(""))
        assertTrue(error is LlamaError.NativeException)
    }
}
