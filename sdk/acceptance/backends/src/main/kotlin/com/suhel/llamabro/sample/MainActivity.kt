package com.suhel.llamabro.sample

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.suhel.llamabro.LlamaBro
import com.suhel.llamabro.ModelSource
import kotlinx.coroutines.launch
import java.io.File

/**
 * Has no screen: loads the first GGUF in filesDir and logs "loaded" (or why not). `check.sh`'s minified-release step
 * launches it and waits for that line, which proves llamabro's consumer R8 rules (F28).
 * Push a model with: adb shell run-as com.suhel.llamabro.sample cp /data/local/tmp/<file>.gguf files/
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val gguf =
            filesDir
                .listFiles { f: File -> f.name.endsWith(".gguf") && !f.name.startsWith("mmproj") }
                ?.minByOrNull { it.name }
        if (gguf == null) {
            Log.e(TAG, "No .gguf in the app's files")
            return
        }
        lifecycleScope.launch {
            try {
                val model = LlamaBro.create(applicationContext).loadModel(ModelSource.file(gguf.path))
                Log.i(TAG, "loaded: ${model.info.description}, context ${model.info.contextSize}")
                model.close()
            } catch (e: Exception) {
                Log.e(TAG, "load failed: $e")
            }
        }
    }

    private companion object {
        const val TAG = "LlamaBroAcceptance"
    }
}
