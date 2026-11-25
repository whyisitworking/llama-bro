package com.suhel.llamabro.demo.models

/**
 * Hugging Face links from a model page, read as text (no `java.net`): which repo a link names, and where a relative
 * one points. Both refuse anything that is not plain http(s), and a relative link never leaves the Hub's host.
 */
object HubLinks {
    private const val ORIGIN = "https://huggingface.co"
    private val HOSTS = setOf("huggingface.co", "www.huggingface.co", "hf.co", "www.hf.co")
    private const val MAX_ID = 256
    private val WEB = setOf("http", "https")
    private val SCHEME = Regex("^([a-zA-Z][a-zA-Z0-9+.-]*):")
    private val UNSAFE = Regex("[\\s\\u0000-\\u001f\\\\]")

    /** First path segments of the site that are not a model's owner. */
    private val NOT_OWNERS =
        setOf(
            "datasets",
            "spaces",
            "papers",
            "collections",
            "docs",
            "blog",
            "models",
            "organizations",
            "settings",
            "join",
            "login",
        )

    /** The `owner/name` a link names on huggingface.co or hf.co, or null for anything else. */
    fun repoOf(url: String): String? {
        val link = url.trim()
        if (schemeOf(link) !in WEB) return null
        val afterScheme = link.substringAfter("://", missingDelimiterValue = "")
        val authority = afterScheme.takeWhile { it !in "/?#" }
        val path = afterScheme.removePrefix(authority).substringBefore('#').substringBefore('?')
        val segments = path.split('/').filter { it.isNotEmpty() }
        val named = authority.lowercase() in HOSTS && segments.size >= 2 && segments[0].lowercase() !in NOT_OWNERS
        val repo = if (named) "${segments[0]}/${segments[1]}" else null
        // an id the SDK refuses is no repo (it would throw), so its link goes to the browser
        return repo?.takeIf { !UNSAFE.containsMatchIn(path) && validId(it) }
    }

    /** The SDK's repo id rules: ASCII letters, digits, `_`, single `.` or `-` between them, one `/`, max 256. */
    private fun validId(repo: String): Boolean {
        var special = true
        var slashes = 0
        for (char in repo) {
            when {
                char in 'A'..'Z' || char in 'a'..'z' || char in '0'..'9' || char == '_' -> {
                    special = false
                }

                char == '/' || char == '.' || char == '-' -> {
                    if (special) return false
                    if (char == '/') slashes++
                    special = true
                }

                else -> {
                    return false
                }
            }
        }
        return repo.length <= MAX_ID && !special && slashes == 1
    }

    /**
     * [href] resolved against [pageRepo]'s `blob/main/`, for a model page's relative links; an absolute http(s) link
     * is kept. Null for any other scheme, a protocol-relative link, a fragment alone, or text with spaces.
     */
    fun absolute(href: String, pageRepo: String): String? {
        val link = href.trim()
        val scheme = schemeOf(link)
        return when {
            link.isEmpty() || link.startsWith("#") || link.startsWith("//") -> null
            UNSAFE.containsMatchIn(link) -> null
            scheme != null -> link.takeIf { scheme in WEB && hasHost(link) }
            else -> resolve(link, pageRepo)
        }
    }

    private fun schemeOf(link: String): String? =
        SCHEME
            .find(link)
            ?.groupValues
            ?.get(1)
            ?.lowercase()

    private fun hasHost(link: String): Boolean =
        link.substringAfter(':').startsWith("//") && link.substringAfter("//").takeWhile { it !in "/?#" }.isNotEmpty()

    private fun resolve(link: String, pageRepo: String): String {
        val tail = link.indexOfFirst { it == '?' || it == '#' }.let { if (it < 0) "" else link.substring(it) }
        val path = link.substring(0, link.length - tail.length)
        val base = if (path.startsWith("/")) emptyList() else pageRepo.split('/') + listOf("blob", "main")
        val resolved = ArrayDeque(base)
        for (part in path.split('/')) {
            when (part) {
                "", "." -> Unit

                // past the root stays at the root: a link cannot climb out of the host
                ".." -> resolved.removeLastOrNull()

                else -> resolved.addLast(part)
            }
        }
        return ORIGIN + "/" + resolved.joinToString("/") + tail
    }
}

/** Where a link from a model page goes: a model's card in the app, or the browser. */
sealed interface LinkTarget {
    data class Card(val model: SearchModel) : LinkTarget

    data class Browser(val url: String) : LinkTarget
}
