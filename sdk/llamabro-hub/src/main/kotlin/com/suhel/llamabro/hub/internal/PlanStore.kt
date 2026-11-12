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

/** The SDK's own plan record, separate from upstream's cache tree (C.5). */
internal class PlanStore(private val cacheDir: File, private val storage: Storage) {
    internal data class FileRecord(val path: String, val role: HubFile.Role, val size: Long, val oid: String?)

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

    /** The record in [json] if it is a version 1 record of [model] with every file readable. */
    private fun parse(json: JsonObject, model: HfModel): Record? {
        val ours =
            json["version"]?.jsonPrimitive?.content == "1" && json["model"]?.jsonPrimitive?.content == model.toString()
        val commit = json["commit"]?.jsonPrimitive?.content
        val files = json["files"]?.jsonArray?.map(::fileRecord)?.takeIf { records -> records.all { it != null } }
        if (!ours || commit == null || files == null) return null
        return Record(commit, files.filterNotNull())
    }

    private fun fileRecord(item: JsonElement): FileRecord? {
        val value = item.jsonObject
        val path = value.getValue("path").jsonPrimitive.content
        val role = HubFile.Role.valueOf(value.getValue("role").jsonPrimitive.content)
        val size = value.getValue("size").jsonPrimitive.longOrNull ?: return null
        return FileRecord(path, role, size, value["oid"]?.jsonPrimitive?.content)
    }

    fun key(model: HfModel, options: PlanOptions): String {
        val draft = options.draft?.from?.toString() ?: if (options.draft != null) "same" else "none"
        return "$model|mmproj=${options.mmproj}|draft=$draft"
    }

    fun write(plan: HubPlan, options: PlanOptions) {
        val key = key(plan.model, options)
        val destination = destination(key)
        val content =
            buildJsonObject {
                put("version", 1)
                put("model", plan.model.toString())
                put("commit", plan.commit)
                put(
                    "files",
                    buildJsonArray {
                        for (file in plan.files) {
                            add(
                                buildJsonObject {
                                    put("path", file.path)
                                    put("role", file.role.name)
                                    put("size", file.sizeBytes)
                                    put("oid", file.oid)
                                },
                            )
                        }
                    },
                )
            }.toString()
        writeAtomic(destination, content.toByteArray(), storage)
    }

    private fun destination(key: String): File {
        val digest =
            MessageDigest
                .getInstance("SHA-256")
                .digest(key.toByteArray())
                .joinToString("") { "%02x".format(it) }
        return File(cacheDir, ".llamabro/plans/$digest.json")
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
