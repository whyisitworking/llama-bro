package com.suhel.llamabro.rag

import okio.FileSystem
import okio.Path

/**
 * What a library holds and what built it, read from its folder without an embedder ([Library.contents]).
 * @property builtBy the embedder its vectors came from; a [Library.open] with another fails with [IndexMismatch].
 */
public data class LibraryContents(val builtBy: EmbedderIdentity, val documents: List<DocumentInfo>)

/*
 * A rebuild writes a whole new library in `<dir>.rebuild` beside the old one. Renaming it `<dir>.rebuilt` commits it;
 * moving the old folder to `<dir>.old` and the new one into its place finishes it, and the old one is deleted. A crash
 * before the commit leaves the old library and a `.rebuild` folder the next open deletes; a crash after it leaves a
 * `.rebuilt` folder the next open moves into place, so the library is always one whole version or the other.
 */
internal const val REBUILDING = ".rebuild"
internal const val REBUILT = ".rebuilt"
private const val REPLACED = ".old"

internal fun sibling(dir: Path, suffix: String): Path =
    requireNotNull(dir.parent) { "a library's folder needs a parent: $dir" } / "${dir.name}$suffix"

/** Finishes a committed rebuild of [dir] and deletes what an unfinished one left. Blocking I/O. */
internal fun finishRebuild(fs: FileSystem, dir: Path) {
    if (dir.parent == null) return
    val rebuilt = sibling(dir, REBUILT)
    val replaced = sibling(dir, REPLACED)
    if (fs.exists(rebuilt)) {
        if (fs.exists(dir)) {
            fs.deleteRecursively(replaced)
            fs.atomicMove(dir, replaced)
        }
        fs.atomicMove(rebuilt, dir)
    }
    fs.deleteRecursively(replaced)
    fs.deleteRecursively(sibling(dir, REBUILDING))
}
