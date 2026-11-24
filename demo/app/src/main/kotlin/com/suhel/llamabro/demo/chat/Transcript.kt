package com.suhel.llamabro.demo.chat

import com.suhel.llamabro.demo.data.MessageKind
import com.suhel.llamabro.demo.data.MessageState
import com.suhel.llamabro.demo.data.NewMessage
import com.suhel.llamabro.demo.data.StoredMessage
import com.suhel.llamabro.hub.HfModel

/** The divider line for a switch to [key]. */
internal fun switchNotice(key: HfModel, name: String, now: Long) =
    NewMessage(MessageKind.Switch, "Switched to $name", modelKey = key.toString(), sentAt = now)

/** Whether choosing a model now switches from another one: only once the chat has a question in it. */
internal fun isSwitch(rows: List<StoredMessage>): Boolean = rows.any { it.kind == MessageKind.User }

/**
 * After a cut at [position] (an edit, a regenerate): the model to say "Switched to" for again, or null. The cut can
 * remove the divider that named the model answering, while the model in use stays the latest one; when the last
 * divider or reply that is left names another model than [current], the transcript must say so, or a reply would
 * stand under a divider for a model that did not write it.
 */
internal fun modelToRestate(rows: List<StoredMessage>, position: Int, current: HfModel?): HfModel? {
    val answering =
        rows
            .lastOrNull {
                it.position < position &&
                    (it.kind == MessageKind.Switch || it.kind == MessageKind.Assistant) &&
                    it.modelKey != null
            }?.modelKey
    return current?.takeIf { answering != null && answering != it.toString() }
}

/** Whether [reply] can be continued in a chat on [key]: cut at its length limit, and written by that model (D60). */
internal fun canContinue(reply: StoredMessage, key: HfModel): Boolean =
    reply.kind == MessageKind.Assistant && reply.state == MessageState.Cut && reply.modelKey == key.toString()
