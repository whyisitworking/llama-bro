package com.suhel.llamabro.download

import android.Manifest
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.PersistableBundle
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.suhel.llamabro.LlamaBro
import com.suhel.llamabro.chat.Message
import com.suhel.llamabro.chat.Reasoning
import com.suhel.llamabro.conversation
import com.suhel.llamabro.hub.DownloadState
import com.suhel.llamabro.hub.HfModel
import com.suhel.llamabro.hub.LlamaBroHub
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/** The app owns scheduling; the Hub owns the resumable transfer and local model source. */
class MainActivity : ComponentActivity() {
    private lateinit var hub: LlamaBroHub
    private lateinit var reference: EditText
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var userJob: CheckBox
    private var watching: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        (application as DownloadApplication).rememberDebugEndpoint(intent.getStringExtra("endpoint"))
        hub = LlamaBroHub.getInstance(this)

        reference = EditText(this).apply {
            setSingleLine(true)
            hint = "Hugging Face model"
            setText(intent.getStringExtra("ref") ?: "unsloth/Qwen3.5-4B-GGUF:Q4_K_M")
        }
        status = TextView(this).apply { setPadding(24, 24, 24, 24); setTextIsSelectable(true) }
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
        }
        userJob = CheckBox(this).apply {
            text = "User-initiated job (Android 14+)"
            isEnabled = Build.VERSION.SDK_INT >= 34
        }
        val plan = Button(this).apply { text = "Plan"; setOnClickListener { plan() } }
        val download = Button(this).apply { text = "Download"; setOnClickListener { download() } }
        val chat = Button(this).apply { text = "Chat"; setOnClickListener { chat() } }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
                val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                WindowInsetsCompat.CONSUMED
            }
            addView(reference, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(LinearLayout(context).apply { addView(plan); addView(download); addView(chat) })
            addView(userJob)
            addView(progress, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(ScrollView(context).apply { addView(status) }, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        }
        setContentView(layout)
        askNotifications()
        watchReference()
        reference.doAfterTextChanged { watchReference() }
        if (BuildConfig.DEBUG && intent.getBooleanExtra("autostart", false)) download.performClick()
    }

    private fun selected(): HfModel = HfModel.parse(reference.text.toString().trim())

    private fun watchReference() {
        watching?.cancel()
        val model = try { selected() } catch (error: IllegalArgumentException) {
            status.text = error.message
            return
        }
        watching = lifecycleScope.launch {
            hub.progress(model).collect { state ->
                when (state) {
                    DownloadState.Absent -> { progress.progress = 0; status.text = "Ready to download $model" }
                    is DownloadState.Partial -> showBytes("Paused", state.bytesDone, state.bytesTotal)
                    is DownloadState.Downloading -> {
                        val point = state.progress
                        showBytes(point.phase.toString().substringAfterLast('.'), point.bytesDone, point.bytesTotal)
                    }
                    DownloadState.Complete -> { progress.progress = 100; status.text = "Download complete: $model" }
                    is DownloadState.Failed -> status.text = "Download failed: ${state.error.message}"
                }
            }
        }
    }

    private fun showBytes(phase: String, done: Long, total: Long) {
        progress.progress = if (total > 0) (done * 100 / total).toInt().coerceIn(0, 100) else 0
        status.text = "$phase: ${done / 1_048_576}/${total / 1_048_576} MB"
    }

    private fun plan() {
        val model = try { selected() } catch (error: IllegalArgumentException) {
            status.text = error.message
            return
        }
        lifecycleScope.launch {
            try {
                val choice = hub.plan(model)
                val fit = hub.estimateFit(choice, LlamaBro.getInstance(this@MainActivity))
                status.text = buildString {
                    append("${choice.files.size} files, ${choice.bytesTotal / 1_048_576} MB total\n")
                    choice.files.forEach { append("${it.role}: ${it.path} (${it.sizeBytes / 1_048_576} MB)\n") }
                    append("Fits: ${fit.fits}; context: ${fit.contextTokens}; minimum: ${fit.bytesAtMinimum / 1_048_576} MB")
                }
            } catch (error: Exception) {
                status.text = "Plan failed: ${error.message}"
            }
        }
    }

    private fun download() {
        val model = try { selected() } catch (error: IllegalArgumentException) {
            status.text = error.message
            return
        }
        watchReference()
        if (userJob.isChecked && Build.VERSION.SDK_INT >= 34) {
            lifecycleScope.launch {
                try {
                    val choice = hub.plan(model)
                    val info = JobInfo.Builder(UserDownloadJobService.jobId(model.toString()),
                        ComponentName(this@MainActivity, UserDownloadJobService::class.java))
                        .setUserInitiated(true)
                        .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                        .setEstimatedNetworkBytes(choice.bytesToDownload, 0)
                        .setExtras(PersistableBundle().apply { putString("ref", model.toString()) })
                        .build()
                    val result = getSystemService(JobScheduler::class.java).schedule(info)
                    status.text = if (result == JobScheduler.RESULT_SUCCESS) "Download job scheduled"
                        else "Download job could not be scheduled"
                } catch (error: Exception) {
                    status.text = "Download job failed: ${error.message}"
                }
            }
        } else {
            val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
                .setInputData(Data.Builder().putString("ref", model.toString()).build())
                .build()
            WorkManager.getInstance(this).enqueueUniqueWork(model.toString(), ExistingWorkPolicy.KEEP, request)
        }
    }

    private fun chat() {
        val model = try { selected() } catch (error: IllegalArgumentException) {
            status.text = error.message
            return
        }
        lifecycleScope.launch {
            try {
                val source = hub.source(model)
                val loaded = LlamaBro.getInstance(this@MainActivity).loadModel(source)
                try {
                    // A one-line greeting needs no thinking; Qwen3.5 thinks by default and could spend the turn on it.
                    val conversation = loaded.conversation(system = "You are a helpful assistant.") {
                        defaults { reasoning = Reasoning { enableThinking = false } }
                    }
                    conversation.send("Say hello in five words.").collect()
                    val reply = conversation.messages.value.filterIsInstance<Message.Assistant>().lastOrNull()
                    status.text = reply?.content?.takeIf { it.isNotBlank() }
                        ?: reply?.reasoning?.takeIf { it.isNotBlank() }?.let { "(reasoning only) $it" }
                        ?: "(empty reply)"
                } finally {
                    loaded.closeAndJoin()
                }
            } catch (error: Exception) {
                status.text = "Chat failed: ${error.message}"
            }
        }
    }

    private fun askNotifications() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }
}
