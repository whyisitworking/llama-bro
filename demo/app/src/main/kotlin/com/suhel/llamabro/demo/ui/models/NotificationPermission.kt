package com.suhel.llamabro.demo.ui.models

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/**
 * Returns an action that, when [asks], asks for the notification permission (Android 13 and later, when it isn't
 * granted) and then
 * runs [proceed], whatever the answer: a download doesn't wait on the notification, it only loses it (demo design
 * §4.1).
 */
@Composable
fun rememberAskNotificationsThen(asks: Boolean, proceed: () -> Unit): () -> Unit {
    val context = LocalContext.current
    val latest = rememberUpdatedState(proceed)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { latest.value() }
    return {
        val needed =
            asks && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
        if (needed) launcher.launch(Manifest.permission.POST_NOTIFICATIONS) else latest.value()
    }
}
