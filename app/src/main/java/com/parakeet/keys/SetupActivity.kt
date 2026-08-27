package com.parakeet.keys

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import java.lang.ref.WeakReference

class SetupActivity : Activity() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var modelStatus: TextView
    private lateinit var progress: ProgressBar
    private lateinit var downloadButton: Button
    private lateinit var permissionButton: Button
    private lateinit var versionGroup: RadioGroup
    private lateinit var runtimeStatus: TextView
    private val refreshDownloadState = object : Runnable {
        override fun run() {
            updateState()
            if (ModelManager.currentDownload() != null) {
                mainHandler.postDelayed(this, DOWNLOAD_REFRESH_MS)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildContent())
        updateState()
    }

    override fun onResume() {
        super.onResume()
        if (::modelStatus.isInitialized) {
            updateState()
            scheduleDownloadRefresh()
        }
    }

    override fun onPause() {
        mainHandler.removeCallbacks(refreshDownloadState)
        super.onPause()
    }

    private fun buildContent(): ScrollView {
        val padding = dp(24)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
            setBackgroundColor(getColor(R.color.keyboard_background))
        }

        content.addView(text("Parakeet Keys", 30f, Color.WHITE).apply {
            setPadding(0, 0, 0, dp(8))
        })
        content.addView(text(
            "A compact command keyboard with local Parakeet TDT 0.6B dictation. " +
                "No speech leaves the phone.",
            17f,
            getColor(R.color.muted_text),
        ))

        content.addView(section("1. Microphone"))
        permissionButton = actionButton("Grant microphone access") {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_AUDIO)
        }
        content.addView(permissionButton)

        content.addView(section("2. Speech model"))
        versionGroup = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
            ModelManager.Version.entries.forEach { version ->
                addView(RadioButton(this@SetupActivity).apply {
                    id = View.generateViewId()
                    tag = version
                    text = getString(
                        R.string.model_option,
                        version.displayName,
                        version.languageDescription,
                    )
                    setTextColor(Color.WHITE)
                    textSize = 16f
                    isChecked = version == ModelManager.selectedVersion(this@SetupActivity)
                })
            }
            setOnCheckedChangeListener { group, checkedId ->
                val version = group.findViewById<RadioButton>(checkedId)?.tag
                    as? ModelManager.Version ?: return@setOnCheckedChangeListener
                ModelManager.selectVersion(this@SetupActivity, version)
                updateState()
            }
        }
        content.addView(versionGroup)
        modelStatus = text("Checking model...", 16f, Color.WHITE)
        content.addView(modelStatus)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            isIndeterminate = false
        }
        content.addView(progress, rowParams(dp(12)))
        downloadButton = actionButton("Download speech model") { downloadModel() }
        content.addView(downloadButton)

        content.addView(section("3. Enable keyboard"))
        content.addView(actionButton("Open keyboard settings") {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        })
        content.addView(actionButton("Choose Parakeet Keys") {
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
        })

        content.addView(section("Runtime"))
        val soc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Build.SOC_MODEL.ifBlank { Build.HARDWARE }
        } else {
            Build.HARDWARE
        }
        runtimeStatus = text(
            "",
            15f,
            getColor(R.color.muted_text),
        )
        runtimeStatus.tag = soc
        content.addView(runtimeStatus)

        content.addView(section("Attribution"))
        content.addView(text(
            "Parakeet TDT 0.6B v2 and v3 by NVIDIA are licensed CC BY 4.0. " +
                "The on-device runtime is sherpa-onnx.",
            14f,
            getColor(R.color.muted_text),
        ))

        return ScrollView(this).apply { addView(content) }
    }

    private fun downloadModel() {
        downloadButton.isEnabled = false
        modelStatus.setText(R.string.download_starting)
        val activity = WeakReference(this)
        val version = ModelManager.selectedVersion(this)
        ModelManager.download(
            context = applicationContext,
            version = version,
            onProgress = { _, _, _ -> },
            onComplete = { error ->
                activity.get()?.let { owner ->
                    owner.mainHandler.post {
                        activity.get()?.let { current ->
                            current.updateState()
                            if (error != null) {
                                current.modelStatus.text = current.getString(
                                    R.string.download_failed,
                                    error.message,
                                )
                            }
                        }
                    }
                }
            },
        )
        scheduleDownloadRefresh()
    }

    private fun updateState() {
        val granted = checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        permissionButton.text = if (granted) "Microphone access granted" else "Grant microphone access"
        permissionButton.isEnabled = !granted

        val download = ModelManager.currentDownload()
        if (download != null) {
            setVersionSelectionEnabled(false)
            val ratio = download.downloaded.toDouble() / download.total.toDouble()
            progress.progress = (ratio * progress.max).toInt()
            modelStatus.text = if (download.currentFile.isBlank()) {
                getString(R.string.model_download_starting, download.version.displayName)
            } else {
                getString(
                    R.string.download_progress,
                    download.currentFile,
                    download.downloaded / 1_000_000.0,
                    download.total / 1_000_000.0,
                )
            }
            downloadButton.isEnabled = false
            return
        }

        setVersionSelectionEnabled(true)
        val version = ModelManager.selectedVersion(this)
        val installed = ModelManager.isInstalled(this, version)
        val megabytes = ModelManager.totalBytes(version) / 1_000_000
        modelStatus.text = if (installed) {
            "${version.displayName} INT8 is installed and verified."
        } else {
            "Required download: $megabytes MB. Interrupted downloads resume automatically."
        }
        progress.progress = if (installed) progress.max else 0
        downloadButton.isEnabled = !installed
        downloadButton.text = if (installed) "Model installed" else "Download ${version.displayName} INT8"

        val soc = runtimeStatus.tag as String
        runtimeStatus.text = getString(
            R.string.runtime_status,
            soc,
            version.displayName,
            version.languageDescription,
            megabytes,
        )
    }

    private fun scheduleDownloadRefresh() {
        mainHandler.removeCallbacks(refreshDownloadState)
        if (ModelManager.currentDownload() != null) {
            mainHandler.postDelayed(refreshDownloadState, DOWNLOAD_REFRESH_MS)
        }
    }

    private fun setVersionSelectionEnabled(enabled: Boolean) {
        for (index in 0 until versionGroup.childCount) {
            versionGroup.getChildAt(index).isEnabled = enabled
        }
    }

    private fun section(title: String) = text(title, 20f, Color.WHITE).apply {
        setPadding(0, dp(28), 0, dp(8))
    }

    private fun text(value: String, size: Float, color: Int) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        setLineSpacing(0f, 1.15f)
    }

    private fun actionButton(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 16f
        gravity = Gravity.CENTER_VERTICAL
        setOnClickListener { action() }
    }

    private fun rowParams(topMargin: Int) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    ).apply { this.topMargin = topMargin }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val REQUEST_AUDIO = 100
        private const val DOWNLOAD_REFRESH_MS = 500L
    }
}
