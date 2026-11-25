package com.suhel.llamabro.hub

import com.suhel.llamabro.chat.Sampling

/** The order of [LlamaBroHub.search]'s results, most first. */
public enum class SearchSort {
    /** Downloads in the last 30 days, the Hub's default. */
    Downloads,

    /** Likes, all time. */
    Likes,

    /** The Hub's trending score: recent likes and downloads. */
    Trending,

    /** The last commit, newest first. */
    RecentlyUpdated,

    /** The repo's creation, newest first. */
    Newest,
}

/** Mutable scope of [LlamaBroHub.search]. Every search is for GGUF repos. */
public class SearchOptions internal constructor() {
    /** The order of the results. */
    public var sort: SearchSort = SearchSort.Downloads

    /** Only repos of this user or organisation, such as `ggml-org`; null for anyone's. */
    public var author: String? = null

    /**
     * Only the uploads quantized from this original, an `owner/name` repo id such as `Qwen/Qwen3-8B` (the Hub's
     * `base_model:quantized:` filter); null for any. An id that is not a repo id is refused.
     */
    public var quantizedFrom: String? = null

    /** Only models that take pictures as well as text (the Hub's `image-text-to-text` task). */
    public var takesImages: Boolean = false

    /** Repos per page, 1 to 100; a page may hold fewer results, since quantized uploads of one model group. */
    public var limit: Int = 40

    /**
     * Only repos whose GGUF has at least this many parameters, or null for no floor. The Hub filters on each upload's
     * reported count (`num_parameters`), which an upload sometimes reports wrongly.
     */
    public var minParameters: Long? = null

    /** Only repos whose GGUF has at most this many parameters, or null for no ceiling; as [minParameters]. */
    public var maxParameters: Long? = null

    /** Leaves out gated repos, the ones that need their terms accepted on the Hub before a token can download them. */
    public var hideGated: Boolean = false
}

/**
 * One page of [LlamaBroHub.search]: the results in the Hub's order, and where the next page starts. Grouping is per
 * page, so a later page can hold more uploads of a model already shown: for an endless list, merge each page's results
 * into the earlier ones by [SearchResult.id], ignoring case.
 */
public class SearchPage internal constructor(
    /** The models on this page, in the order of their first repo. */
    public val results: List<SearchResult>,
    /** Pass to [LlamaBroHub.search] for the next page; null on the last one. */
    public val next: SearchCursor?,
) {
    override fun toString(): String = "SearchPage(results=$results, next=${next != null})"
}

/**
 * Where a search's next page starts: the Hub's own cursor, as text ([value]) an app may store, for an endless list that
 * outlives the process, and pass back. The hub follows it only on its own endpoint. The Hub may expire it: a refused
 * cursor fails as a first page does, and the search starts again from its first page.
 */
public class SearchCursor(
    /** The cursor as the Hub gave it: a URL on the hub's endpoint. */
    public val value: String,
) {
    override fun toString(): String = "SearchCursor($value)"
}

/**
 * A model found by search: its quantized uploads grouped under the original they were made from
 * ([D68](https://github.com/whyisitworking/llama-bro/blob/main/docs/decisions.md#d68)). Only repos tagged
 * `base_model:quantized:X` group under X; a fine-tune, merge or adapter is a model of its own, though it names the same
 * base ([F146](https://github.com/whyisitworking/llama-bro/blob/main/docs/research/findings.md#f146)). Grouping is
 * within one page: a later page can hold more uploads of the same model.
 */
public class SearchResult internal constructor(
    /** The original's repo id (`Qwen/Qwen3-4B`) for grouped uploads, else the repo's own id. */
    public val id: String,
    /** The repos found, in the Hub's order; each is a GGUF repo to pass to [LlamaBroHub.files]. */
    public val repos: List<SearchRepo>,
) {
    /** Downloads of every repo here. */
    public val downloads: Long get() = repos.sumOf { it.downloads }

    /** Likes of every repo here. */
    public val likes: Long get() = repos.sumOf { it.likes }

    /** Whether any repo here takes pictures (its Hub task, or a `vision` or `multimodal` tag). */
    public val takesImages: Boolean get() = repos.any { it.takesImages }

    override fun toString(): String = "SearchResult(id=$id, repos=$repos)"
}

/** One GGUF repo in a [SearchResult]. */
public class SearchRepo internal constructor(
    /** The repo id, `owner/name`. */
    public val id: String,
    /** Downloads in the last 30 days, as the Hub counts them. */
    public val downloads: Long,
    /** Likes, all time. */
    public val likes: Long,
    /** Whether the Hub lists it as taking pictures (its task, or a `vision` or `multimodal` tag). */
    public val takesImages: Boolean,
    /** The original this repo is a quantized upload of (its `base_model:quantized:` tag), or null. */
    public val baseModel: String?,
    /** The Hub's task (`pipeline_tag`, e.g. `text-generation`), or null when the repo has none. */
    public val task: String?,
    /** The repo's Hub tags, as listed. */
    public val tags: List<String>,
    /** When the repo last changed, ISO-8601 as the Hub sends it (`2026-11-12T02:15:52.000Z`), or null. */
    public val lastModified: String?,
    /** What the Hub read from the repo's GGUF headers, or null when it has none. */
    public val gguf: GgufSummary?,
) {
    /** The user or organisation that uploaded it. */
    public val author: String get() = id.substringBefore('/')

    override fun toString(): String = id
}

/** The facts the Hub lists about a repo's GGUF files, read from the search list alone, with no extra request. */
public class GgufSummary internal constructor(
    /** The parameter count of the repo's GGUF (the Hub's `gguf.total`). */
    public val parameters: Long,
    /** The model architecture (`qwen35`, `llama`, `bert`). */
    public val architecture: String,
    /** The trained context length in tokens, or null when the header has none. */
    public val contextLength: Long?,
    /** Whether the GGUF carries a chat template; embedding models do not. */
    public val hasChatTemplate: Boolean,
)

/**
 * A repo's GGUF files as [LlamaBroHub.files] lists them: sizes, and no verdict on whether they fit
 * ([D68](https://github.com/whyisitworking/llama-bro/blob/main/docs/decisions.md#d68)).
 */
public class RepoFiles internal constructor(
    /** The repo id. */
    public val repo: String,
    /** The commit listed. */
    public val commit: String,
    /** Each model file once, split files as one entry with every part's bytes, in the repo's order. */
    public val quantizations: List<Quantization>,
    /** Whether the repo has an image encoder (an `mmproj` file), which [LlamaBroHub.model] fetches with the model. */
    public val hasImageEncoder: Boolean,
    /** True when the Hub could not be reached and the list is what this phone's cache holds of the repo. */
    public val offline: Boolean,
) {
    override fun toString(): String = "RepoFiles($repo@$commit, $quantizations, imageEncoder=$hasImageEncoder)"
}

/** One quantisation of a model in [RepoFiles]. */
public class Quantization internal constructor(
    /** The quantisation's name from the file name, such as `Q4_K_M`, or the file's name when it has none. */
    public val name: String,
    /** The file, or a split file's first part. */
    public val path: String,
    /** Bytes of every part. */
    public val sizeBytes: Long,
    /** How many files it is split into; 1 when whole. */
    public val parts: Int,
    /** This exact file as a reference for [LlamaBroHub.model], which fetches every part and the image encoder. */
    public val model: HfModel,
    /**
     * Bytes of the image or audio encoder (the `mmproj` file) [LlamaBroHub.model] fetches with this quantisation, by
     * the same choice it makes when it downloads (llama.cpp's `-hf` rule); 0 when the repo has none. From the listing
     * alone: no file is read ([D101](https://github.com/whyisitworking/llama-bro/blob/main/docs/decisions.md#d101)).
     */
    public val encoderBytes: Long,
) {
    override fun toString(): String = "$name ($sizeBytes bytes, $parts part(s), encoder $encoderBytes bytes)"
}

/**
 * A model's recommended sampling from both places the Hub can hold it, each null when absent
 * ([D68](https://github.com/whyisitworking/llama-bro/blob/main/docs/decisions.md#d68),
 * [D79](https://github.com/whyisitworking/llama-bro/blob/main/docs/decisions.md#d79)). Nothing is applied: [fromFile]
 * is what loads by default, since llama.cpp applies a GGUF's own values at load
 * ([F17](https://github.com/whyisitworking/llama-bro/blob/main/docs/research/findings.md#f17)); the app chooses whether
 * to use [fromBaseModel] instead. Fields a source doesn't set stay null in its [Sampling].
 */
public class RecommendedSampling internal constructor(
    /** The GGUF header's `general.sampling.*`: what the model runs with unless the app sets its own. */
    public val fromFile: Sampling?,
    /**
     * The base model's `generation_config.json`, the values its authors publish; null when absent or gated.
     * `do_sample: false` reads as greedy (temperature 0); other values map as given, even where transformers would
     * read a missing `do_sample` as greedy.
     */
    public val fromBaseModel: Sampling?,
    /** The base model read for [fromBaseModel] (the repo's `base_model:quantized:` tag), or null when it has none. */
    public val baseModel: String?,
) {
    override fun toString(): String =
        "RecommendedSampling(fromFile=$fromFile, fromBaseModel=$fromBaseModel, baseModel=$baseModel)"
}
