package com.suhel.llamabro.demo.ui.models

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.CreationExtras
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.reflect.KClass

class SheetStoresTest {
    private class Probe : ViewModel() {
        var cleared = false

        override fun onCleared() {
            cleared = true
        }
    }

    private val factory =
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: KClass<T>, extras: CreationExtras): T = Probe() as T
        }

    /** What `rememberSheetViewModelOwner` does on each composition, a rotation's included. */
    private fun SheetStores.probe(key: String): Probe {
        val owner =
            object : ViewModelStoreOwner {
                override val viewModelStore = store(key)
            }
        return ViewModelProvider.create(owner, factory)[Probe::class]
    }

    @Test fun aSheetKeepsItsViewModelAcrossARotationAndLosesItWhenClosed() {
        val stores = SheetStores()
        val first = stores.probe("sheet:unsloth/Qwen3.5-0.8B-GGUF")
        // a rotation composes the sheet again over the same entry's stores: the same view model, fits and all
        assertSame(first, stores.probe("sheet:unsloth/Qwen3.5-0.8B-GGUF"))
        assertFalse(first.cleared)
        stores.clear("sheet:unsloth/Qwen3.5-0.8B-GGUF") // dismissed
        assertTrue(first.cleared)
        assertNotSame(first, stores.probe("sheet:unsloth/Qwen3.5-0.8B-GGUF"))
    }

    @Test fun theScreenGoingClearsEverySheet() {
        val stores = SheetStores()
        val a = stores.probe("sheet:a")
        val b = stores.probe("sheet:b")
        SheetStores::class.java
            .getDeclaredMethod("onCleared")
            .apply { isAccessible = true }
            .invoke(stores)
        assertTrue(a.cleared && b.cleared)
    }
}
