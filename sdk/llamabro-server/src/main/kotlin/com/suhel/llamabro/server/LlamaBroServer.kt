package com.suhel.llamabro.server

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.HttpRequestLifecycle
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

private const val STOP_TIMEOUT_MILLIS = 2_000L
private const val EVENT_BUFFER = 64
private val SERVICE_TYPES = listOf("_http._tcp", "_llamabro._tcp")

/**
 * llama-server's HTTP API in front of the models [models] serves, in this process: `GET /health`, `GET /v1/models`
 * and the model routes, behind the key and CORS settings of [config]
 * ([design](https://github.com/whyisitworking/llama-bro/blob/main/docs/design/server.md)).
 */
public class LlamaBroServer(private val config: ServerConfig, private val models: ServedModels) {
    private val mutableState = MutableStateFlow<ServerState>(ServerState.Stopped)
    private val mutableEvents =
        MutableSharedFlow<ServerEvent>(
            extraBufferCapacity = EVENT_BUFFER,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
    private val lifecycle = Mutex()
    private var registrations: List<Registration> = emptyList()
    private var engine: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null
    private val forwarding = Forwarding(models) { mutableEvents.tryEmit(it) }
    private val audioRoutes = AudioRoutes(models, { mutableEvents.tryEmit(it) }, forwarding::nextId)

    /** Where the server is; [ServerState.Serving] carries the bound port. */
    public val state: StateFlow<ServerState> = mutableState.asStateFlow()

    /** What the server did, for a request log. A slow collector misses the oldest events, never blocks a request. */
    public val events: SharedFlow<ServerEvent> = mutableEvents.asSharedFlow()

    /**
     * Binds and starts answering. A server already serving stays as it is: a second call does nothing. After [stop], or
     * a [ServerState.Failed], it can start again.
     *
     * [state] becomes [ServerState.Serving], or
     * [ServerState.Failed] if the bind fails.
     */
    public suspend fun start() {
        lifecycle.withLock {
            if (engine != null) return
            mutableState.value = ServerState.Starting
            val server = embeddedServer(CIO, host = config.host, port = config.port, module = { serve(config, models) })
            try {
                server.startSuspend(wait = false)
                val port =
                    server.engine
                        .resolvedConnectors()
                        .first()
                        .port
                engine = server
                mutableState.value = ServerState.Serving(port)
                announce(port)
            } catch (e: CancellationException) {
                runCatching { server.stopSuspend(0, 0) }
                mutableState.value = ServerState.Stopped
                throw e
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                runCatching { server.stopSuspend(0, 0) }
                withdraw()
                mutableState.value = ServerState.Failed(e)
            }
        }
    }

    /** Cancels the requests in flight and unbinds. A server that isn't running stays stopped. */
    public suspend fun stop() {
        lifecycle.withLock {
            withdraw()
            engine?.stopSuspend(0, STOP_TIMEOUT_MILLIS)
            engine = null
            mutableState.value = ServerState.Stopped
        }
    }

    // a network that refuses the registration leaves the server serving, unannounced: the module logs nothing
    private fun announce(port: Int) {
        val advertise = config.advertise ?: return
        val txt = mapOf("path" to "/v1", "auth" to if (config.apiKey == null) "none" else "key")
        val registry = runCatching { advertise.registry() }.getOrNull() ?: return
        // each one on its own, so one the network refuses does not leave the other registered and forgotten
        registrations =
            SERVICE_TYPES.mapNotNull { type ->
                runCatching { registry.register(advertise.name, type, port, txt) }.getOrNull()
            }
    }

    private fun withdraw() {
        registrations.forEach { runCatching { it.unregister() } }
        registrations = emptyList()
    }

    private fun Application.serve(config: ServerConfig, models: ServedModels) {
        // CORS first, so a preflight is answered before the key check could refuse it
        if (config.cors == Cors.Any) {
            install(CORS) {
                anyHost()
                allowNonSimpleContentTypes = true
                HttpMethod.DefaultMethods.forEach { allowMethod(it) }
                allowHeader(HttpHeaders.Authorization)
                allowHeader("x-api-key")
                allowHeader("anthropic-version")
                allowHeader(HttpHeaders.ContentType)
            }
        }
        // a client that hangs up cancels its call, so the model stops even while it has nothing to write (prefill)
        install(HttpRequestLifecycle) { cancelCallOnClose = true }
        install(ApiKeyAuth) {
            apiKey = config.apiKey
            onRejected = { client, path, status, reason ->
                mutableEvents.tryEmit(ServerEvent.Rejected(client, path, status, reason))
            }
        }
        routing {
            // as upstream answers a load balancer's probe
            get("/health") { call.respondText("""{"status":"ok"}""", ContentType.Application.Json) }
            get("/v1/models") {
                val listed = models.list()
                val body =
                    buildJsonObject {
                        put("object", "list")
                        putJsonArray("data") {
                            listed.forEach { model ->
                                add(
                                    buildJsonObject {
                                        put("id", model.id)
                                        put("object", "model")
                                        put("created", model.created)
                                        put("owned_by", "llamabro")
                                    },
                                )
                            }
                        }
                    }
                call.respondText(body.toString(), ContentType.Application.Json)
            }
            with(audioRoutes) { audio(config) }
            with(forwarding) { modelRoutes(config) }
        }
    }
}
