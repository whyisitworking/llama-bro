package com.suhel.llamabro.hub.internal

import com.suhel.llamabro.hub.HubPlan
import com.suhel.llamabro.hub.HfModel
import com.suhel.llamabro.hub.HubFile
import com.suhel.llamabro.hub.PlanOptions
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** The SDK's own plan record, separate from upstream's cache tree (C.5). */
internal class PlanStore(private val cacheDir: File, private val storage: Storage) {
    internal data class FileRecord(val path: String, val role: HubFile.Role, val size: Long, val oid: String?)
    internal data class Record(val commit: String, val files: List<FileRecord>)

    fun read(model: HfModel, options: PlanOptions): Record? {
        val file = destination(key(model, options))
        if (!file.isFile) return null
        return try {
            val json = Json.parseToJsonElement(file.readText()).jsonObject
            if (json["version"]?.jsonPrimitive?.content != "1" ||
                json["model"]?.jsonPrimitive?.content != model.toString()) return null
            val commit = json["commit"]?.jsonPrimitive?.content ?: return null
            val files = json["files"]?.jsonArray?.map { item ->
                val value = item.jsonObject
                FileRecord(
                    value.getValue("path").jsonPrimitive.content,
                    HubFile.Role.valueOf(value.getValue("role").jsonPrimitive.content),
                    value.getValue("size").jsonPrimitive.longOrNull ?: return null,
                    value["oid"]?.jsonPrimitive?.content,
                )
            } ?: return null
            Record(commit, files)
        } catch (_: Exception) { null }
    }

    fun key(model: HfModel, options: PlanOptions): String {
        val draft = options.draft?.from?.toString() ?: if (options.draft != null) "same" else "none"
        return "$model|mmproj=${options.mmproj}|draft=$draft"
    }

    fun write(plan: HubPlan, options: PlanOptions) {
        val key = key(plan.model, options)
        val destination = destination(key)
        val content = buildJsonObject {
            put("version", 1)
            put("model", plan.model.toString())
            put("commit", plan.commit)
            put("files", buildJsonArray {
                for (file in plan.files) add(buildJsonObject {
                    put("path", file.path)
                    put("role", file.role.name)
                    put("size", file.sizeBytes)
                    put("oid", file.oid)
                })
            })
        }.toString()
        writeAtomic(destination, content.toByteArray(), storage)
    }

    private fun destination(key: String): File {
        val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray())
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
        Files.move(temporary.toPath(), destination.toPath(),
            StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        storage.fsyncDir(directory)
    } finally {
        temporary.delete()
    }
}
