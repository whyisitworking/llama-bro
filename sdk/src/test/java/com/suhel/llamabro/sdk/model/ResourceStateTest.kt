package com.suhel.llamabro.sdk.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ResourceStateTest {

    // ── getOrNull ───────────────────────────────────────────────────────────

    @Test
    fun `getOrNull returns value for Success`() {
        val event: ResourceState<String> = ResourceState.Success("hello")
        assertEquals("hello", event.getOrNull())
    }

    @Test
    fun `getOrNull returns null for Loading`() {
        val event: ResourceState<String> = ResourceState.Loading(0.5f)
        assertNull(event.getOrNull())
    }

    @Test
    fun `getOrNull returns null for Loading without progress`() {
        val event: ResourceState<String> = ResourceState.Loading()
        assertNull(event.getOrNull())
    }

    @Test
    fun `getOrNull returns null for Failure`() {
        val event: ResourceState<String> = ResourceState.Failure(LlamaError.ContextOverflow())
        assertNull(event.getOrNull())
    }

    // ── map ─────────────────────────────────────────────────────────────────

    @Test
    fun `map transforms Success value`() {
        val event: ResourceState<Int> = ResourceState.Success(42)
        val mapped = event.map { it.toString() }
        assertEquals(ResourceState.Success("42"), mapped)
    }

    @Test
    fun `map preserves Loading with progress`() {
        val event: ResourceState<Int> = ResourceState.Loading(0.7f)
        val mapped = event.map { it * 2 }
        assertEquals(ResourceState.Loading(0.7f), mapped)
    }

    @Test
    fun `map preserves Loading without progress`() {
        val event: ResourceState<Int> = ResourceState.Loading()
        val mapped = event.map { it * 2 }
        assertEquals(ResourceState.Loading(null), mapped)
    }

    @Test
    fun `map preserves Failure`() {
        val error = LlamaError.ContextOverflow()
        val event: ResourceState<Int> = ResourceState.Failure(error)
        val mapped = event.map { it * 2 }
        assertEquals(ResourceState.Failure(error), mapped)
    }
}
