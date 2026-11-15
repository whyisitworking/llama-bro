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
        val dir =
            SkeletonInstall(
                File(context.noBackupFilesDir, "llamabro/htp"),
                Assets(context.assets, ASSET_DIR),
            ).install()
        // upstream's way since b12352: ggml-hexagon opens the skeleton by its absolute path in this directory, and
        // falls back by itself to FastRPC's search path and then to the DSP *_LIBRARY_PATH variables. The SDK no longer
        // sets those variables (F19, F36), which are process-wide and may belong to the app's other DSP users
        val env = mapOf("GGML_HEXAGON_LIBHTP_DIR" to dir.absolutePath)
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
