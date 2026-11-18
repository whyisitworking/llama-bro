package com.suhel.llamabro.demo.ui.firstrun

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.suhel.llamabro.demo.data.ChatRepository
import com.suhel.llamabro.demo.data.SettingsRepository
import com.suhel.llamabro.demo.models.Catalog
import com.suhel.llamabro.demo.models.CatalogEntry
import com.suhel.llamabro.demo.models.DeviceFacts
import com.suhel.llamabro.demo.models.FitView
import com.suhel.llamabro.demo.models.Fits
import com.suhel.llamabro.demo.models.Format
import com.suhel.llamabro.demo.models.ModelRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId

/** The greeting by the hour of the day. */
object Greetings {
    private val MORNING = 5 until 12
    private val AFTERNOON = 12 until 17
    private val EVENING = 17 until 22

    fun forHour(hour: Int): String =
        when (hour) {
            in MORNING -> "Good morning"
            in AFTERNOON -> "Good afternoon"
            in EVENING -> "Good evening"
            else -> "Hello"
        }
}

/** A first-run pick: the catalog entry, how it runs here (quick, from its shipped sizes) and the size to download. */
@Immutable
data class PickCard(val entry: CatalogEntry, val fit: FitView, val sizeText: String)

/**
 * A pick opened in its sheet: the fit from its shipped sizes, the same as its card's (D101); [memoryText] and
 * [downloadText] are the third and fourth fact.
 */
@Immutable
data class PickSheetState(val entry: CatalogEntry, val fit: FitView, val memoryText: String, val downloadText: String)

/**
 * The first screen (demo design §4.1): the greeting, three picks tinted without any network, and a pick's sheet. The
 * two states are derived: the picks from the phone's facts, the sheet from which pick is open. [get] is the one
 * command.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FirstRunViewModel(
    private val facts: suspend () -> DeviceFacts,
    private val chats: ChatRepository,
    private val settings: SettingsRepository,
    private val models: ModelRepository,
    clock: () -> Long = System::currentTimeMillis,
    zone: ZoneId = ZoneId.systemDefault(),
) : ViewModel() {
    val greeting: String = Greetings.forHour(Instant.ofEpochMilli(clock()).atZone(zone).hour)

    /** Null until the phone's facts are read (an instant, no network). */
    val picks: StateFlow<List<PickCard>?> =
        flow {
            val device = facts()
            emit(
                Catalog.picks.map {
                    PickCard(
                        it,
                        Fits.quick(it, device),
                        Format.sizeInGb(it.modelBytes + it.mmprojBytes),
                    )
                },
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), null)

    private val opened = MutableStateFlow<CatalogEntry?>(null)

    /** The open pick's sheet, from the model's shipped sizes and the phone's facts; null while none is open. */
    val sheet: StateFlow<PickSheetState?> =
        opened
            .flatMapLatest { entry ->
                if (entry == null) flowOf(null) else sheetOf(entry)
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), null)

    fun open(entry: CatalogEntry) {
        opened.value = entry
    }

    fun close() {
        opened.value = null
    }

    private fun sheetOf(entry: CatalogEntry): Flow<PickSheetState> =
        flow {
            val total = entry.modelBytes + entry.mmprojBytes
            val download = "${Format.sizeInGb(total)} · ${Format.downloadTime(total)}"
            emit(PickSheetState(entry, Fits.quick(entry, facts()), memory(total), download))
        }

    private val chatEvents = Channel<Long>(Channel.BUFFERED)

    /** The chats [get] made, once each: the screen opens them. */
    val chatOpened: Flow<Long> = chatEvents.receiveAsFlow()

    /** Starts the download, makes the chat for [entry], marks first run seen and announces the chat on [chatOpened]. */
    fun get(entry: CatalogEntry) {
        viewModelScope.launch {
            models.get(entry.key)
            settings.setLastModel(entry.key.toString())
            val id = chats.createChat(entry.key.toString())
            settings.markFirstRunSeen()
            chatEvents.send(id)
        }
    }

    /** The user went to search instead: first run doesn't come back. */
    fun markSeen() {
        viewModelScope.launch { settings.markFirstRunSeen() }
    }

    private fun memory(bytes: Long) = "Uses about ${Format.sizeInGb(bytes)}"

    private companion object {
        const val STOP_TIMEOUT = 5_000L
    }
}
