package com.suhel.llamabro.sample

import android.app.Activity
import android.os.Bundle
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.suhel.llamabro.LlamaBro
import com.suhel.llamabro.Model
import com.suhel.llamabro.ModelSource
import com.suhel.llamabro.chat.ChatEvent
import com.suhel.llamabro.conversation
import com.suhel.llamabro.conversation.Conversation
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File

/**
 * The smallest useful app on the SDK: loads the first GGUF in filesDir and streams replies.
 * Push a model with: adb shell run-as com.suhel.llamabro.sample cp /data/local/tmp/<file>.gguf files/
 */
class MainActivity : Activity() {
    private val scope = MainScope()
    private var model: Model? = null
    private var chat: Conversation? = null
    private var reply: Job? = null

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

        val gguf = filesDir.listFiles { f: File -> f.name.endsWith(".gguf") && !f.name.startsWith("mmproj") }?.minByOrNull { it.name }
        if (gguf == null) {
            output.text = "No .gguf in ${filesDir.path}"
            return
        }
        output.text = "Loading ${gguf.name}…"
        scope.launch {
            try {
                val m = LlamaBro.getInstance(this@MainActivity).loadModel(ModelSource.file(gguf.path))
                model = m
                chat = m.conversation(system = "You are a helpful assistant.")
                output.text = "${m.info.description}, context ${m.info.contextSize}\n"
                android.util.Log.i("LlamaBroSample", "loaded ${m.info.description}")
                send.isEnabled = true
            } catch (e: Exception) {
                output.text = "Load failed: ${e.message}"
                android.util.Log.e("LlamaBroSample", "load failed", e)
            }
        }

        send.setOnClickListener {
            val text = input.text.toString().trim()
            val c = chat ?: return@setOnClickListener
            if (text.isEmpty() || reply?.isActive == true) return@setOnClickListener
            input.text.clear()
            output.append("\n> $text\n")
            reply = scope.launch {
                try {
                    c.send(text).collect { e ->
                        when (e) {
                            is ChatEvent.Content -> output.append(e.delta)
                            is ChatEvent.Done -> e.timings?.let {
                                output.append("\n[%.1f t/s prefill, %.1f t/s decode]\n".format(it.promptPerSecond, it.predictedPerSecond))
                            }
                            else -> Unit
                        }
                        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
                    }
                } catch (e: Exception) {
                    if (e !is kotlinx.coroutines.CancellationException) output.append("\n[error: ${e.message}]\n")
                }
            }
        }
        stop.setOnClickListener { reply?.cancel() }
    }

    override fun onDestroy() {
        scope.cancel()
        // close() blocks for up to about a second while running requests stop: keep it off the main thread
        model?.let { m -> Thread { m.close() }.start() }
        super.onDestroy()
    }
}
