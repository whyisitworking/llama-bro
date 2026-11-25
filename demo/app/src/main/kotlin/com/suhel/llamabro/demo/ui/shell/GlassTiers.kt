package com.suhel.llamabro.demo.ui.shell

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import com.suhel.llamabro.demo.designsystem.theme.GlassTier

/**
 * The glass this phone draws (D153): [GlassTier.Full] on one that declares Android 13's media performance class or a
 * later one and isn't a low-memory phone; [GlassTier.Light] otherwise, a phone that declares no class (0) included.
 * The glass is measured on a flagship only (F186), so a phone that doesn't vouch for itself gets the lighter glass.
 */
fun glassTier(performanceClass: Int, lowRam: Boolean): GlassTier =
    if (performanceClass >= Build.VERSION_CODES.TIRAMISU && !lowRam) GlassTier.Full else GlassTier.Light

/** [glassTier] for the phone the app runs on. */
fun glassTierOf(context: Context): GlassTier {
    val performanceClass =
        if (Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.S
        ) {
            Build.VERSION.MEDIA_PERFORMANCE_CLASS
        } else {
            0
        }
    val lowRam = context.getSystemService(ActivityManager::class.java)?.isLowRamDevice ?: false
    return glassTier(performanceClass, lowRam)
}
