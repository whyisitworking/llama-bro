package com.suhel.llamabro.demo.designsystem

import android.content.res.Configuration
import androidx.compose.ui.tooling.preview.Preview

/**
 * The screenshot matrix of Reference L for a component group: light and dark at 360 dp (the mockups' phone), the
 * narrowest phone (320 dp) at font scale 2.0 in both themes, and a wide one (411 dp) in dark. The heights are tall enough for the
 * longest group, so nothing is cut at the default device's screen height.
 */
@Preview(name = "light-360", widthDp = 360, heightDp = 1400)
@Preview(name = "dark-360", widthDp = 360, heightDp = 1400, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "light-320-font2", widthDp = 320, heightDp = 2600, fontScale = 2f)
@Preview(
    name = "dark-320-font2",
    widthDp = 320,
    heightDp = 2600,
    fontScale = 2f,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Preview(name = "dark-411", widthDp = 411, heightDp = 1400, uiMode = Configuration.UI_MODE_NIGHT_YES)
annotation class ComponentPreviews
