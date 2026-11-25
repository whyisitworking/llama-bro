package com.suhel.llamabro.demo.ui.models

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.reflect.KClass

/** The cards a model's sheet stacks (D156): Back pops, a card already open pops back, closing clears every store. */
class CardStackTest {
    private class Probe : ViewModel() {
        var cleared = false

        override fun onCleared() {
            cleared = true
        }
    }

    private val probes =
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: KClass<T>, extras: CreationExtras): T = Probe() as T
        }

    private val stores = SheetStores()
    private val stack = CardStack(stores)

    private val qwen = entry("unsloth/Qwen3.5-4B-GGUF")
    private val gemma = entry("unsloth/gemma-3-4b-it-GGUF")
    private val llama = entry("bartowski/Llama-3.2-3B-Instruct-GGUF")

    private fun entry(repo: String) =
        ModelEntry(groupKey = repo, name = repo, repo = repo, downloads = null, maker = null, catalog = null)

    /** The card at [depth]'s view model, as its route makes it in the card's own store. */
    private fun CardStack.probe(depth: Int, entry: ModelEntry): Probe =
        ViewModelProvider.create(owner(depth, entry), probes)[Probe::class]

    @Test fun pushThenPopReturnsToTheRoot() {
        stack.open(qwen)
        stack.push(gemma)
        assertEquals(listOf(qwen, gemma), stack.cards.value)
        val root = stack.probe(0, qwen)
        val pushed = stack.probe(1, gemma)
        assertTrue(stack.pop())
        assertEquals(listOf(qwen), stack.cards.value)
        // the card popped stops its work; the one under it keeps its state
        assertTrue(pushed.cleared)
        assertFalse(root.cleared)
        assertSame(root, stack.probe(0, qwen))
    }

    @Test fun pushingAnOpenCardPopsBackToIt() {
        stack.open(qwen)
        stack.push(gemma)
        stack.push(llama)
        val root = stack.probe(0, qwen)
        val middle = stack.probe(1, gemma)
        val top = stack.probe(2, llama)
        // a page linking back to a model already in the stack, its repo cased as the link has it
        stack.push(entry("Unsloth/Qwen3.5-4B-GGUF"))
        assertEquals(listOf(qwen), stack.cards.value)
        assertTrue(middle.cleared && top.cleared)
        assertFalse(root.cleared)
    }

    @Test fun popOnTheRootIsFalse() {
        stack.open(qwen)
        val root = stack.probe(0, qwen)
        assertFalse(stack.pop())
        assertEquals(listOf(qwen), stack.cards.value)
        assertFalse(root.cleared)
    }

    @Test fun closeClearsEveryCardsStore() {
        stack.open(qwen)
        stack.push(gemma)
        stack.push(llama)
        val probes = listOf(stack.probe(0, qwen), stack.probe(1, gemma), stack.probe(2, llama))
        stack.close()
        assertTrue(probes.all { it.cleared })
        assertEquals(emptyList<ModelEntry>(), stack.cards.value)
    }

    @Test fun openingAnotherRootClearsTheOldCards() {
        stack.open(qwen)
        stack.push(gemma)
        val old = listOf(stack.probe(0, qwen), stack.probe(1, gemma))
        stack.open(llama)
        assertEquals(listOf(llama), stack.cards.value)
        assertTrue(old.all { it.cleared })
    }

    @Test fun theStackSurvivesARecreatedOwner() {
        // the screen's back-stack entry: its store outlives the activity a rotation recreates
        val entryStore = ViewModelStore()
        val first = stackOf(entryStore)
        first.open(qwen)
        first.push(gemma)
        first.push(llama)
        val cards = listOf(first.probe(0, qwen), first.probe(1, gemma), first.probe(2, llama))
        val again = stackOf(entryStore)
        assertSame(first, again)
        assertEquals(listOf(qwen, gemma, llama), again.cards.value)
        // each card's own state, its view model, is the one it had
        assertSame(cards[0], again.probe(0, qwen))
        assertSame(cards[1], again.probe(1, gemma))
        assertSame(cards[2], again.probe(2, llama))
        assertFalse(cards.any { it.cleared })
    }

    /** What `ModelCardStack` asks of a (new) owner over [entryStore]: the entry's sheet stores, then its stack. */
    private fun stackOf(entryStore: ViewModelStore): CardStack {
        val owner =
            object : ViewModelStoreOwner {
                override val viewModelStore = entryStore
            }
        val sheets = ViewModelProvider.create(owner, viewModelFactory { initializer { SheetStores() } })
        val stores = sheets[SheetStores::class]
        val stacks = ViewModelProvider.create(owner, viewModelFactory { initializer { CardStack(stores) } })
        return stacks[CARD_STACK_KEY, CardStack::class]
    }
}
