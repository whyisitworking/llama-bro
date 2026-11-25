package com.suhel.llamabro.demo.ui.models

import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.suhel.llamabro.demo.models.FitView
import com.suhel.llamabro.demo.models.SizeTier
import com.suhel.llamabro.demo.ui.Screens
import com.suhel.llamabro.hub.HfModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The key of a screen's [CardStack] among its back-stack entry's view models. */
internal const val CARD_STACK_KEY = "card-stack"

/**
 * The cards a model's sheet holds (D156): the one it opened on, then each model a link on a page opened over it. A view
 * model of the screen's back-stack entry, so a rotation keeps the stack and, in [stores], each card's view model. A
 * card's store is cleared when the card is popped and when the sheet closes, never because its composition went: a
 * card under another isn't composed, and keeps its state for Back.
 */
class CardStack(private val stores: SheetStores) : ViewModel() {
    // user input: the cards opened, the root first
    private val stack = MutableStateFlow<List<ModelEntry>>(emptyList())

    /** The cards from the root up; the last is the one shown. Empty while no sheet is open. */
    val cards: StateFlow<List<ModelEntry>> = stack.asStateFlow()

    /** Opens the sheet on [root], in place of any stack left before. */
    fun open(root: ModelEntry) {
        clearFrom(0)
        stack.value = listOf(root)
    }

    /** Opens [entry]'s card over the shown one; a model already in the stack pops back to its card instead. */
    fun push(entry: ModelEntry) {
        val at = stack.value.indexOfFirst { it.repo.equals(entry.repo, ignoreCase = true) }
        if (at >= 0) popTo(at) else stack.value += entry
    }

    /** Back: the shown card goes and the one under it shows. False on the root, which only a close removes. */
    fun pop(): Boolean {
        if (stack.value.size <= 1) return false
        popTo(stack.value.size - 2)
        return true
    }

    /** The sheet closed: every card's view model stops its work. */
    fun close() {
        clearFrom(0)
        stack.value = emptyList()
    }

    /** The store of [entry]'s card at [depth]: one model twice in a stack (never at once) gets two. */
    fun storeKey(depth: Int, entry: ModelEntry): String = "sheet:$depth:${entry.repo}"

    /** The owner of [entry]'s card's view model at [depth]: its store lives until the card is popped or closed. */
    fun owner(depth: Int, entry: ModelEntry): ViewModelStoreOwner = stores.owner(storeKey(depth, entry))

    private fun popTo(depth: Int) {
        clearFrom(depth + 1)
        stack.value = stack.value.take(depth + 1)
    }

    private fun clearFrom(depth: Int) {
        stack.value.drop(depth).forEachIndexed { i, entry -> stores.clear(storeKey(depth + i, entry)) }
    }
}

/**
 * Where a card sits in its stack: the [owner] of its view model, the way [onBack] (null on the root), and [onRelated],
 * which opens a linked model's card over it.
 */
@Stable
class CardPlace(val owner: ViewModelStoreOwner, val onBack: (() -> Unit)?, val onRelated: (ModelEntry) -> Unit)

/**
 * A model's card and the cards its page's links open over it, one at a time in the sheet (D156): Back, the card's own
 * or the system's, returns to the card under it, and only the root's closes the sheet. The stack and every card's state
 * survive a rotation; closing the sheet, or the screen dropping it, clears them. [onStarted], [onChatWith] and
 * [onExactFit] serve every card in the stack ([onExactFit] hears which); [startTier] only the root's.
 */
@Composable
fun ModelCardStack(
    screens: Screens,
    root: ModelEntry,
    onStarted: (HfModel) -> Unit,
    onDismiss: () -> Unit,
    onChatWith: ((HfModel) -> Unit)?,
    onExactFit: (ModelEntry, FitView) -> Unit = { _, _ -> },
    startTier: SizeTier? = null,
) {
    val stores = viewModel { SheetStores() }
    val stack = viewModel(key = CARD_STACK_KEY) { CardStack(stores) }
    val activity = LocalActivity.current
    DisposableEffect(stack, root.repo) {
        // a rotation composes the sheet again over the stack it had
        val open =
            stack.cards.value
                .firstOrNull()
                ?.repo
        if (open != root.repo) stack.open(root)
        onDispose { if (activity?.isChangingConfigurations != true) stack.close() }
    }
    val cards by stack.cards.collectAsStateWithLifecycle()
    // nothing until the stack is this root's: never a frame of a stack left from another
    if (cards.firstOrNull()?.repo != root.repo) return
    val depth = cards.lastIndex
    val top = cards[depth]
    key(stack.storeKey(depth, top)) {
        val place =
            remember(stack, depth, top) {
                CardPlace(
                    stack.owner(depth, top),
                    onBack = if (depth > 0) ({ stack.pop() }) else null,
                    onRelated = stack::push,
                )
            }
        ModelSheetRoute(
            screens,
            top,
            place,
            onStarted = onStarted,
            onDismiss = {
                stack.close()
                onDismiss()
            },
            onChatWith = onChatWith,
            onExactFit = { onExactFit(top, it) },
            startTier = startTier.takeIf { depth == 0 },
        )
    }
}
