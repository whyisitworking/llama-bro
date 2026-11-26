package com.suhel.llamabro.server

import com.suhel.llamabro.Endpoint
import org.junit.Assert.assertEquals
import org.junit.Test

/** The route table is the design's (docs/design/server.md, §2 Routes), one row per `Endpoint` it serves. */
class RouteTableTest {
    // audio comes with its own routes, the realtime socket isn't in v1, and System One isn't a llama-server route
    private val notForwarded = setOf(Endpoint.Transcriptions, Endpoint.SystemOne)

    // they change or save the shared model, as upstream gates them behind --props and --slot-save-path
    private val offByDefault = setOf(Endpoint.PropsSet, Endpoint.LoraAdaptersSet, Endpoint.SlotsAction)

    /** Every public constant of [Endpoint]; internal ones have mangled getters, which the `$` leaves out. */
    private val publicEndpoints: List<Endpoint> =
        Endpoint.Companion::class.java.methods
            .filter { it.returnType == Endpoint::class.java && it.parameterCount == 0 && '$' !in it.name }
            .map { it.invoke(Endpoint.Companion) as Endpoint }

    @Test
    fun everyEndpointHasItsDesignDefault() {
        val expected = (publicEndpoints - notForwarded).associateWith { it !in offByDefault }
        assertEquals(expected, routeTable.associate { it.endpoint to it.onByDefault })
    }

    @Test
    fun eachRouteIsItsEndpointsMethodAndPath() =
        routeTable.forEach { route ->
            // Ktor writes a path parameter {id_slot}, llamabro.h :id_slot
            val path = route.path.replace(Regex("\\{(\\w+)}"), ":$1")
            assertEquals(route.endpoint.toString(), "${route.method.value} $path")
        }

    @Test
    fun noRouteTwice() = assertEquals(routeTable.size, routeTable.map { it.method to it.path }.toSet().size)
}
