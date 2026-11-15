package com.suhel.llamabro.hub.internal

/** Upstream download.cpp's get_gguf_split_info result. */
internal class SplitInfo(val prefix: String, val tag: String, val index: Int, val count: Int)

private val splitPattern = Regex("^(.+)-([0-9]{5})-of-([0-9]{5})$", RegexOption.IGNORE_CASE)
private val tagPattern = Regex("[-.]([A-Z0-9_]+)$", RegexOption.IGNORE_CASE)
private val sidecarKeywords = listOf("mmproj", "mtp-", "dflash-", "eagle3-", "dspark-")

/** Port of download.cpp's get_gguf_split_info. */
internal fun splitInfo(path: String): SplitInfo {
    if (!path.endsWith(".gguf")) return SplitInfo("", "", 0, 0)
    var prefix = path.removeSuffix(".gguf")
    var index = 1
    var count = 1
    splitPattern.matchEntire(prefix)?.let {
        val (shardPrefix, shardIndex, shardCount) = it.destructured
        prefix = shardPrefix
        index = shardIndex.toInt()
        count = shardCount.toInt()
    }
    val tag =
        tagPattern
            .find(prefix)
            ?.groupValues
            ?.get(1)
            ?.uppercase() ?: ""
    return SplitInfo(prefix, tag, index, count)
}

/** Port of download.cpp's extract_quant_bits (C stoi reads leading digits). */
internal fun quantBits(path: String): Int {
    val tag = splitInfo(path).tag
    val firstDigit = tag.indexOfFirst { it in '0'..'9' }
    if (firstDigit < 0) return 0
    return tag.substring(firstDigit).takeWhile { it in '0'..'9' }.toIntOrNull() ?: 0
}

/** Port of download.cpp's name_is_sidecar. The keyword match is case-sensitive. */
internal fun nameIsSidecar(name: String): Boolean = sidecarKeywords.any { name.contains(it) }

/** Port of download.cpp's gguf_filename_is_model. */
internal fun isModelGguf(path: String): Boolean {
    if (!path.endsWith(".gguf")) return false
    val name = path.substringAfterLast('/')
    return !name.contains("imatrix") && !nameIsSidecar(name)
}

/** A Hub tree entry after oid and size validation (A.3). */
internal class RepoFile(val path: String, val oid: String? = null, val sizeBytes: Long = 0)

/** The model and supported sidecars chosen by upstream's plan rules. */
internal class Resolved(
    val primary: RepoFile?,
    val modelFiles: List<RepoFile>,
    val mmproj: RepoFile?,
    val dflash: RepoFile?,
)

/** Port of download.cpp's get_split_files. */
internal fun splitFiles(all: List<RepoFile>, file: RepoFile): List<RepoFile> {
    val split = splitInfo(file.path)
    if (split.count <= 1) return listOf(file)
    return all.filter {
        val other = splitInfo(it.path)
        other.count == split.count && other.prefix == split.prefix
    }
}

/** Port of download.cpp's find_best_model, including listing-order ties. */
internal fun findBestModel(files: List<RepoFile>, tag: String): RepoFile? {
    val tags = if (tag.isNotEmpty()) listOf(tag) else listOf("Q4_K_M", "Q8_0")
    for (wanted in tags) {
        // the tag starts at a token boundary, so ":F16" does not pick a BF16 file (upstream #31620, #31639)
        val pattern = Regex("(^|[-./])" + wanted + "[.-]", RegexOption.IGNORE_CASE)
        files
            .firstOrNull { isModelGguf(it.path) && pattern.containsMatchIn(it.path) && startsSplit(it.path) }
            ?.let { return it }
    }
    return if (tag.isEmpty()) files.firstOrNull { isModelGguf(it.path) && startsSplit(it.path) } else null
}

/** A whole file, or the first shard of a split one. */
private fun startsSplit(path: String): Boolean = splitInfo(path).let { it.count <= 1 || it.index == 1 }

/** A candidate for [findBestSibling]: how close it sits to the model in the tree and in quantization. */
private class Sibling(val file: RepoFile, val depth: Int, val diff: Int, val exact: Boolean) {
    /** download.cpp's order: the deeper directory first, then an exact tag match, then the closer quantization. */
    fun beats(other: Sibling?): Boolean {
        if (other == null || depth > other.depth) return true
        if (depth < other.depth) return false
        return if (exact != other.exact) exact else diff < other.diff
    }
}

/** Port of download.cpp's find_best_sibling. */
internal fun findBestSibling(files: List<RepoFile>, modelPath: String, keyword: String, tag: String = ""): RepoFile? {
    val tagUpper = tag.uppercase()
    val modelBits = if (tagUpper.isNotEmpty()) leadingQuantBits(tagUpper) else quantBits(modelPath)
    val modelDirs = modelPath.split('/').dropLast(1)
    return files
        .asSequence()
        .filter { it.path.endsWith(".gguf") && it.path.contains(keyword) }
        .mapNotNull { file ->
            val siblingDirs = file.path.split('/').dropLast(1)
            if (siblingDirs.size > modelDirs.size ||
                siblingDirs != modelDirs.take(siblingDirs.size)
            ) {
                return@mapNotNull null
            }
            val diff = kotlin.math.abs(quantBits(file.path) - modelBits)
            val exact = tagUpper.isNotEmpty() && file.path.uppercase().contains("-$tagUpper.")
            Sibling(file, siblingDirs.size, diff, exact)
        }.fold(null as Sibling?) { best, candidate -> if (candidate.beats(best)) candidate else best }
        ?.file
}

private fun leadingQuantBits(tag: String): Int {
    val firstDigit = tag.indexOfFirst { it in '0'..'9' }
    if (firstDigit < 0) return 0
    return tag.substring(firstDigit).takeWhile { it in '0'..'9' }.toIntOrNull() ?: 0
}

/** Port of download.cpp's common_download_get_hf_plan for the two sidecars this SDK supports. */
internal fun resolvePlan(
    all: List<RepoFile>,
    tag: String,
    hfFile: String,
    wantMmproj: Boolean,
    wantDflash: Boolean,
): Resolved {
    val primary = if (hfFile.isNotEmpty()) all.firstOrNull { it.path == hfFile } else findBestModel(all, tag)
    // download.cpp returns an empty plan, sidecars included, when a named file isn't in the repo.
    if (hfFile.isNotEmpty() && primary == null) return Resolved(null, emptyList(), null, null)
    val models = primary?.let { splitFiles(all, it) } ?: emptyList()
    val mmproj = if (wantMmproj && primary != null) findBestSibling(all, primary.path, "mmproj") else null
    val dflash = if (wantDflash) findBestSibling(all, primary?.path ?: "", "dflash-", tag) else null
    return Resolved(primary, models, mmproj, dflash)
}
