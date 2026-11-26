package com.suhel.llamabro.demo.ui.models

import androidx.compose.runtime.Immutable
import com.suhel.llamabro.demo.models.Capability
import com.suhel.llamabro.demo.models.Catalog
import com.suhel.llamabro.demo.models.CatalogEntry
import com.suhel.llamabro.demo.models.DownloadRecord
import com.suhel.llamabro.demo.models.Guess
import com.suhel.llamabro.demo.models.LibraryModel
import com.suhel.llamabro.demo.models.Names
import com.suhel.llamabro.demo.models.SearchModel
import com.suhel.llamabro.demo.models.isChatModel
import com.suhel.llamabro.hub.HfModel

/**
 * A row's size: the whole download, the model file and its image reader (docs/design/models-page.md §7). [Exact] when
 * the sizes are known (the catalog's, the file on the phone, a download's record); [About] when it is guessed from the
 * Hub's parameter count (F175), written "about 3.4 GB".
 */
sealed interface SizeText {
    val bytes: Long

    /** The model file's share of [bytes], without its image reader: what decoding reads, so the fit's speed. */
    val modelBytes: Long

    data class Exact(override val bytes: Long, override val modelBytes: Long = bytes) : SizeText

    data class About(override val bytes: Long, override val modelBytes: Long = bytes) : SizeText
}

/**
 * A model as a list row: on the phone, downloading, a suggestion or a Hub result. [groupKey] identifies the model
 * across them. [name] is cleaned (the catalog's, else the repo's without upload tokens); [maker] is the catalog's,
 * else the original's owner through [Names.maker]; null when the Hub names no original (the repo's owner is the
 * uploader, not the maker). [parameters] and [activeParameters] are the Hub's facts a row's fit and [size] are guessed
 * from (F175). [baseModel] is the original whose page the card shows; null when the Hub names none. [key] is the file
 * on the phone or downloading, with [lastUsed] and [isDefault] for one on the phone ([onPhone]); null for a suggestion
 * or a result.
 */
@Immutable
data class ModelEntry(
    val groupKey: String,
    val name: String,
    val repo: String,
    val downloads: Long?,
    val maker: String?,
    val catalog: CatalogEntry?,
    val capabilities: List<Capability> = emptyList(),
    val canChat: Boolean = true,
    val parameters: Long? = null,
    val activeParameters: Long? = null,
    val baseModel: String? = null,
    val size: SizeText? = null,
    val key: HfModel? = null,
    val lastUsed: Long? = null,
    val isDefault: Boolean = false,
    val onPhone: Boolean = false,
    /** Every upload a Hub row stands for ([repo] is the representative), so a duplicate of any of them is caught. */
    val uploads: List<String> = emptyList(),
) {
    companion object {
        fun of(entry: CatalogEntry) =
            ModelEntry(
                groupKey = keyOf(entry.key.repo),
                name = entry.realName,
                repo = entry.key.repo,
                downloads = null,
                maker = entry.maker,
                catalog = entry,
                capabilities = capabilitiesOf(entry),
                parameters = entry.parameters,
                baseModel = entry.baseModel,
                size = SizeText.Exact(entry.modelBytes + entry.mmprojBytes, entry.modelBytes),
            )

        /**
         * A suggestion with the Hub's [facts] for its repo when they are stored: the catalog's own fill the same fields
         * until then, so the row has every line a search row has (audit M5).
         */
        fun suggestion(entry: CatalogEntry, facts: SearchModel?) =
            of(entry).copy(
                downloads = facts?.downloads,
                parameters = entry.parameters,
                maker = entry.maker,
            )

        fun of(model: SearchModel): ModelEntry {
            // a row's size is guessed whenever its parameter count is known, from its GGUF header or else its name
            // so line 2 is "maker · about 4.6 GB", never the maker alone
            val parameters = model.parameters ?: Guess.namedParameters(model.displayName)
            return ModelEntry(
                groupKey = model.groupKey,
                name = model.displayName,
                repo = model.repo,
                downloads = model.downloads,
                maker = Names.makerOf(model.repo, model.maker),
                catalog = model.catalog,
                capabilities = model.capabilities,
                canChat = model.canChat,
                parameters = parameters,
                activeParameters = model.activeParameters,
                baseModel = model.baseModel,
                size =
                    model.catalog?.let { SizeText.Exact(it.modelBytes + it.mmprojBytes, it.modelBytes) }
                        ?: guessed(parameters, model.sees),
                uploads = model.repos,
            )
        }

        /** A model on the phone: its own files' size, when it was last used, whether new chats use it. */
        fun of(model: LibraryModel) =
            ofKey(model.key, model.name).copy(
                maker = Catalog.byRepo(model.key.repo)?.maker,
                // the one size: the file on the phone and its image reader; the model file alone for the fit's speed
                size = SizeText.Exact(model.totalBytes, model.sizeBytes),
                key = model.key,
                canChat = isChatModel(model.key),
                lastUsed = model.lastUsed,
                isDefault = model.isDefault,
                onPhone = true,
            )

        /**
         * A download: the name, maker and whole size the person chose it with ([record], kept in Room so they show from
         * the first frame after a restart), else the catalog's for the same file.
         */
        fun downloading(key: HfModel, record: DownloadRecord?): ModelEntry {
            val known = ofKey(key, Names.of(key))
            return known.copy(
                name = record?.name ?: Names.of(key),
                canChat = isChatModel(key),
                // the catalog's maker wins over the one recorded (P2-3); the recorded one through the one map
                maker = Catalog.byRepo(key.repo)?.maker ?: record?.maker?.let(Names::maker) ?: known.maker,
                // the model file alone for the fit's speed, as recorded at Get; a record from before
                // version 6 has none: the catalog's for its own file, else the total
                size =
                    record?.totalBytes?.takeIf { it > 0 }?.let { total ->
                        val model =
                            record.modelBytes.takeIf { it > 0 }
                                ?: known.size?.modelBytes?.takeIf { known.catalog?.key == key }
                                ?: total
                        SizeText.Exact(total, model)
                    } ?: known.size,
                key = key,
            )
        }

        /** The row of a model known only by its key (the chat's own): the catalog's entry, else the repo alone. */
        fun ofKey(key: HfModel, name: String): ModelEntry =
            Catalog.byKey(key)?.let(::of)
                ?: ModelEntry(
                    groupKey = keyOf(key.repo),
                    name = name,
                    repo = key.repo,
                    downloads = null,
                    maker = null,
                    catalog = null,
                )

        /**
         * The list key of [entry]'s row (docs/design/models-page.md §7): the file for a row that has one, the catalog's
         * file for a suggestion, so Get moves the suggestion to Downloading and a landing moves it to On this phone as
         * one item; two files of one repo on the phone are two rows. A row with no file is keyed by its repo.
         */
        fun listKey(entry: ModelEntry): String =
            (entry.key ?: entry.catalog?.key)?.toString()?.lowercase() ?: "repo:${entry.repo.lowercase()}"

        private fun keyOf(repo: String) = Names.key(repo.substringAfter('/'))

        /**
         * The Recommended file's size from the parameter count, with the reader for a model that sees (F175); the
         * model file's share of it, from which every fit takes its speed ([Fits.speedBytes]).
         */
        private fun guessed(parameters: Long?, sees: Boolean): SizeText? =
            parameters?.let { p ->
                SizeText.About(
                    Guess.recommendedBytes(p) + if (sees) Guess.readerBytes(p) else 0L,
                    Guess.recommendedBytes(p),
                )
            }

        private fun capabilitiesOf(entry: CatalogEntry): List<Capability> =
            listOfNotNull(
                Capability.Thinks.takeIf { entry.thinks },
                Capability.Talks.takeIf { entry.talks },
                Capability.SeesImages.takeIf { entry.sees },
            )

        /** The six suggestions, in the order demo design §4.3 gives. */
        val suggestions: List<ModelEntry> get() = Catalog.suggested.map(::of)
    }
}
