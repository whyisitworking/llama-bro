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
        prefix = it.groupValues[1]
        index = it.groupValues[2].toInt()
        count = it.groupValues[3].toInt()
    }
    val tag = tagPattern.find(prefix)?.groupValues?.get(1)?.uppercase() ?: ""
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
        val pattern = Regex(wanted + "[.-]", RegexOption.IGNORE_CASE)
        for (file in files) {
            if (!isModelGguf(file.path) || !pattern.containsMatchIn(file.path)) continue
            val split = splitInfo(file.path)
            if (split.count > 1 && split.index != 1) continue
            return file
        }
    }
    if (tag.isEmpty()) {
        return files.firstOrNull {
            val split = splitInfo(it.path)
            isModelGguf(it.path) && (split.count <= 1 || split.index == 1)
        }
    }
    return null
}

/** Port of download.cpp's find_best_sibling. */
internal fun findBestSibling(
    files: List<RepoFile>, modelPath: String, keyword: String, tag: String = "",
): RepoFile? {
    val tagUpper = tag.uppercase()
    val modelBits = if (tagUpper.isNotEmpty()) leadingQuantBits(tagUpper) else quantBits(modelPath)
    val modelDirs = modelPath.split('/').dropLast(1)
    var best: RepoFile? = null
    var bestDepth = 0
    var bestDiff = 0
    var bestExact = false
    for (file in files) {
        if (!file.path.endsWith(".gguf") || !file.path.contains(keyword)) continue
        val siblingDirs = file.path.split('/').dropLast(1)
        if (siblingDirs.size > modelDirs.size || siblingDirs != modelDirs.take(siblingDirs.size)) continue
        val depth = siblingDirs.size
        val diff = kotlin.math.abs(quantBits(file.path) - modelBits)
        val exact = tagUpper.isNotEmpty() && file.path.uppercase().contains("-$tagUpper.")
        if (best == null || depth > bestDepth ||
            (depth == bestDepth && exact && !bestExact) ||
            (depth == bestDepth && exact == bestExact && diff < bestDiff)
        ) {
            best = file
            bestDepth = depth
            bestDiff = diff
            bestExact = exact
        }
    }
    return best
}

private fun leadingQuantBits(tag: String): Int {
    val firstDigit = tag.indexOfFirst { it in '0'..'9' }
    if (firstDigit < 0) return 0
    return tag.substring(firstDigit).takeWhile { it in '0'..'9' }.toIntOrNull() ?: 0
}

/** Port of download.cpp's common_download_get_hf_plan for the two sidecars this SDK supports. */
internal fun resolvePlan(
    all: List<RepoFile>, tag: String, hfFile: String, wantMmproj: Boolean, wantDflash: Boolean,
): Resolved {
    val primary = if (hfFile.isNotEmpty()) all.firstOrNull { it.path == hfFile } else findBestModel(all, tag)
    // download.cpp returns an empty plan, sidecars included, when a named file isn't in the repo.
    if (hfFile.isNotEmpty() && primary == null) return Resolved(null, emptyList(), null, null)
    val models = primary?.let { splitFiles(all, it) } ?: emptyList()
    val mmproj = if (wantMmproj && primary != null) findBestSibling(all, primary.path, "mmproj") else null
    val dflash = if (wantDflash) findBestSibling(all, primary?.path ?: "", "dflash-", tag) else null
    return Resolved(primary, models, mmproj, dflash)
}
