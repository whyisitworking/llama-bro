package com.suhel.llamabro.internal

/** The client's side of a realtime conversation (D97): [send] answers false once it has closed. */
internal interface RealtimeLink {
    fun send(message: String): Boolean
}
