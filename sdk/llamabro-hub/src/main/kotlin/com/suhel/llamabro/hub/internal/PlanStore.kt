package com.suhel.llamabro.hub.internal

import com.suhel.llamabro.hub.HfModel
import com.suhel.llamabro.hub.HubFile
import com.suhel.llamabro.hub.HubPlan
import com.suhel.llamabro.hub.PlanOptions
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

/**
 * The SDK's own records of a selection, separate from upstream's cache tree (C.5): in [folder] `plans`, the last plan
 * made online (what a download fetches); in `installed`, the copy whose download completed, which is what loads (D64).
 */
internal class PlanStore(
    private val cacheDir: File,
    private val storage: Storage,
    private val folder: String = PLANS,
) {
    /** One file; [repo] and [commit] name its snapshot (null in records written before they were kept). */
    internal data class FileRecord(
        val path: String,
        val role: HubFile.Role,
        val size: Long,
        val oid: String?,
        val repo: String? = null,
        val commit: String? = null,
    )

    internal data class Record(val commit: String, val files: List<FileRecord>)

    fun read(model: HfModel, options: PlanOptions): Record? {
        val file = destination(key(model, options))
        if (!file.isFile) return null
        return try {
            parse(Json.parseToJsonElement(file.readText()).jsonObject, model)
        } catch (_: Exception) {
            null // a record that doesn't read is a record that isn't there
        }
    }

    /**
     * Every record of [model] whatever the options it was made with, read from the folder (a record's name is a digest
     * of its key, so the model is matched inside).
     */
    fun readAll(model: HfModel): List<Record> =
        File(cacheDir, ".llamabro/$folder")
            .listFiles { file -> file.isFile && file.name.endsWith(".json") }
            .orEmpty()
            .sortedBy { it.name }
            .mapNotNull { file ->
                try {
                    parse(Json.parseToJsonElement(file.readText()).jsonObject, model)
                } catch (_: Exception) {
                    null
                }
            }

    /** The record in [json] if it is a version 1 record of [model] with every file readable. */
    private fun parse(json: JsonObject, model: HfModel): Record? =
        parseAny(json)?.takeIf { it.first.toString() == model.toString() }?.second

    fun key(model: HfModel, options: PlanOptions): String {
        val draft = options.draft?.from?.toString() ?: if (options.draft != null) "same" else "none"
        return "$model|mmproj=${options.mmproj}|draft=$draft"
    }

    fun write(plan: HubPlan, options: PlanOptions) {
        val files =
            plan.files.map {
                FileRecord(it.path, it.role, it.sizeBytes, it.oid, it.sourceRepo, it.sourceCommit)
            }
        write(plan.model, options, Record(plan.commit, files))
    }

    fun write(model: HfModel, options: PlanOptions, record: Record) {
        val content =
            buildJsonObject {
                put("version", 1)
                put("model", model.toString())
                put("commit", record.commit)
                put(
                    "files",
                    buildJsonArray {
                        for (file in record.files) {
                            add(
                                buildJsonObject {
                                    put("path", file.path)
                                    put("role", file.role.name)
                                    put("size", file.size)
                                    put("oid", file.oid)
                                    file.repo?.let { put("repo", it) }
                                    file.commit?.let { put("commit", it) }
                                },
                            )
                        }
                    },
                )
            }.toString()
        writeAtomic(destination(key(model, options)), content.toByteArray(), storage)
    }

    private fun destination(key: String): File {
        val digest =
            MessageDigest
                .getInstance("SHA-256")
                .digest(key.toByteArray())
                .joinToString("") { "%02x".format(it) }
        return File(cacheDir, ".llamabro/$folder/$digest.json")
    }

    companion object {
        const val PLANS = "plans"
        const val INSTALLED = "installed"

        /** Every readable record in [folder] of [cacheDir], whatever its model or options, with its model. */
        fun readEvery(cacheDir: File, folder: String): List<Pair<HfModel, Record>> =
            File(cacheDir, ".llamabro/$folder")
                .listFiles { file -> file.isFile && file.name.endsWith(".json") }
                .orEmpty()
                .sortedBy { it.name }
                .mapNotNull { file ->
                    try {
                        parseAny(Json.parseToJsonElement(file.readText()).jsonObject)
                    } catch (_: Exception) {
                        null // a record that doesn't read is a record that isn't there
                    }
                }

        /** The record in [json] and its model, if it is a version 1 record with every file readable. */
        private fun parseAny(json: JsonObject): Pair<HfModel, Record>? {
            if (json["version"]?.jsonPrimitive?.content != "1") return null
            val model = json["model"]?.jsonPrimitive?.content?.let(HfModel::parse) ?: return null
            val commit = json["commit"]?.jsonPrimitive?.content
            val files = json["files"]?.jsonArray?.map(::fileRecord)?.takeIf { records -> records.all { it != null } }
            if (commit == null || files == null) return null
            return model to Record(commit, files.filterNotNull())
        }

        private fun fileRecord(item: JsonElement): FileRecord? {
            val value = item.jsonObject
            val path = value.getValue("path").jsonPrimitive.content
            val role = HubFile.Role.valueOf(value.getValue("role").jsonPrimitive.content)
            val size = value.getValue("size").jsonPrimitive.longOrNull ?: return null
            return FileRecord(
                path,
                role,
                size,
                value["oid"]?.jsonPrimitive?.contentOrNull,
                value["repo"]?.jsonPrimitive?.contentOrNull,
                value["commit"]?.jsonPrimitive?.contentOrNull,
            )
        }
    }
}

/** Atomically replaces a small cache metadata file after syncing its contents. */
internal fun writeAtomic(destination: File, bytes: ByteArray, storage: Storage) {
    val directory = requireNotNull(destination.parentFile)
    directory.mkdirs()
    val temporary = File(directory, "${destination.name}.tmp-${UUID.randomUUID()}")
    try {
        FileOutputStream(temporary).use { output ->
            output.write(bytes)
            output.fd.sync()
        }
        Files.move(
            temporary.toPath(),
            destination.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
        storage.fsyncDir(directory)
    } finally {
        temporary.delete()
    }
}
