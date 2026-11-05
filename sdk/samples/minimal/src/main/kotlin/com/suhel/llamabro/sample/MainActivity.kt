package com.suhel.llamabro.sample

import android.os.Bundle
import android.util.Log
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.viewModels
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.io.File

/**
 * The smallest useful app on the SDK: loads the first GGUF in filesDir and streams replies. The model lives in
 * [ChatViewModel], so rotation doesn't reload it.
 * Push a model with: adb shell run-as com.suhel.llamabro.sample cp /data/local/tmp/<file>.gguf files/
 */
class MainActivity : ComponentActivity() {
    private val vm: ChatViewModel by viewModels {
        val gguf = filesDir.listFiles { f: File -> f.name.endsWith(".gguf") && !f.name.startsWith("mmproj") }?.minByOrNull { it.name }
        ChatViewModel.factory(application, gguf?.path)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val output = TextView(this).apply { id = R.id.output; setPadding(32, 32, 32, 32); setTextIsSelectable(true) }
        val scroll = ScrollView(this).apply { addView(output) }
        val input = EditText(this).apply { id = R.id.input; hint = "Message" }
        val send = Button(this).apply { id = R.id.send; text = "Send"; isEnabled = false }
        val stop = Button(this).apply { id = R.id.stop; text = "Stop" }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // targetSdk 35+ draws edge to edge and adjustResize no longer resizes the window: pad the layout by
            // the system bars and the keyboard so the Send button stays visible while typing
            ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
                val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                WindowInsetsCompat.CONSUMED
            }
            addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
            addView(input, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(LinearLayout(context).apply { addView(send); addView(stop) })
        })

        send.setOnClickListener {
            vm.send(input.text.toString().trim())
            input.text.clear()
        }
        stop.setOnClickListener { vm.stop() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    vm.ready.collect {
                        send.isEnabled = it
                        // check.sh's minified-build step waits for this line (R8 rules, F28)
                        if (it) Log.i("LlamaBroSample", "loaded: ${vm.status.value}")
                    }
                }
                launch {
                    combine(vm.status, vm.transcript, vm.stats) { status, transcript, stats ->
                        status + "\n" + transcript + (stats?.let { "$it\n" } ?: "")
                    }.collect {
                        output.text = it
                        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
                    }
                }
            }
        }
    }
}
