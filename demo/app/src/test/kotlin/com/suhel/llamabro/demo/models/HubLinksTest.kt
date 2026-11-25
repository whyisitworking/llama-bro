package com.suhel.llamabro.demo.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A link on a model page names a repo, or is for the browser; a relative one resolves without leaving the Hub. */
class HubLinksTest {
    private val repo = "Qwen/Qwen3-8B"

    @Test fun acceptedFormsNameTheirRepo() {
        val cases =
            listOf(
                "https://huggingface.co/Qwen/Qwen3-8B",
                "https://huggingface.co/Qwen/Qwen3-8B/",
                "http://huggingface.co/Qwen/Qwen3-8B",
                "https://HuggingFace.co/Qwen/Qwen3-8B",
                "https://www.huggingface.co/Qwen/Qwen3-8B",
                "https://huggingface.co/Qwen/Qwen3-8B/tree/main",
                "https://huggingface.co/Qwen/Qwen3-8B/blob/main/README.md",
                "https://huggingface.co/Qwen/Qwen3-8B/resolve/main/x.gguf",
                "https://huggingface.co/Qwen/Qwen3-8B?library=gguf",
                "https://huggingface.co/Qwen/Qwen3-8B#usage",
                "https://hf.co/Qwen/Qwen3-8B",
                "https://HF.CO/Qwen/Qwen3-8B/",
            )
        cases.forEach { assertEquals(it, "Qwen/Qwen3-8B", HubLinks.repoOf(it)) }
    }

    @Test fun refusedFirstSegmentsAreNotRepos() {
        listOf(
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
        ).forEach { assertNull(it, HubLinks.repoOf("https://huggingface.co/$it/foo")) }
    }

    @Test fun otherLinksAreNotRepos() {
        listOf(
            "https://huggingface.co/Qwen",
            "https://huggingface.co/",
            "https://huggingface.co",
            "https://example.com/Qwen/Qwen3-8B",
            "https://huggingface.co.evil.com/Qwen/Qwen3-8B",
            "https://evil.com/huggingface.co/Qwen/Qwen3-8B",
            "ftp://huggingface.co/Qwen/Qwen3-8B",
            "javascript:alert(1)",
            "",
        ).forEach { assertNull(it, HubLinks.repoOf(it)) }
    }

    @Test fun aRelativeLinkResolvesAgainstThePageRepo() {
        assertEquals(
            "https://huggingface.co/Qwen/Qwen3-8B/blob/main/config.json",
            HubLinks.absolute("./config.json", repo),
        )
        assertEquals(
            "https://huggingface.co/Qwen/Qwen3-8B/blob/main/config.json",
            HubLinks.absolute("config.json", repo),
        )
        assertEquals("https://huggingface.co/Qwen/Qwen3-4B", HubLinks.absolute("/Qwen/Qwen3-4B", repo))
        assertEquals("https://huggingface.co/Qwen/Qwen3-8B/blob/main/a/b.md", HubLinks.absolute("a/b.md", repo))
    }

    @Test fun anAbsoluteHttpLinkIsKept() {
        assertEquals("https://example.com/x", HubLinks.absolute("https://example.com/x", repo))
        assertEquals("http://example.com/x", HubLinks.absolute("http://example.com/x", repo))
    }

    @Test fun unsafeLinksAreNull() {
        listOf(
            "javascript:x",
            "JavaScript:alert(1)",
            "data:text/html,hi",
            "file:///etc/passwd",
            "intent://x#Intent;end",
            "mailto:a@b.c",
            "//evil.com/x",
            "\\\\evil.com\\x",
            "",
            "#top",
        ).forEach { assertNull(it, HubLinks.absolute(it, repo)) }
    }

    @Test fun dotDotCannotLeaveTheHub() {
        listOf("../../../../evil.com/x", "/../../evil.com/x", "../../../x").forEach {
            val resolved = HubLinks.absolute(it, repo)
            if (resolved != null) {
                assertEquals(it, true, resolved.startsWith("https://huggingface.co/") && ".." !in resolved)
            }
        }
        assertEquals("https://huggingface.co/Qwen/Qwen3-8B/blob/x", HubLinks.absolute("../x", repo))
    }
}
