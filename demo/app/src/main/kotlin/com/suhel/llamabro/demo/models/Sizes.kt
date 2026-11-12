package com.suhel.llamabro.demo.models

enum class SizeTier { Smaller, Recommended, Best }

/**
 * One model file in a repo: [quant] is the tag in its name (`Q4_K_M`, `UD-Q4_K_XL`, `BF16`), [bytes] all its shards.
 */
data class RepoFile(val path: String, val quant: String, val bytes: Long)

/** The three sizes the sheet's Basic view offers. */
object Sizes {
    fun tiers(files: List<RepoFile>): Map<SizeTier, RepoFile> {
        val models = files.filterNot { it.path.substringAfterLast('/').contains("mmproj", ignoreCase = true) }
        val recommended =
            models.firstOrNull { it.quant == "Q4_K_M" }
                ?: models.firstOrNull { it.quant == "UD-Q4_K_XL" }
                ?: models.filter { it.quant.startsWith("Q4_") }.maxByOrNull { it.bytes }
        val smaller = recommended?.let { smallerThan(models, it) }
        val best =
            (
                models.firstOrNull { it.quant == "Q8_0" }
                    ?: models.firstOrNull { it.quant == "Q6_K" }
                    ?: models.maxByOrNull { it.bytes }
            )?.takeIf { it.path != recommended?.path }
        return buildMap {
            smaller?.let { put(SizeTier.Smaller, it) }
            recommended?.let { put(SizeTier.Recommended, it) }
            best?.let { put(SizeTier.Best, it) }
        }
    }

    /** The largest file under 75% of Recommended's size, preferring `Q3_K_M`, then `IQ3_*`. */
    private fun smallerThan(models: List<RepoFile>, recommended: RepoFile): RepoFile? {
        val candidates = models.filter { it.bytes * 4 < recommended.bytes * 3 }
        return candidates.firstOrNull { it.quant == "Q3_K_M" }
            ?: candidates.filter { it.quant.removePrefix("UD-").startsWith("IQ3_") }.maxByOrNull { it.bytes }
            ?: candidates.maxByOrNull { it.bytes }
    }
}
