package com.suhel.llamabro.server

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.path
import io.ktor.server.request.userAgent
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private const val PAGE = "pair.html"
private const val MILLIS_PER_SECOND = 1_000L

/** The pairing page, a resource beside this class: one HTML file with its CSS and script inline, and nothing else. */
internal fun pairingPage(): String =
    checkNotNull(Pairings::class.java.getResourceAsStream(PAGE)) { "$PAGE is missing" }
        .bufferedReader()
        .use { it.readText() }

/** The paths pairing answers without a key. */
internal fun isPairingPath(path: String): Boolean = path == "/" || path == "/pair" || path.startsWith("/pair/")

private val ipv4 = Regex("""\d{1,3}(\.\d{1,3}){3}""")

/** Names only a LAN resolves: a page reached under any other is another site's, rebound to this address. */
private val lanSuffixes = listOf(".local", ".lan", ".home.arpa", ".internal")

/**
 * Whether [host] (a `Host` header) names this device as only the LAN can: an address, `localhost`, a single label or
 * a LAN suffix. A public name pointed at the device's address (DNS rebinding) would make another site the page's
 * origin, able to read the key once the person approves.
 */
internal fun isLanHost(host: String): Boolean {
    val name =
        when {
            host.startsWith("[") -> return host.contains("]")
            else -> host.substringBeforeLast(':').lowercase().trimEnd('.')
        }
    return name.isNotEmpty() &&
        (ipv4.matches(name) || name == "localhost" || '.' !in name || lanSuffixes.any { name.endsWith(it) })
}

/**
 * `GET /`, `POST /pair` and `GET /pair/{id}`
 * ([design §6](https://github.com/whyisitworking/llama-bro/blob/main/docs/design/server.md),
 * [D174](https://github.com/whyisitworking/llama-bro/blob/main/docs/decisions.md#d174)), served only when the
 * app set [ServerConfig.pairing]. They need no key, sit behind the key's throttle ([wrongKeys]: a wrong id is a
 * wrong guess) and answer only the page's own origin, never another site's, whatever the server's CORS.
 */
internal class PairingRoutes(
    private val pairings: Pairings,
    private val wrongKeys: WrongKeys?,
    private val onRejected: (client: String, path: String, status: Int, reason: String) -> Unit,
) {
    private val page by lazy { pairingPage() }

    fun Route.pairing() {
        get("/") {
            if (!call.admitted()) return@get
            call.noStore()
            call.response.header("Content-Security-Policy", PAGE_POLICY)
            call.response.header("X-Frame-Options", "DENY")
            call.respondText(page, ContentType.Text.Html.withParameter("charset", "utf-8"))
        }
        post("/pair") {
            if (!call.admitted()) return@post
            val client = call.request.local.remoteAddress
            when (val started = pairings.start(client, call.request.userAgent())) {
                is Pairings.Start.Begun -> {
                    call.noStore()
                    call.respondJson(
                        buildJsonObject {
                            put("id", started.id)
                            put("code", started.code)
                        }.toString(),
                    )
                }

                is Pairings.Start.Full -> {
                    val seconds = (started.retryAfterMillis + MILLIS_PER_SECOND - 1) / MILLIS_PER_SECOND
                    call.response.header(HttpHeaders.RetryAfter, seconds.toString())
                    call.refuse(HttpStatusCode.TooManyRequests, "too many pairings waiting", ErrorType.RATE_LIMIT)
                }
            }
        }
        get("/pair/{id}") {
            val id = call.parameters["id"].orEmpty()
            val client = call.request.local.remoteAddress
            // looked up first, so the throttle judges and records the guess in one step; the answer is taken after
            if (!call.admitted(guessed = !pairings.exists(client, id))) return@get
            val state = pairings.poll(client, id)
            if (state == null) {
                call.refuse(HttpStatusCode.NotFound, "no such pairing", ErrorType.NOT_FOUND)
                return@get
            }
            call.noStore()
            call.respondJson(
                buildJsonObject {
                    when (state) {
                        Pairings.State.Pending -> {
                            put("state", "pending")
                        }

                        Pairings.State.Denied -> {
                            put("state", "denied")
                        }

                        Pairings.State.Expired -> {
                            put("state", "expired")
                        }

                        is Pairings.State.Approved -> {
                            put("state", "approved")
                            put("key", state.key)
                            put("baseUrl", call.baseUrl())
                        }
                    }
                }.toString(),
            )
        }
    }

    /**
     * Whether the call may go on: not throttled (a [guessed] call is recorded as a wrong guess in the same step), and
     * from the page's own origin on a LAN name. Else it has been answered.
     */
    private suspend fun ApplicationCall.admitted(guessed: Boolean = false): Boolean {
        val wait = wrongKeys?.check(request.local.remoteAddress, ok = false, guessed = guessed)
        val host = request.headers[HttpHeaders.Host].orEmpty()
        val origin = request.headers[HttpHeaders.Origin]
        when {
            wait != null -> {
                respondThrottled(wait, onRejected)
            }

            !isLanHost(host) || (origin != null && origin != "http://$host") -> {
                refuse(HttpStatusCode.Forbidden, "pairing from another site", ErrorType.PERMISSION)
            }

            guessed -> {
                refuse(HttpStatusCode.NotFound, "no such pairing", ErrorType.NOT_FOUND)
            }

            else -> {
                return true
            }
        }
        return false
    }

    private suspend fun ApplicationCall.refuse(status: HttpStatusCode, reason: String, type: String) {
        onRejected(request.local.remoteAddress, request.path(), status.value, reason)
        respondError(status, reason.replaceFirstChar { it.uppercase() }, type)
    }

    private fun ApplicationCall.noStore() = response.header(HttpHeaders.CacheControl, "no-store")

    private suspend fun ApplicationCall.respondJson(body: String) = respondText(body, ContentType.Application.Json)

    /** The server as the computer reached it: its `Host`, which [admitted] has checked. */
    private fun ApplicationCall.baseUrl(): String = "http://${request.headers[HttpHeaders.Host]}"

    private companion object {
        // the page's own inline style and script, and requests to this server only
        const val PAGE_POLICY =
            "default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; connect-src 'self'; " +
                "base-uri 'none'; form-action 'none'; frame-ancestors 'none'"
    }
}
