package com.suhel.llamabro.hub

/** The files selected from one immutable Hub commit. */
public class HubPlan internal constructor(
    /** Requested model reference. */
    public val model: HfModel,
    /** Immutable repository commit from the Hub. */
    public val commit: String,
    /** Selected model and sidecar files in load order. */
    public val files: List<HubFile>,
    /** True when the Hub was unavailable and the plan came from the local cache. */
    public val offline: Boolean,
    /** Selection notes, including unsupported upstream presets. */
    public val notes: List<String>,
) {
    /** Total size of the selected files. */
    public val bytesTotal: Long = files.sumOf { it.sizeBytes }

    internal var partialBytes: Long = 0

    /** Bytes still missing, allowing for completed files and partial downloads. */
    public val bytesToDownload: Long
        get() = (files.filterNot { it.cached }.sumOf { it.sizeBytes } - partialBytes).coerceAtLeast(0)
}

/** One selected repository file and its intended SDK role. */
public class HubFile internal constructor(
    /** Repository-relative file path. */
    public val path: String,
    /** How the SDK will load this file. */
    public val role: Role,
    /** Full file length. */
    public val sizeBytes: Long,
    /** Published SHA-256 or git blob SHA-1, null for offline cache entries. */
    public val oid: String?,
    /** True when a complete snapshot entry already exists. */
    public val cached: Boolean,
) {
    internal var sourceRepo: String = ""
    internal var sourceCommit: String = ""

    /** Roles the SDK loads from a Hub plan. */
    public enum class Role { Model, Mmproj, Draft }
}
