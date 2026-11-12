package com.suhel.llamabro.demo.ui.models

import androidx.compose.runtime.Immutable
import com.suhel.llamabro.demo.models.Catalog
import com.suhel.llamabro.demo.models.CatalogEntry
import com.suhel.llamabro.demo.models.SearchModel
import com.suhel.llamabro.hub.HfModel

/**
 * A model as a list row (a suggestion or a search result). [groupKey] identifies the model across both: a download
 * started from a row shows its progress on that row. [maker] is known only for the catalog's models: for any other, the
 * repo's owner is the uploader, not the maker, so the sheet shows the repo alone.
 */
@Immutable
data class ModelRow(
    val groupKey: String,
    val name: String,
    val repo: String,
    val line: String,
    val sees: Boolean,
    val thinks: Boolean,
    val downloads: Long?,
    val maker: String?,
    val catalog: CatalogEntry?,
) {
    companion object {
        fun of(entry: CatalogEntry) =
            ModelRow(
                groupKey =
                    entry.key.repo
                        .substringAfter('/')
                        .removeSuffix("-GGUF")
                        .lowercase(),
                name = entry.realName,
                repo = entry.key.repo,
                line = entry.line,
                sees = entry.sees,
                thinks = entry.thinks,
                downloads = null,
                maker = entry.maker,
                catalog = entry,
            )

        fun of(model: SearchModel) =
            ModelRow(
                groupKey = model.groupKey,
                name = model.displayName,
                repo = model.repo,
                line = model.line,
                sees = model.sees,
                thinks = model.catalog?.thinks ?: false,
                downloads = model.downloads,
                maker = model.catalog?.maker,
                catalog = model.catalog,
            )

        /** The row of a model known only by its key (the chat's own): the catalog's entry, else the repo alone. */
        fun ofKey(key: HfModel, name: String): ModelRow =
            Catalog.byKey(key)?.let(::of)
                ?: ModelRow(
                    groupKey =
                        key.repo
                            .substringAfter('/')
                            .removeSuffix("-GGUF")
                            .lowercase(),
                    name = name,
                    repo = key.repo,
                    line = "",
                    sees = false,
                    thinks = false,
                    downloads = null,
                    maker = null,
                    catalog = null,
                )

        /** The five suggestions, in the order demo design §4.3 gives. */
        val suggestions: List<ModelRow> get() = Catalog.suggested.map(::of)
    }
}
