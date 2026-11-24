package com.suhel.llamabro.demo.ui.chat

import kotlinx.coroutines.sync.Mutex

/**
 * The message bar's one send at a time, typed or voice, and the draft pictures it carries. From the moment the send
 * takes them until it ends they are the send's: nothing that empties the bar meanwhile (Back, a starter, an edit, a
 * picture removed) deletes the files the stored message is about to name. When it ends, those of them nobody holds
 * are deleted: the ones past the four a message takes, and the ones the bar dropped that the send didn't store (a
 * stored one has left the draft folder, where deleting finds nothing).
 */
class Sending(
    /** The pictures in the bar now. */
    private val inBar: () -> List<String>,
    /** Deletes draft pictures. */
    private val discard: (List<String>) -> Unit,
) {
    private val lock = Mutex()
    private val guard = Any()
    private var carried: List<String> = emptyList()

    /** The bar is gone: its draft is no one's, so the send under way deletes what it carried when it ends. */
    private var closed = false

    /** Starts a send, or false while another is under way. */
    fun tryStart(): Boolean = lock.tryLock()

    /** The send under way takes [pictures]. */
    fun carry(pictures: List<String>) = synchronized(guard) { carried = pictures }

    /** Ends the send [tryStart] started, deleting what it carried that nobody holds now. */
    fun end() {
        val gone =
            synchronized(guard) {
                val bar = if (closed) emptySet() else inBar().toSet()
                carried.filterNot { it in bar }.also { carried = emptyList() }
            }
        lock.unlock()
        if (gone.isNotEmpty()) discard(gone)
    }

    /**
     * [names] less the pictures the send under way carries: what may be deleted now. With [closing] the bar is going,
     * and the send deletes what it carried itself when it ends.
     */
    fun notCarried(names: List<String>, closing: Boolean = false): List<String> =
        synchronized(guard) {
            if (closing) closed = true
            names.filterNot { it in carried }
        }
}
