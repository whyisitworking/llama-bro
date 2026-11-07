package com.suhel.llamabro.hexagon

import android.content.Context
import android.content.res.AssetManager
import com.suhel.llamabro.internal.BackendInstaller
import com.suhel.llamabro.internal.BackendLibraries
import java.io.File
import java.io.InputStream

/**
 * Called by the core when the runtime starts (BackendDiscovery, through this module's manifest). Copies the HTP
 * skeletons out of the APK and points the DSP loader at them; the skeleton for this chip is picked by the backend
 * (`libggml-htp-v<arch>.so`), so all of them are installed.
 */
internal class HexagonInstaller : BackendInstaller {
    override fun install(context: Context): BackendLibraries {
        val dir = SkeletonInstall(File(context.noBackupFilesDir, "llamabro/htp"), Assets(context.assets, ASSET_DIR)).install()
        // as upstream's scripts/snapdragon/run.py: every DSP domain's search path (F19); verified from an app in F36
        val env = listOf("ADSP_LIBRARY_PATH", "CDSP_LIBRARY_PATH", "CDSP1_LIBRARY_PATH", "CDSP2_LIBRARY_PATH", "CDSP3_LIBRARY_PATH")
            .associateWith { dir.absolutePath }
        return BackendLibraries(listOf("libllamabro-ggml-hexagon.so"), env)
    }

    private class Assets(private val assets: AssetManager, private val dir: String) : SkeletonInstall.Source {
        override fun list(): List<String> = assets.list(dir)?.toList() ?: emptyList()
        override fun open(name: String): InputStream = assets.open("$dir/$name")
    }

    private companion object {
        const val ASSET_DIR = "llamabro/htp"
    }
}
