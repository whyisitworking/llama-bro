package com.suhel.llamabro.sdk.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LoadEventTest {

    // ── getOrNull ───────────────────────────────────────────────────────────

    @Test
    fun `getOrNull returns resource for Ready`() {
        val event: LoadEvent<String> = LoadEvent.Ready("hello")
        assertEquals("hello", event.getOrNull())
    }

    @Test
    fun `getOrNull returns null for Loading`() {
        val event: LoadEvent<String> = LoadEvent.Loading(0.5f)
        assertNull(event.getOrNull())
    }

    @Test
    fun `getOrNull returns null for Loading without progress`() {
        val event: LoadEvent<String> = LoadEvent.Loading()
        assertNull(event.getOrNull())
    }

    @Test
    fun `getOrNull returns null for Error`() {
        val event: LoadEvent<String> = LoadEvent.Error(LlamaError.ContextOverflow())
        assertNull(event.getOrNull())
    }

    // ── map ─────────────────────────────────────────────────────────────────

    @Test
    fun `map transforms Ready value`() {
        val event: LoadEvent<Int> = LoadEvent.Ready(42)
        val mapped = event.map { it.toString() }
        assertEquals(LoadEvent.Ready("42"), mapped)
    }

    @Test
    fun `map preserves Loading with progress`() {
        val event: LoadEvent<Int> = LoadEvent.Loading(0.7f)
        val mapped = event.map { it * 2 }
        assertEquals(LoadEvent.Loading(0.7f), mapped)
    }

    @Test
    fun `map preserves Loading without progress`() {
        val event: LoadEvent<Int> = LoadEvent.Loading()
        val mapped = event.map { it * 2 }
        assertEquals(LoadEvent.Loading(null), mapped)
    }

    @Test
    fun `map preserves Error`() {
        val error = LlamaError.ContextOverflow()
        val event: LoadEvent<Int> = LoadEvent.Error(error)
        val mapped = event.map { it * 2 }
        assertEquals(LoadEvent.Error(error), mapped)
    }
}
