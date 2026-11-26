package com.suhel.llamabro.demo.ui.server

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.suhel.llamabro.demo.models.Format
import com.suhel.llamabro.demo.models.LibraryModel
import com.suhel.llamabro.demo.models.Names
import com.suhel.llamabro.demo.server.LogLine
import com.suhel.llamabro.demo.server.ServerMode
import com.suhel.llamabro.demo.server.ServingState
import com.suhel.llamabro.demo.server.Thermal
import com.suhel.llamabro.hub.HfModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.util.Locale

/** Server mode as the Server page reads and drives it: [ServerMode] in the app, a fake in the tests. */
interface ServerControl {
    val state: Flow<ServingState>

    /** The model being served or loading to be; null when off. */
    val served: Flow<HfModel?>
    val log: Flow<List<LogLine>>

    /** The key clients send; null until the first start makes one. */
    val key: Flow<String?>

    suspend fun start(model: HfModel, slots: Int)

    suspend fun stop()

    /** A new key: clients connected with the old one are dropped. */
    suspend fun renewKey()
}

/** What the page says about the phone: its Wi-Fi [addresses], whether it is [charging], and its [thermal] state. */
class PhoneFacts(val addresses: Flow<List<String>>, val charging: Flow<Boolean>, val thermal: Flow<Thermal?>)

/** The Server page in each of the design's states (docs/design/server.md §3). */
sealed interface ServerPage {
    /** Not serving: the downloaded [models], the one [chosen] (null with none), and the [slots] to load it with. */
    data class Off(
        val models: List<ServeChoice>,
        val chosen: HfModel?,
        val slots: Int,
        val slotChoices: List<Int> = ServerMode.SLOTS.sorted(),
    ) : ServerPage

    /** [name] is loading; [progress] is the load's, 0 to 1, once it reports any. */
    data class Starting(val name: String, val progress: Float?) : ServerPage

    /**
     * [name] answers at each of [urls] (none when the phone has no Wi-Fi address) and [localName]; [qr] holds the
     * first URL and the key; [speed] is the last request's, [heat] the phone's thermal state.
     */
    data class Serving(
        val name: String,
        val urls: List<String>,
        val localName: String,
        val qr: String?,
        val key: KeyView,
        val slots: Int,
        val speed: String?,
        val heat: String?,
        val charging: Boolean,
        val log: List<LogRow>,
        val snippets: List<SnippetView>,
    ) : ServerPage

    /** The model didn't load or no port was free: [reason] says which. */
    data class Failed(val reason: String) : ServerPage
}

/** A downloaded model the server can serve: its [name] and the quantisation and size telling its files apart. */
data class ServeChoice(val key: HfModel, val name: String, val detail: String)

/** The key as its row shows it ([shown]: masked unless [revealed]) and as Copy puts it on the clipboard ([copy]). */
data class KeyView(val shown: String, val copy: String, val revealed: Boolean)

/** A connect snippet as its tab shows it (the key masked unless revealed) and as Copy copies it, key and all. */
data class SnippetView(val tool: ConnectTool, val shown: String, val copy: String)

/**
 * The Server page's state, derived from server mode, the library and the phone; the person's choices (the model, the
 * slots, the key shown) are its only inputs. Start, stop and a new key run on [background], the app's scope, so leaving
 * the page never cancels them halfway.
 */
class ServerViewModel(
    private val server: ServerControl,
    private val library: Flow<List<LibraryModel>>,
    phone: PhoneFacts,
    private val background: CoroutineScope,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val locale: Locale = Locale.getDefault(),
) : ViewModel() {
    // the person's input
    private val chosen = MutableStateFlow<HfModel?>(null)
    private val slots = MutableStateFlow(1)
    private val revealed = MutableStateFlow(false)

    private class Choices(val chosen: HfModel?, val slots: Int, val revealed: Boolean)

    private class Phone(val addresses: List<String>, val charging: Boolean, val thermal: Thermal?)

    private class Server(val state: ServingState, val served: HfModel?, val log: List<LogLine>, val key: String?)

    private val serverNow = combine(server.state, server.served, server.log, server.key, ::Server)
    private val choices = combine(chosen, slots, revealed, ::Choices)
    private val phoneNow = combine(phone.addresses, phone.charging, phone.thermal, ::Phone)

    /** The page; null until its sources are read. */
    val page: StateFlow<ServerPage?> =
        combine(serverNow, library, choices, phoneNow) { s, models, c, p -> pageOf(s, models, c, p) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), null)

    fun choose(model: HfModel) {
        chosen.value = model
    }

    fun setSlots(count: Int) {
        slots.value = count
    }

    fun reveal(shown: Boolean) {
        revealed.value = shown
    }

    /** Serves the model chosen (the default one until the person picks) with the slots chosen. */
    fun start() {
        background.launch {
            val model = chosen.value ?: preferred(library.first()) ?: return@launch
            server.start(model, slots.value)
        }
    }

    /** Failed's Try again: the same start once more. */
    fun retry() = start()

    fun stop() {
        background.launch { server.stop() }
    }

    fun renewKey() {
        background.launch { server.renewKey() }
    }

    private fun pageOf(s: Server, models: List<LibraryModel>, c: Choices, p: Phone): ServerPage =
        when (val state = s.state) {
            is ServingState.Off -> {
                ServerPage.Off(
                    models.map(::choiceOf),
                    c.chosen?.takeIf { key -> models.any { it.key == key } } ?: preferred(models),
                    c.slots,
                )
            }

            is ServingState.Starting -> {
                ServerPage.Starting(s.served?.let(Names::of).orEmpty(), state.progress)
            }

            is ServingState.Serving -> {
                serving(state, s, c.revealed, p)
            }

            is ServingState.Failed -> {
                ServerPage.Failed(state.reason)
            }
        }

    private fun serving(state: ServingState.Serving, s: Server, revealed: Boolean, p: Phone): ServerPage.Serving {
        val key = s.key.orEmpty()
        val shownKey = if (revealed) key else ServerFormat.MASK
        val urls = p.addresses.map { ServerFormat.url(it, state.port) }
        val first = urls.firstOrNull()
        val id = state.model.toString()
        val snippets =
            first
                ?.let { url ->
                    ConnectSnippets.of(url, shownKey, id).zip(ConnectSnippets.of(url, key, id)) { shown, copy ->
                        SnippetView(shown.tool, shown.code, copy.code)
                    }
                }.orEmpty()
        return ServerPage.Serving(
            name = Names.of(state.model),
            urls = urls,
            localName = ServerFormat.localName(state.port),
            qr = first?.let { ConnectSnippets.qrPayload(it, key) },
            key = KeyView(shownKey, key, revealed),
            slots = state.slots,
            speed = ServerFormat.speed(s.log, locale),
            heat = p.thermal?.let(ServerFormat::heat),
            charging = p.charging,
            log = s.log.take(LOG_ROWS).map { ServerFormat.logRow(it, zone, locale) },
            snippets = snippets,
        )
    }

    private fun choiceOf(model: LibraryModel) =
        ServeChoice(
            model.key,
            model.name,
            listOfNotNull(model.key.tag, Format.size(model.sizeBytes)).joinToString(" · "),
        )

    /** The model for new chats, else the first on the phone. */
    private fun preferred(models: List<LibraryModel>): HfModel? =
        (
            models.firstOrNull { it.isDefault }
                ?: models.firstOrNull()
        )?.key

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L

        /** The log's newest rows the page lists; the server keeps more. */
        const val LOG_ROWS = 20
    }
}
