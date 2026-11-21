package com.suhel.llamabro.hub

import com.suhel.llamabro.hub.internal.JvmStorage
import com.suhel.llamabro.hub.internal.Response
import com.suhel.llamabro.hub.internal.Transport
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.util.Collections

/** What each search option asks the Hub for (models-search design), and a cursor kept as text. */
class SearchOptionsTest {
    private val asked: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val links = mutableMapOf<String, String>()

    private val transport =
        object : Transport {
            override suspend fun open(
                method: String,
                url: String,
                headers: Map<String, String>,
                range: Long?,
                rangeEnd: Long?,
            ): Response {
                asked += url
                val link = links[url]?.let { mapOf("link" to listOf("<$it>; rel=\"next\"")) }.orEmpty()
                return Response(200, link, url, ByteArrayInputStream("[]".toByteArray())) {}
            }
        }

    private val hub =
        Files.createTempDirectory("search-options-").toFile().let { cache ->
            LlamaBroHub(
                HubConfig {
                    endpoint = ENDPOINT
                    cacheDir = cache
                },
                cache,
                transport,
                JvmStorage(),
            )
        }

    private fun url(query: String = "qwen", sort: String = "downloads", tail: String = ""): String =
        "${ENDPOINT}api/models?search=$query&filter=gguf&sort=$sort&direction=-1&limit=40$EXPAND$tail"

    @Test fun aParameterRangeIsTheHubsNumParametersFilter() =
        runBlocking {
            hub.search("qwen") { maxParameters = 6_600_000_000 }
            hub.search("qwen") { minParameters = 1_000_000_000 }
            hub.search("qwen") {
                minParameters = 1_000_000_000
                maxParameters = 4_000_000_000
            }
            assertEquals(
                listOf(
                    url(tail = "&num_parameters=max:6600000000"),
                    url(tail = "&num_parameters=min:1000000000"),
                    url(tail = "&num_parameters=min:1000000000,max:4000000000"),
                ),
                asked,
            )
        }

    @Test fun hideGatedAsksForOnlyReposAnyoneCanDownload() =
        runBlocking {
            hub.search("qwen") { hideGated = true }
            assertEquals(listOf(url(tail = "&gated=false")), asked)
        }

    @Test fun newestSortsByCreation() =
        runBlocking {
            hub.search("qwen") { sort = SearchSort.Newest }
            assertEquals(listOf(url(sort = "createdAt")), asked)
        }

    @Test fun everyOptionTogether() =
        runBlocking {
            hub.search("qwen") {
                sort = SearchSort.Trending
                takesImages = true
                maxParameters = 4_000_000_000
                hideGated = true
            }
            assertEquals(
                listOf(
                    url(
                        sort = "trendingScore",
                        tail = "&pipeline_tag=image-text-to-text&num_parameters=max:4000000000&gated=false",
                    ),
                ),
                asked,
            )
        }

    @Test fun aBadParameterRangeIsRefusedWithNoRequest() {
        val refused =
            listOf<SearchOptions.() -> Unit>(
                { minParameters = -1 },
                { maxParameters = 0 },
                {
                    minParameters = 4_000_000_000
                    maxParameters = 1_000_000_000
                },
            ).count { options ->
                runCatching { runBlocking { hub.search("qwen", options) } }.exceptionOrNull() is
                    IllegalArgumentException
            }
        assertEquals(3, refused)
        assertTrue(asked.isEmpty())
    }

    @Test fun aCursorKeptAsTextFetchesTheSamePage() =
        runBlocking {
            val second = url() + "&cursor=abc"
            links[url()] = second
            val kept = hub.search("qwen").next!!.value
            hub.search(SearchCursor(kept))
            assertEquals(listOf(url(), second), asked)
        }

    @Test fun aCursorOffTheEndpointIsRefusedWithNoRequest() =
        runBlocking {
            val refused = runCatching { hub.search(SearchCursor("https://elsewhere.test/api/models?cursor=x")) }
            assertTrue(refused.exceptionOrNull() is IllegalArgumentException)
            val garbled = runCatching { hub.search(SearchCursor("not a url")) }
            assertTrue(garbled.exceptionOrNull() is IllegalArgumentException)
            assertTrue(asked.isEmpty())
        }

    private companion object {
        const val ENDPOINT = "https://hub.test/"
        const val EXPAND =
            "&expand[]=downloads&expand[]=likes&expand[]=tags&expand[]=pipeline_tag&expand[]=lastModified&expand[]=gguf"
    }
}
