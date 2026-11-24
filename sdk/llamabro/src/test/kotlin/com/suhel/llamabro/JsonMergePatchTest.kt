package com.suhel.llamabro

import com.suhel.llamabro.internal.JsonMergePatch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

/** A typed call's `extra`, merged onto the body it built (RFC 7396), with the fields the SDK owns kept its own. */
class JsonMergePatchTest {
    private val base = JsonObject(mapOf("model" to JsonPrimitive("m"), "stream" to JsonPrimitive(true)))

    @Test
    fun noExtraIsTheBaseItself() {
        assertSame(base, JsonMergePatch.applyExtra(base, null, setOf("stream")))
    }

    @Test
    fun extraIsMergedAfterTheBase() {
        val merged = JsonMergePatch.applyExtra(base, """{"top_k":4,"model":null}""", setOf("stream"))
        assertEquals("""{"stream":true,"top_k":4}""", merged.toString())
    }

    @Test
    fun anExtraThatIsNotAnObjectIsRefused() {
        assertThrows(IllegalArgumentException::class.java) { JsonMergePatch.applyExtra(base, "[1]", setOf("stream")) }
    }

    @Test
    fun aNestedObjectMergesKeepingTheBaseOrder() {
        val nested =
            JsonObject(
                mapOf(
                    "model" to JsonPrimitive("m"),
                    "opts" to JsonObject(mapOf("b" to JsonPrimitive(1), "a" to JsonPrimitive(2))),
                ),
            )
        val merged = JsonMergePatch.applyExtra(nested, """{"opts":{"c":3,"b":9}}""", emptySet())
        assertEquals("""{"model":"m","opts":{"b":9,"a":2,"c":3}}""", merged.toString())
    }

    @Test
    fun anOwnedKeyIsRefused() {
        assertThrows(IllegalArgumentException::class.java) {
            JsonMergePatch.applyExtra(base, """{"stream":false}""", setOf("stream"))
        }
    }
}
