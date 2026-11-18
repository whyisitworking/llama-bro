package com.suhel.llamabro.demo.ui.models

import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * The view model stores of a screen's sheets, one per key, held in a view model of the screen's own back-stack entry:
 * they survive a rotation with it, and go when it goes.
 */
class SheetStores : ViewModel() {
    private val stores = HashMap<String, ViewModelStore>()

    fun store(key: String): ViewModelStore = stores.getOrPut(key) { ViewModelStore() }

    /** The sheet [key] closed: its view models stop their work. */
    fun clear(key: String) {
        stores.remove(key)?.clear()
    }

    override fun onCleared() {
        stores.values.forEach(ViewModelStore::clear)
        stores.clear()
    }
}

/**
 * A view model store that lives as long as the sheet [key] is open. A sheet's view model must stop its work when the
 * sheet closes, not when the whole screen under it goes (the entry's own store would keep it until then), and must
 * survive a rotation, which disposes the sheet's composition without closing it.
 */
@Composable
fun rememberSheetViewModelOwner(key: String): ViewModelStoreOwner {
    val stores = viewModel { SheetStores() }
    val activity = LocalActivity.current
    val owner =
        remember(stores, key) {
            object : ViewModelStoreOwner {
                override val viewModelStore = stores.store(key)
            }
        }
    DisposableEffect(stores, key) {
        onDispose { if (activity?.isChangingConfigurations != true) stores.clear(key) }
    }
    return owner
}
