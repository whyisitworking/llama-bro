package com.suhel.llamabro.demo.ui.models

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner

/**
 * A view model store that lives exactly as long as the composable that remembers it. A sheet's view model must stop its
 * work (estimates over the network) when the sheet closes, not when the whole screen under it goes: the activity's own
 * store would keep it until then.
 */
@Composable
fun rememberSheetViewModelOwner(): ViewModelStoreOwner {
    val owner =
        remember {
            object : ViewModelStoreOwner {
                override val viewModelStore = ViewModelStore()
            }
        }
    DisposableEffect(owner) { onDispose { owner.viewModelStore.clear() } }
    return owner
}
