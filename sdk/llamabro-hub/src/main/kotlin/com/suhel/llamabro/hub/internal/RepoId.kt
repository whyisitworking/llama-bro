package com.suhel.llamabro.hub.internal

import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.nio.file.Paths

/** Port of hf-cache.cpp's is_valid_repo_id. */
internal fun isValidRepoId(repo: String): Boolean {
    if (repo.isEmpty() || repo.length > 256) return false
    var slashCount = 0
    var special = true
    for (char in repo) {
        when {
            char.isAsciiAlphanumeric() || char == '_' -> special = false
            char == '/' || char == '.' || char == '-' -> {
                if (special) return false
                if (char == '/') slashCount++
                special = true
            }
            else -> return false
        }
    }
    return !special && slashCount == 1
}

/** Port of hf-cache.cpp's is_valid_subpath, using normalized path components. */
internal fun isValidSubpath(base: Path, sub: String): Boolean {
    return try {
        val child = Paths.get(sub)
        if (child.isAbsolute) return false
        val normalizedBase = base.toAbsolutePath().normalize()
        normalizedBase.resolve(child).normalize().startsWith(normalizedBase)
    } catch (_: InvalidPathException) {
        false
    }
}

/** Port of hf-cache.cpp's is_valid_hf_token. */
internal fun isValidHfToken(token: String): Boolean =
    token.length in 37..256 && token.startsWith("hf_") && token.substring(3).all { it.isAsciiAlphanumeric() }

/** Port of hf-cache.cpp's is_valid_commit. */
internal fun isValidCommit(hash: String): Boolean = hash.length == 40 && hash.all { it.isAsciiHex() }

/** Port of hf-cache.cpp's is_valid_oid. */
internal fun isValidOid(oid: String): Boolean = oid.length in setOf(40, 64) && oid.all { it.isAsciiHex() }

/** Port of hf-cache.cpp's repo_to_folder_name. */
internal fun repoFolder(repo: String): String = "models--" + repo.replace("/", "--")

/** Port of hf-cache.cpp's folder_name_to_repo. */
internal fun repoOfFolder(folder: String): String =
    if (folder.startsWith("models--")) folder.removePrefix("models--").replace("--", "/") else ""

/** Port of download.cpp's common_download_split_repo_tag. */
internal fun splitRepoTag(ref: String): Pair<String, String> {
    val parts = ref.split(':')
    val repo = parts.first()
    require(repo.split('/').size == 2) {
        "invalid HF repo format, expected <user>/<model>[:quant]"
    }
    require(isValidRepoId(repo)) { "invalid HF repo id: $repo" }
    return repo to if (parts.size > 1) parts.last() else ""
}

private fun Char.isAsciiAlphanumeric(): Boolean =
    this in 'A'..'Z' || this in 'a'..'z' || this in '0'..'9'

private fun Char.isAsciiHex(): Boolean =
    this in 'A'..'F' || this in 'a'..'f' || this in '0'..'9'
