package com.suhel.llamabro.sample

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.suhel.llamabro.LlamaBro
import com.suhel.llamabro.ModelSource
import com.suhel.llamabro.typed.LbDescription
import com.suhel.llamabro.typed.LbRange
import com.suhel.llamabro.typed.typedSchema
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** What the minified build decodes before it loads a model: a description, a bound and a default (D63, D88). */
@Serializable
data class Probe(
    @LbDescription("A city") val city: String,
    @LbRange(min = 1, max = 7) val days: Int,
    val unit: String = "C",
)

/**
 * Has no screen: checks typed decoding, loads the first GGUF in filesDir and logs "loaded" (or why not).
 * `check.sh`'s minified-release step launches it and waits for that line, which proves llamabro's consumer R8 rules
 * (F28) and that llamabro-typed's schema and decoding survive R8: a typed failure logs instead of "loaded".
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
        val typed = typedProblem()
        if (typed != null) {
            Log.e(TAG, "typed decoding failed after R8: $typed")
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

    /** Null when the schema and a decode come out as without R8, else what differs. */
    private fun typedProblem(): String? {
        val schema = typedSchema<Probe>()
        val expected =
            """{"type":"object","properties":{"city":{"type":"string","description":"A city"},""" +
                """"days":{"type":"integer","minimum":1,"maximum":7},"unit":{"type":"string"}},""" +
                """"required":["city","days"],"additionalProperties":false}"""
        if (schema != expected) return "schema $schema"
        // the default fills `unit`, so the generated serializer and its defaults survived too
        val probe = Json.decodeFromString<Probe>("""{"city":"Paris","days":$PROBE_DAYS}""")
        return if (probe == Probe("Paris", PROBE_DAYS, "C")) null else "decoded $probe"
    }

    private companion object {
        const val TAG = "LlamaBroAcceptance"
        const val PROBE_DAYS = 3
    }
}
