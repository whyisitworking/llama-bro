package com.suhel.llamabro.demo.ui.library

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import com.suhel.llamabro.demo.designsystem.components.DialogCard
import com.suhel.llamabro.demo.designsystem.components.gutterPadding
import com.suhel.llamabro.demo.ui.preview.DemoPreviews
import com.suhel.llamabro.demo.ui.preview.DemoScreenPreviews
import com.suhel.llamabro.demo.ui.preview.PreviewFrame

// The Library page in each state a person can find it in (docs/design/rag.md, the demo), and its question.

private const val EMBEDDER = "BGE Small"

private val ready = EmbedderLine.Ready(EMBEDDER)

private val documents =
    listOf(
        DocumentRow("a", "Tenancy agreement", "48 passages · Tenancy agreement.md"),
        DocumentRow("b", "Trip notes", "6 passages · Trip notes.md"),
        DocumentRow("c", "Recipes I keep losing", "1 passage · Recipes I keep losing.txt"),
    )

internal data class LibraryPreview(val name: String, val page: LibraryPage) {
    override fun toString() = name
}

internal class LibraryStates : PreviewParameterProvider<LibraryPreview> {
    override val values =
        sequenceOf(
            LibraryPreview("Empty", LibraryPage(ready, emptyList())),
            LibraryPreview(
                "Importing",
                LibraryPage(ready, documents.take(2), work = WorkLine("Adding a document: 16 of 40 passages", 0.4f)),
            ),
            LibraryPreview("Listed", LibraryPage(ready, documents)),
            LibraryPreview(
                "Mismatch",
                LibraryPage(
                    ready,
                    documents,
                    mismatch =
                        MismatchLine(
                            "This library was indexed by bge-small-en-v1.5-f16, so BGE Small can't search it. " +
                                "Rebuilding indexes its 3 documents again; nothing is deleted.",
                            "Rebuild with BGE Small",
                        ),
                ),
            ),
            LibraryPreview(
                "Rebuilding",
                LibraryPage(ready, documents, work = WorkLine("Rebuilding the library: 32 of 55 passages", 0.58f)),
            ),
            LibraryPreview(
                "Doesn't fit",
                LibraryPage(
                    EmbedderLine.DoesNotFit(
                        "BGE Small doesn't fit beside Qwen3.5 9B: it needs 120 MB, and 80 MB is free.",
                    ),
                    documents,
                ),
            ),
            LibraryPreview("No embedder yet", LibraryPage(EmbedderLine.Missing(EMBEDDER, "36 MB"), emptyList())),
            LibraryPreview(
                "Getting the embedder",
                LibraryPage(EmbedderLine.Downloading(EMBEDDER, 0.35f), emptyList()),
            ),
            LibraryPreview(
                "A file refused",
                LibraryPage(
                    ready,
                    documents.take(1),
                    notice = "Lease.pdf is a PDF: only text and Markdown files can be added for now.",
                ),
            ),
        )
}

@DemoScreenPreviews
@Composable
internal fun LibraryContentPreview(
    @PreviewParameter(LibraryStates::class) p: LibraryPreview,
) {
    PreviewFrame { LibraryContent(p.page, LibraryActions(onBack = {})) }
}

@DemoPreviews
@Composable
internal fun RemoveDialogPreview() {
    PreviewFrame {
        DialogCard(
            removeTitle("Trip notes"),
            REMOVE_TEXT,
            "Remove",
            {},
            {},
            Modifier.gutterPadding(),
            "Cancel",
            danger = true,
        )
    }
}
