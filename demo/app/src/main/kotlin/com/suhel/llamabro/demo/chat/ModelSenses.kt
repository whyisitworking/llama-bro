package com.suhel.llamabro.demo.chat

import com.suhel.llamabro.ModelInfo
import com.suhel.llamabro.demo.data.ModelSenses
import com.suhel.llamabro.demo.data.SettingsRepository
import com.suhel.llamabro.demo.models.Catalog
import com.suhel.llamabro.demo.models.CatalogEntry
import com.suhel.llamabro.hub.HfModel
import com.suhel.llamabro.hub.ModelCapabilities
import kotlinx.coroutines.flow.first
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * What a chat shows its model can do: the Think chip ([thinks]), photos ([sees]), the mic ([hears], D76) and voice
 * mode ([talks], D100).
 */
internal data class Senses(val thinks: Boolean, val sees: Boolean, val hears: Boolean, val talks: Boolean)

/**
 * What [key]'s model can do, from the best word there is: the loaded model's, when [host] has it loaded; else the
 * [remembered] word of its header or last load (D155); else the catalog's for any quant of its repo; else nothing.
 * It [Senses.talks] when its load spoke, or when it is in [talking] (those that spoke on their last load) or the
 * catalog says so; a model that talks hears.
 */
internal fun sensesOf(key: HfModel, host: HostState, remembered: ModelSenses?, talking: Set<String>): Senses {
    val catalog = Catalog.byRepo(key.repo)
    val info = (host as? HostState.Ready)?.takeIf { it.key == key }?.model?.info
    val known: ModelSenses
    val talks: Boolean
    if (info != null) {
        known = info.senses(catalog)
        talks = info.supportsAudio && info.supportsSpeech
    } else {
        known = remembered ?: catalog?.let { ModelSenses(it.thinks, it.sees, hears = it.talks) } ?: NO_SENSES
        talks = key.toString() in talking || catalog?.talks == true
    }
    return Senses(known.thinks, known.sees, hears = known.hears || talks, talks = talks)
}

private val NO_SENSES = ModelSenses(thinks = false, sees = false, hears = false)

/** What the header says the model can do, by the rule a load's [ModelInfo] follows ([ModelInfo.senses]). */
internal fun ModelCapabilities.senses(catalog: CatalogEntry?): ModelSenses =
    ModelSenses(
        thinks = Thinking.canThink(chatTemplate, emptyMap(), catalog),
        sees = takesImages,
        hears = takesAudio,
        // an embedding or rerank model's GGUF has no chat template: the one word on it the header read gives
        chats = chatTemplate != null,
    )

/** What the loaded model can do: the final word. */
internal fun ModelInfo.senses(catalog: CatalogEntry?): ModelSenses =
    ModelSenses(
        thinks = Thinking.canThink(chatTemplate, templateCaps, catalog),
        sees = supportsVision,
        hears = supportsAudio,
    )

/**
 * Reads a model's senses from its header ([read], D155) once it is on the phone, and stores them, so its chat shows
 * the Think chip, photos and the mic before the first load. A model whose senses are already stored (a load's, or an
 * earlier read's) is not read again; nor, in this process, is one whose header is corrupt.
 */
class SensesReader(private val settings: SettingsRepository, private val read: suspend (HfModel) -> ModelSenses?) {
    /** Models whose header isn't GGUF: it stays so until the file changes, so reading it again only repeats that. */
    private val corrupt = ConcurrentHashMap.newKeySet<HfModel>()

    /**
     * Reads and stores [key]'s senses unless they are known. A header that can't be read stores nothing: the chat
     * shows the catalog's word, or none, until the model loads. A read that was cancelled, found no copy or met an IO
     * error is tried again by the next call; two reads at once are harmless, since only the first store lands.
     */
    suspend fun ensure(key: HfModel) {
        if (key in corrupt || key.toString() in settings.modelSenses.first()) return
        val senses =
            try {
                read(key)
            } catch (ignored: IllegalArgumentException) {
                // a corrupt header: the load says what the model can do, or fails and says why
                corrupt += key
                null
            } catch (ignored: IOException) {
                // the file went or changed mid-read (a delete, a new download): the next call reads it again
                null
            } ?: return
        // a load may have recorded its own, final word while the header was read
        settings.setSenses(key.toString(), senses, ifUnknown = true)
    }
}
